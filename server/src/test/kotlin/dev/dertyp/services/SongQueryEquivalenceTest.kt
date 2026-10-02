package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.utils.ColorUtils
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@Tag("equivalence")
class SongQueryEquivalenceTest : KoinTest {
    private lateinit var database: Database
    private val redisSearchService = mockk<RedisSearchService>(relaxed = true)

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "song_query_equivalence")
        transaction(database) {
            SchemaUtils.create(*SongQueryFixture.tables)
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<ArtistService>(relaxed = true) }
                single { mockk<AlbumService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<StorageService>(relaxed = true) }
                single { LibraryMergeService() }
                single { LibraryFileDeleter() }
                single { redisSearchService }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private class SortKey(val descending: Boolean, val value: (UUID) -> Comparable<*>?)

    private class Family(
        val label: String,
        val sizes: List<Int>,
        val legacyOrder: List<SortKey>?,
        val order: List<SortKey>?,
        val old: suspend (Int, Int) -> PaginatedResponse<*>,
        val new: suspend (Int, Int) -> PaginatedResponse<*>,
    )

    private class Single(val label: String, val old: suspend () -> Any?, val new: suspend () -> Any?)

    private class Cases {
        val families = mutableListOf<Family>()
        val singles = mutableListOf<Single>()
        val unordered = mutableListOf<Single>()

        fun single(label: String, old: suspend () -> Any?, new: suspend () -> Any?) {
            singles += Single(label, old, new)
        }

        fun unordered(label: String, old: suspend () -> List<BaseSong>, new: suspend () -> List<BaseSong>) {
            unordered += Single(label, old, new)
        }

        fun paged(
            label: String,
            sizes: List<Int>,
            old: suspend (Int, Int) -> PaginatedResponse<*>,
            new: suspend (Int, Int) -> PaginatedResponse<*>,
            order: List<SortKey>? = null,
            legacyOrder: List<SortKey>? = order,
        ) {
            families += Family(label, sizes, legacyOrder, order, old, new)
        }
    }

    private class Facts(
        val title: String,
        val albumName: String?,
        val albumId: String,
        val updatedAt: Long?,
        val superLikedAt: Long?,
        val releaseDate: String?,
        val trackNumber: Int,
        val discNumber: Int,
        val colorDistance: Double?,
    )

    private fun loadFacts(userId: UUID, color: Int): Map<UUID, Facts> = transaction(database) {
        val (l, a, b) = ColorUtils.rgbToLab((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        SongTable
            .leftJoin(UserSongTable, onColumn = { SongTable.id }, otherColumn = { UserSongTable.songId }, additionalConstraint = { UserSongTable.userId eq userId })
            .leftJoin(ImageMetadataTable, onColumn = { SongTable.cover }, otherColumn = { ImageMetadataTable.imageId })
            .leftJoin(AlbumTable, onColumn = { SongTable.albumId }, otherColumn = { AlbumTable.id })
            .selectAll()
            .associate { row ->
                val distance = row.getOrNull(ImageMetadataTable.labL)?.let { labL ->
                    val lDiff = labL - l
                    val aDiff = row[ImageMetadataTable.labA]!! - a
                    val bDiff = row[ImageMetadataTable.labB]!! - b
                    lDiff * lDiff + aDiff * aDiff + bDiff * bDiff
                }
                row[SongTable.id].value to Facts(
                    title = utf8Hex(row[SongTable.title]),
                    albumName = row.getOrNull(AlbumTable.name)?.let(::utf8Hex),
                    albumId = row[SongTable.albumId].value.toString(),
                    updatedAt = row.getOrNull(UserSongTable.updatedAt),
                    superLikedAt = row.getOrNull(UserSongTable.superLikedAt),
                    releaseDate = row[SongTable.releaseDate],
                    trackNumber = row[SongTable.trackNumber],
                    discNumber = row[SongTable.discNumber],
                    colorDistance = distance,
                )
            }
    }

    private fun utf8Hex(text: String) = text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun LegacySongQuery.rankedSongSearchOld(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID,
        searchVector: Boolean = false,
        scope: Query.() -> Query = { this }
    ): suspend () -> PaginatedResponse<UserSong> = {
        rankedSongSearch(redisSearchService, page, pageSize, query, explicit, userId, searchVector, scope)
    }

    private fun Query.inCollection(collectionId: UUID): Query = andWhere {
        (SongTable.id inSubQuery CollectionSongTable
            .select(CollectionSongTable.songId)
            .where { CollectionSongTable.collectionId eq collectionId }
        ) or (SongTable.albumId inSubQuery CollectionAlbumTable
            .select(CollectionAlbumTable.albumId)
            .where { CollectionAlbumTable.collectionId eq collectionId }
        ) or (SongTable.id inSubQuery SongArtistTable
            .innerJoin(CollectionArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { CollectionArtistTable.artistId })
            .select(SongArtistTable.songId)
            .where { CollectionArtistTable.collectionId eq collectionId }
        ) or (SongTable.albumId inSubQuery AlbumArtistTable
            .innerJoin(CollectionArtistTable, onColumn = { AlbumArtistTable.artistId }, otherColumn = { CollectionArtistTable.artistId })
            .select(AlbumArtistTable.albumId)
            .where { CollectionArtistTable.collectionId eq collectionId }
        ) or (SongTable.id inSubQuery UserPlaylistSongTable
            .innerJoin(CollectionPlaylistTable, onColumn = { UserPlaylistSongTable.playlistId }, otherColumn = { CollectionPlaylistTable.playlistId })
            .select(UserPlaylistSongTable.songId)
            .where { CollectionPlaylistTable.collectionId eq collectionId }
        )
    }

    private val searchQueries = listOf("Song 1", "Artist", "Artist 2 -Song", "Recording", "Alias", "MB Release", "Edge", "nothing")

    private fun buildVectorCases(cases: Cases, service: SongService, legacy: LegacySongQuery, fixture: SongQueryFixture) {
        val userId = fixture.userId
        val sizes = listOf(7, 100)
        for (explicit in listOf(true, false)) {
            for (query in searchQueries) {
                for (liked in if (query == "Song 1") listOf(false, true) else listOf(false)) {
                    cases.paged("vector rankedSearch '$query' liked=$liked explicit=$explicit", sizes, { page, size ->
                        legacy.rankedSongSearchOld(page, size, query, explicit, userId, searchVector = true) {
                            if (liked) andWhere { UserSongTable.isFavourite eq true }
                            else this
                        }()
                    }, { page, size -> service.rankedSearch(page, size, query, explicit, userId, liked) })
                }
            }
            cases.paged("vector rankedSearchInCollection explicit=$explicit", sizes, { page, size ->
                legacy.rankedSongSearchOld(page, size, "Song", explicit, userId, searchVector = true) {
                    inCollection(fixture.collectionId)
                }()
            }, { page, size -> service.rankedSearchInCollection(fixture.collectionId, page, size, "Song", explicit, userId) })
        }
    }

    private fun Query.applyTagsOld(tags: List<SongTag>, invert: Boolean): Query {
        if (tags.isNotEmpty()) {
            val conditions = tags.map { tag ->
                when (tag) {
                    SongTag.Q_44_48 -> (SongTable.sampleRate eq 44100) or (SongTable.sampleRate eq 48000)
                    SongTag.Q_96 -> (SongTable.sampleRate eq 96000)
                    SongTag.Q_192 -> (SongTable.sampleRate eq 192000)
                    SongTag.B_16 -> (SongTable.bitsPerSample eq 16)
                    SongTag.B_24 -> (SongTable.bitsPerSample eq 24)
                    SongTag.HAS_LYRICS -> (SongTable.lyrics neq "")
                    SongTag.CUSTOM_UPLOAD -> Op.FALSE
                    SongTag.HAS_MUSICBRAINZ_ID -> (SongMusicBrainzTable.musicBrainzId.isNotNull())
                }
            }
            val combinedCondition = conditions.reduce { acc, op -> acc or op }
            if (invert) andWhere { not(combinedCondition) }
            else andWhere { combinedCondition }
        }
        return this
    }

    private fun buildCases(service: SongService, legacy: LegacySongQuery, fixture: SongQueryFixture, facts: Map<UUID, Facts>): Cases {
        val cases = Cases()
        val userId = fixture.userId
        val sizes = listOf(7, 100)
        val smallResultSizes = listOf(1) + sizes
        val byRelease = listOf(
            SortKey(true) { facts.getValue(it).releaseDate },
            SortKey(false) { facts.getValue(it).trackNumber },
        )
        val byReleaseThenAlbum = listOf(
            SortKey(true) { facts.getValue(it).releaseDate },
            SortKey(false) { facts.getValue(it).albumName },
            SortKey(false) { facts.getValue(it).albumId },
            SortKey(false) { facts.getValue(it).discNumber },
            SortKey(false) { facts.getValue(it).trackNumber },
        )

        for (explicit in listOf(true, false)) {
            cases.paged("allSongs explicit=$explicit", sizes, { page, size ->
                legacy.querySongs<UserSong>(page, size, explicit, userId, query = {
                    applyTagsOld(emptyList(), false)
                    orderBy(SongTable.inserted, SortOrder.DESC)
                    orderBy(SongTable.id, SortOrder.ASC)
                })
            }, { page, size -> service.allSongs(page, size, explicit, userId) })

            for (invert in listOf(false, true)) {
                val tags = listOf(SongTag.B_24, SongTag.HAS_LYRICS, SongTag.HAS_MUSICBRAINZ_ID)
                cases.paged("allSongs tags invert=$invert explicit=$explicit", sizes, { page, size ->
                    legacy.querySongs<UserSong>(page, size, explicit, userId, query = {
                        applyTagsOld(tags, invert)
                        orderBy(SongTable.inserted, SortOrder.DESC)
                        orderBy(SongTable.id, SortOrder.ASC)
                    })
                }, { page, size -> if (invert) service.allSongs(page, size, explicit, userId, excludeTags = tags)
                else service.allSongs(page, size, explicit, userId, tags)
                })
            }

            cases.paged("likedSongs explicit=$explicit", sizes, { page, size ->
                legacy.querySongs<UserSong>(page, size, explicit, userId) {
                    where { UserSongTable.isFavourite eq true }
                    orderBy(UserSongTable.updatedAt to SortOrder.DESC)
                }
            }, { page, size -> service.likedSongs(page, size, explicit, userId) },
                listOf(SortKey(true) { facts.getValue(it).updatedAt }))

            cases.paged("superLikedSongs explicit=$explicit", sizes, { page, size ->
                legacy.querySongs<UserSong>(page, size, explicit, userId) {
                    where { UserSongTable.superLikedAt.isNotNull() }
                    orderBy(UserSongTable.superLikedAt to SortOrder.DESC)
                }
            }, { page, size -> service.superLikedSongs(page, size, explicit, userId) },
                listOf(SortKey(true) { facts.getValue(it).superLikedAt }))

            cases.paged("byColor explicit=$explicit", sizes, { page, size ->
                val match = ColorMatch(COLOR, 40)
                legacy.querySongs<UserSong>(
                    page, size, explicit, userId,
                    columnSet = { match.join(this, SongTable.cover) },
                    query = { match.filterAndOrder(this) }
                )
            }, { page, size -> service.byColor(page, size, COLOR, 40, explicit, userId) },
                listOf(SortKey(false) { facts.getValue(it).colorDistance }))

            for (artistIndex in listOf(0, 2, 5)) {
                val artistId = fixture.artistIds[artistIndex]
                cases.paged("likedByArtist $artistIndex explicit=$explicit", sizes, { page, size ->
                    legacy.querySongs<UserSong>(page, size, explicit, userId) {
                        val songIds = SongArtistTable
                            .select(SongArtistTable.songId)
                            .where { SongArtistTable.artistId eq artistId }
                            .map { it[SongArtistTable.songId].value }

                        val albumIds = AlbumArtistTable
                            .select(AlbumArtistTable.albumId)
                            .where { AlbumArtistTable.artistId eq artistId }
                            .map { it[AlbumArtistTable.albumId].value }

                        where { SongTable.id inList songIds }
                        orWhere { SongTable.albumId inList albumIds }
                        andWhere { UserSongTable.isFavourite eq true }
                        orderBy(SongTable.releaseDate, SortOrder.DESC)
                        orderBy(SongTable.trackNumber, SortOrder.ASC)
                    }
                }, { page, size -> service.likedByArtist(page, size, artistId, explicit, userId) }, byReleaseThenAlbum, byRelease)
            }

            for (query in searchQueries) {
                for (liked in if (query == "Song 1") listOf(false, true) else listOf(false)) {
                    cases.paged("rankedSearch '$query' liked=$liked explicit=$explicit", sizes, { page, size ->
                        legacy.rankedSongSearchOld(page, size, query, explicit, userId) {
                            if (liked) andWhere { UserSongTable.isFavourite eq true }
                            else this
                        }()
                    }, { page, size -> service.rankedSearch(page, size, query, explicit, userId, liked) })
                }
            }

            cases.paged("rankedSearchInCollection explicit=$explicit", sizes, { page, size ->
                legacy.rankedSongSearchOld(page, size, "Song", explicit, userId) {
                    inCollection(fixture.collectionId)
                }()
            }, { page, size -> service.rankedSearchInCollection(fixture.collectionId, page, size, "Song", explicit, userId) })

            cases.paged("searchByLyrics explicit=$explicit", sizes, { page, size ->
                legacy.querySongsRanked<UserSong>(page, size, explicit, userId, columnSet = {
                    leftJoin(
                        SyncedLyricsTable,
                        onColumn = { SongTable.id },
                        otherColumn = { SyncedLyricsTable.songId })
                }) {
                    rankedSearchQuery(
                        redisSearchService,
                        "la",
                        listOf(10, 8),
                        listOf(
                            SyncedLyricsTable.rawLyrics,
                            SongTable.lyrics
                        ),
                        SongTable.id
                    )
                }
            }, { page, size -> service.searchByLyrics(page, size, "la", explicit, userId) })
        }

        for (title in listOf("Song 3", "Song 3 🅴", "Edge B")) {
            cases.paged("byTitle '$title'", smallResultSizes, { page, size ->
                legacy.querySongs<UserSong>(page, size, true, userId) {
                    where { SongTable.title eq title }
                }
            }, { page, size -> service.byTitle(page, size, title, userId) }, emptyList())
        }

        for (artistIndex in listOf(0, 1, 2, 5)) {
            val artistId = fixture.artistIds[artistIndex]
            cases.paged("byArtist $artistIndex", sizes, { page, size ->
                legacy.querySongs<UserSong>(page, size, true, userId) {
                    val songIds = SongArtistTable
                        .select(SongArtistTable.songId)
                        .where { SongArtistTable.artistId eq artistId }
                        .map { it[SongArtistTable.songId].value }

                    val albumIds = AlbumArtistTable
                        .select(AlbumArtistTable.albumId)
                        .where { AlbumArtistTable.artistId eq artistId }
                        .map { it[AlbumArtistTable.albumId].value }

                    where { SongTable.id inList songIds }
                    orWhere { SongTable.albumId inList albumIds }
                    orderBy(SongTable.releaseDate, SortOrder.DESC)
                    orderBy(SongTable.trackNumber, SortOrder.ASC)
                }
            }, { page, size -> service.byArtist(page, size, artistId, userId) }, byReleaseThenAlbum, byRelease)
        }

        for (albumIndex in listOf(0, 1, 2, 3, 4)) {
            val albumId = fixture.albumIds[albumIndex]
            cases.paged("byAlbum $albumIndex", smallResultSizes, { page, size ->
                legacy.querySongs<UserSong>(page, size, true, userId) {
                    where { SongTable.albumId eq albumId }
                    orderBy(SongTable.discNumber, SortOrder.ASC)
                    orderBy(SongTable.trackNumber, SortOrder.ASC)
                }
            }, { page, size -> service.byAlbum(page, size, albumId, userId) }, listOf(
                SortKey(false) { facts.getValue(it).discNumber },
                SortKey(false) { facts.getValue(it).trackNumber },
                SortKey(false) { facts.getValue(it).title },
            ), listOf(
                SortKey(false) { facts.getValue(it).discNumber },
                SortKey(false) { facts.getValue(it).trackNumber },
            ))
        }

        cases.paged("byPlaylist", sizes, { page, size ->
            legacy.querySongs<UserSong>(page, size, true, userId, {
                leftJoin(PlaylistSongTable)
            }) {
                where { PlaylistSongTable.playlistId eq fixture.playlistId }
                orderBy(PlaylistSongTable.position, SortOrder.ASC)
                orderBy(SongTable.id, SortOrder.ASC)
            }
        }, { page, size -> service.byPlaylist(page, size, fixture.playlistId, userId) })

        cases.paged("byUserPlaylist", sizes, { page, size ->
            legacy.querySongs<UserSong>(page, size, true, userId, {
                leftJoin(UserPlaylistSongTable)
            }) {
                where { UserPlaylistSongTable.playlistId eq fixture.userPlaylistId }
                orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
                orderBy(SongTable.id, SortOrder.ASC)
            }
        }, { page, size -> service.byUserPlaylist(page, size, fixture.userPlaylistId, userId) })

        val missing = UUID(0L, 42L)
        val idLists = listOf(
            fixture.songIds,
            fixture.songIds.reversed(),
            listOf(fixture.songIds[3], missing, fixture.songIds[3], fixture.songIds[0], fixture.songIds[1], fixture.songIds[3]),
            fixture.songIds.filterIndexed { index, _ -> index % 4 == 1 } + missing,
            listOf(missing),
            emptyList(),
        )
        idLists.forEachIndexed { index, ids ->
            cases.single("byIds user #$index", {
                legacy.querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
                    where { SongTable.id inList ids }
                }.let { response ->
                    val songMap = response.data.associateBy { it.id }
                    ids.mapNotNull { songMap[it] }
                }
            }, { service.byIds(ids, userId) })
            cases.single("byIds song #$index", {
                legacy.querySongs<Song>(0, Int.MAX_VALUE, true) {
                    where { SongTable.id inList ids }
                }.let { response ->
                    val songMap = response.data.associateBy { it.id }
                    ids.mapNotNull { songMap[it] }
                }
            }, { service.byIds(ids) })
        }

        for (id in fixture.songIds + missing) {
            cases.single("byId user $id", {
                legacy.querySingle<UserSong>(userId) { where { SongTable.id eq id } }
            }, { service.byId(id, userId) })
            cases.single("byId song $id", {
                legacy.querySingle<Song> { where { SongTable.id eq id } }
            }, { service.byId(id) })
        }

        for (recordingId in fixture.recordingIds.take(3)) {
            cases.unordered("byMusicBrainzId $recordingId", {
                legacy.querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
                    where { SongMusicBrainzTable.musicBrainzId eq recordingId }
                }.data
            }, { service.byMusicBrainzId(recordingId, userId) })
        }

        val originalIds = fixture.songIds.indices.filter { it % 6 == 0 }.map { "https://example.com/track/$it" } +
                listOf("https://tidal.example/track/7", "https://tidal.com/browse/track/7", "unknown")
        cases.unordered("byOriginalIds", {
            originalIds.chunked(PROVIDER_LOOKUP_CHUNK_SIZE).flatMap { idChunk ->
                val parsedLookups = idChunk.mapNotNull { providerLookup(it, Type.SONG) }
                legacy.querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
                    val songIdsFromProviders = SongProviderTable
                        .select(SongProviderTable.songId)
                        .where {
                            (SongProviderTable.type eq Type.SONG.value) and (
                                    (SongProviderTable.rawUrl inList idChunk) or
                                            (SongProviderTable.externalId inList idChunk) or
                                            SongProviderTable.matchesAny(parsedLookups)
                                    )
                        }
                        .map { it[SongProviderTable.songId].value }

                    where {
                        SongTable.originalUrl inList idChunk
                    }
                    orWhere {
                        SongTable.id inList songIdsFromProviders
                    }
                }.data
            }
        }, { service.byOriginalIds(originalIds, userId) })

        val tracks = fixture.songIds.indices.filter { it % 9 == 0 }.map { i ->
            IMetadataService.Track(id = "t$i", title = "none", isrc = "USABC${1000000 + i}", duration = 1.milliseconds, images = emptyList())
        } + IMetadataService.Track(id = "t-title", title = "Song 7", duration = 180000.milliseconds, images = emptyList())
        cases.unordered("byOriginalTracks", {
            legacy.querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
                where {
                    tracks.map { track ->
                        (SongTable.originalUrl eq "https://tidal.com/browse/track/${track.id}") or
                                (if (track.isrc?.isNotBlank() == true) SongTable.isrc eq track.isrc else Op.FALSE) or
                                ((SongTable.title eq track.title) and
                                        (SongTable.duration eq track.duration.inWholeMilliseconds))
                    }.reduce { acc, op -> acc or op }
                }
            }.data
        }, { service.byOriginalTracks(tracks, userId) })

        return cases
    }

    private fun comparator(order: List<SortKey>): Comparator<UUID> {
        var result: Comparator<UUID> = Comparator { _, _ -> 0 }
        for (key in order) {
            val byKey = compareBy<UUID> { key.value(it) }
            result = result.then(if (key.descending) byKey.reversed() else byKey)
        }
        return result
    }

    private class Outcome {
        val mismatches = mutableListOf<String>()
        val quirks = mutableListOf<String>()
        val tieOrders = mutableListOf<String>()
        var identicalPages = 0
        var quirkPages = 0
        var comparedPages = 0
    }

    private fun ids(response: PaginatedResponse<*>): List<UUID> = response.data.map { (it as BaseSong).id }

    private suspend fun compareFamily(tag: String, family: Family, outcome: Outcome) {
        val legacyAll = family.old(0, Int.MAX_VALUE)
        val newAll = family.new(0, Int.MAX_VALUE)
        val legacyIds = ids(legacyAll)
        val canonical = if (family.order == null) legacyAll.data else {
            val legacyKeyOrder = comparator(family.legacyOrder.orEmpty())
            val sortedByKeys = legacyIds.zipWithNext().all { (a, b) -> legacyKeyOrder.compare(a, b) <= 0 }
            if (!sortedByKeys) outcome.mismatches += "$tag ${family.label}: legacy result is not sorted by the legacy keys"
            legacyAll.data.sortedWith(compareBy<Any?, UUID>(comparator(family.order).then(uuidOrder)) { (it as BaseSong).id })
        }
        val correctTotal = canonical.size
        val canonicalIds = canonical.map { (it as BaseSong).id }
        if (family.order != null && canonicalIds != legacyIds) {
            outcome.tieOrders += tieOrder(tag, family.label, legacyIds, canonicalIds, family.order) { ids(family.old(0, Int.MAX_VALUE)) }
        }
        val expectedAll = PaginatedResponse(canonical, 0, correctTotal, Int.MAX_VALUE, false)
        if (newAll != expectedAll) {
            outcome.mismatches += "$tag ${family.label} unpaged\n${describeDifference(expectedAll, newAll)}"
        }
        if (legacyAll.total != correctTotal) {
            outcome.quirks += "$tag ${family.label} unpaged: legacy total ${legacyAll.total} counts songs before the explicit filter and duplicate dedupe, ${correctTotal} returned"
        }

        for (size in family.sizes) {
            val seen = mutableSetOf<UUID>()
            var earlierQuirk = false
            val lastPage = correctTotal / size + 1
            for (page in 0..lastPage) {
                val from = minOf(page * size, correctTotal)
                val to = minOf(from + size, correctTotal)
                val expected = PaginatedResponse(canonical.subList(from, to), page, correctTotal, size, (page + 1).toLong() * size < correctTotal)
                val newPage = family.new(page, size)
                val legacyPage = family.old(page, size)
                outcome.comparedPages++
                if (newPage != expected) {
                    outcome.mismatches += "$tag ${family.label} size=$size page=$page\n${describeDifference(expected, newPage)}"
                }
                if (legacyPage == expected) {
                    outcome.identicalPages++
                    if (newPage != legacyPage) outcome.mismatches += "$tag ${family.label} size=$size page=$page differs from a quirk free legacy page"
                } else {
                    outcome.quirkPages++
                    val pageIds = ids(legacyPage)
                    val expectedIds = ids(expected)
                    val reasons = mutableListOf<String>()
                    if (legacyPage.total != correctTotal) reasons += "total ${legacyPage.total} instead of $correctTotal"
                    if (legacyPage.data.size < expected.data.size) reasons += "short page ${legacyPage.data.size} of ${expected.data.size}"
                    val repeated = pageIds.filter { it in seen }
                    if (repeated.isNotEmpty()) reasons += "${repeated.size} songs repeated from earlier pages"
                    val dropped = pageIds.filter { id -> canonical.none { (it as BaseSong).id == id } }
                    if (dropped.isNotEmpty()) reasons += "${dropped.size} songs the unpaged result drops"
                    if (pageIds.toSet() == expectedIds.toSet() && pageIds != expectedIds) reasons += "tie order"
                    if (earlierQuirk && pageIds != expectedIds) reasons += "shifted by earlier pages"
                    if (family.order !== family.legacyOrder && pageIds != expectedIds) reasons += "album grouped sort order"
                    if (family.order != null && pageIds.toSet() != expectedIds.toSet() && reasons.isEmpty()) reasons += "tie order across the page boundary"
                    if (reasons.isEmpty()) outcome.mismatches += "$tag ${family.label} size=$size page=$page legacy differs without a known quirk"
                    outcome.quirks += "$tag ${family.label} size=$size page=$page: ${reasons.joinToString(", ")}"
                    earlierQuirk = true
                }
                seen += ids(legacyPage)
            }
        }
    }

    private fun short(id: UUID) = id.toString().take(8)

    private suspend fun tieOrder(
        tag: String,
        label: String,
        legacyIds: List<UUID>,
        newIds: List<UUID>,
        order: List<SortKey>,
        repeat: suspend () -> List<UUID>,
    ): String {
        val deterministic = List(3) { repeat() }.all { it == legacyIds }
        val first = legacyIds.indices.first { legacyIds[it] != newIds[it] }
        val last = legacyIds.indices.last { legacyIds[it] != newIds[it] }
        val keys = { id: UUID -> order.map { it.value(id) } }
        val legacyPart = legacyIds.subList(first, last + 1).joinToString(" ") { "${short(it)}${keys(it)}" }
        val newPart = newIds.subList(first, last + 1).joinToString(" ") { "${short(it)}${keys(it)}" }
        return "$tag $label: legacy tie order deterministic=$deterministic, positions $first..$last, legacy=[$legacyPart] new=[$newPart]"
    }

    private fun describeDifference(old: Any?, new: Any?): String {
        val oldItems = flatten(old)
        val newItems = flatten(new)
        if (oldItems.size != newItems.size) return "  sizes expected=${oldItems.size} actual=${newItems.size}\n  expected=${summary(old)}\n  actual=${summary(new)}"
        val index = oldItems.indices.first { oldItems[it] != newItems[it] }
        val a = oldItems[index]
        val b = newItems[index]
        if (a is BaseSong && b is BaseSong) {
            val fields = listOf<Pair<String, (BaseSong) -> Any?>>(
                "id" to { it.id }, "artists" to { it.artists.map { artist -> artist.id } }, "artistsFull" to { it.artists },
                "album" to { it.album }, "albumArtists" to { it.album?.artists?.map { artist -> artist.id } },
                "genres" to { it.genres }, "originalUrl" to { it.originalUrl }, "whole" to { it },
            )
            val differing = fields.filter { (_, get) -> get(a) != get(b) }.map { (name, get) -> "$name expected=${get(a)} actual=${get(b)}" }
            return "  item #$index\n  " + differing.joinToString("\n  ")
        }
        return "  item #$index expected=$a actual=$b"
    }

    private fun summary(value: Any?): String = when (value) {
        is PaginatedResponse<*> -> "page=${value.page} total=${value.total} next=${value.hasNextPage} ids=${ids(value)}"
        is List<*> -> value.map { (it as? BaseSong)?.id ?: it }.toString()
        else -> value.toString()
    }

    private fun flatten(value: Any?): List<Any?> = when (value) {
        is PaginatedResponse<*> -> listOf(value.copy(data = emptyList())) + value.data
        is List<*> -> value.flatMap { flatten(it) }
        else -> listOf(value)
    }

    private suspend fun buildAll(dialect: DbDialect, duplicates: Boolean): Triple<Cases, SongQueryFixture, LegacySongQuery> {
        val fixture = SongQueryFixture.build(database, seed = 7, songCount = 60, duplicates = duplicates)
        val service = SongService()
        val legacy = LegacySongQuery(service)
        val facts = loadFacts(fixture.userId, COLOR)
        val cases = buildCases(service, legacy, fixture, facts)
        if (dialect == DbDialect.POSTGRES) {
            val worker = SearchIndexWorker()
            transaction(database) {
                SchemaUtils.createIndex(Index(listOf(SongTable.searchVector), false, "song_search_vector_idx", indexType = "GIN"))
                SearchIndexQueueTable.batchInsert(fixture.songIds) { songId ->
                    this[SearchIndexQueueTable.entityId] = songId
                    this[SearchIndexQueueTable.entityType] = SearchIndexEntityType.SONG
                }
            }
            var processed = worker.processBatch()
            while (processed > 0) processed = worker.processBatch()
            val indexed = transaction(database) {
                SongTable.selectAll().where { SongTable.searchVector.isNotNull() }.count()
            }
            assertEquals(fixture.songIds.size.toLong(), indexed)
            val vectorService = SongService(worker)
            buildVectorCases(cases, vectorService, LegacySongQuery(vectorService), fixture)
        }
        return Triple(cases, fixture, legacy)
    }

    private fun runEquivalence(dialect: DbDialect, duplicates: Boolean) = runBlocking {
        setup(dialect)
        val (cases, fixture, legacy) = buildAll(dialect, duplicates)
        val tag = "$dialect duplicates=$duplicates"
        val outcome = Outcome()
        val started = System.nanoTime()
        for (family in cases.families) {
            val familyStart = System.nanoTime()
            compareFamily(tag, family, outcome)
            println("FAMILY $tag ${family.label} ${(System.nanoTime() - familyStart) / 1_000_000}ms")
        }
        for (single in cases.singles) {
            val old = single.old()
            val new = single.new()
            if (old != new) outcome.mismatches += "$tag ${single.label}\n${describeDifference(old, new)}"
        }
        for (single in cases.unordered) {
            val old = single.old() as List<*>
            val new = single.new() as List<*>
            val byId = compareBy<Any?, UUID>(uuidOrder) { (it as BaseSong).id }
            if (old.sortedWith(byId) != new) outcome.mismatches += "$tag ${single.label}\n${describeDifference(old.sortedWith(byId), new)}"
            val oldIds = old.map { (it as BaseSong).id }
            val newIds = new.map { (it as BaseSong).id }
            if (oldIds != newIds && oldIds.size == newIds.size) {
                outcome.tieOrders += tieOrder(tag, single.label, oldIds, newIds, emptyList()) { (single.old() as List<*>).map { (it as BaseSong).id } }
            }
        }
        println("EQUIVALENCE $tag families=${cases.families.size} singles=${cases.singles.size} pages=${outcome.comparedPages} identicalPages=${outcome.identicalPages} quirkPages=${outcome.quirkPages} mismatches=${outcome.mismatches.size} elapsed=${(System.nanoTime() - started) / 1_000_000}ms")
        outcome.quirks.forEach { println("QUIRK $it") }
        outcome.tieOrders.forEach { println("TIEORDER $it") }
        outcome.mismatches.take(10).forEach { println("MISMATCH $it") }

        if (duplicates) {
            val coverage = fixtureCoverage(legacy.querySongs<Song>(0, Int.MAX_VALUE, false) { this }.data, fixture)
            println("COVERAGE $tag $coverage")
            assertEquals(emptyList<String>(), coverage.filterValues { it == 0 }.keys.toList())
        }
        assertTrue(cases.families.size > 50)
        assertTrue(outcome.identicalPages > 0)
        assertEquals(emptyList<String>(), outcome.mismatches.map { it.substringBefore('\n') })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the slim song id query matches the legacy join query`(dialect: DbDialect) = runEquivalence(dialect, duplicates = true)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the slim song id query matches the legacy join query without duplicate songs`(dialect: DbDialect) = runEquivalence(dialect, duplicates = false)

    private fun fixtureCoverage(songs: List<Song>, fixture: SongQueryFixture): Map<String, Int> {
        val byIdString = compareBy<Artist> { it.id.toString() }
        return mapOf(
            "artistsOutOfIdOrder" to songs.count { it.artists != it.artists.sortedWith(byIdString) },
            "albumArtistsOutOfIdOrder" to songs.count { song -> song.album?.artists?.let { it != it.sortedWith(byIdString) } == true },
            "artistJoinPhrases" to songs.count { song -> song.artists.any { it.joinPhrase != null } },
            "albumArtistJoinPhrases" to songs.count { song -> song.album?.artists?.any { it.joinPhrase != null } == true },
            "genresOutOfIdOrder" to songs.count { song -> song.genres != song.genres.sortedBy { it.id.toString() } },
            "originalUrlFromMatchingProvider" to songs.count { it.originalUrl.startsWith("https://example.com/") && it.id in songsWithProviders(songs) },
            "originalUrlFromFirstProvider" to songs.count { it.originalUrl.contains(".example/track/") },
        ) + duplicatePairCoverage(fixture)
    }

    private fun olderHasGreaterId(pair: List<ResultRow>): Boolean? {
        val (older, newer) = pair.sortedBy { it[SongTable.inserted] }
        if (older[SongTable.inserted] == newer[SongTable.inserted]) return null
        return older[SongTable.id].value.toString() > newer[SongTable.id].value.toString()
    }

    private fun duplicatePairCoverage(fixture: SongQueryFixture): Map<String, Int> = transaction(database) {
        val rows = SongTable.select(SongTable.id, SongTable.inserted, SongTable.explicit)
            .where { SongTable.id inList fixture.songIds }
            .associateBy { it[SongTable.id].value }
        val cleanPairs = fixture.songIds.chunked(2)
            .map { pair -> pair.map { rows.getValue(it) } }
            .filter { pair -> pair.size == 2 && pair.none { it[SongTable.explicit] } }
        mapOf(
            "cleanDuplicatesEqualInserted" to cleanPairs.count { (a, b) -> a[SongTable.inserted] == b[SongTable.inserted] },
            "cleanDuplicatesOlderHasGreaterId" to cleanPairs.count { pair -> olderHasGreaterId(pair) == true },
            "cleanDuplicatesOlderHasSmallerId" to cleanPairs.count { pair -> olderHasGreaterId(pair) == false },
        )
    }

    private fun songsWithProviders(songs: List<Song>): Set<UUID> = transaction(database) {
        SongProviderTable.select(SongProviderTable.songId)
            .where { SongProviderTable.songId inList songs.map { it.id } }
            .map { it[SongProviderTable.songId].value }
            .toSet()
    }

    private companion object {
        const val COLOR = 0x777777
    }
}
