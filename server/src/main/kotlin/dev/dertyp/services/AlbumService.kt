package dev.dertyp.services

import dev.dertyp.*
import dev.dertyp.core.*
import dev.dertyp.core.date.*
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.plugins.AlbumLibrary
import dev.dertyp.plugins.parsePartialDate
import dev.dertyp.services.ArtistService.Companion.mapArtist
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.release.AlbumVersionGroups
import dev.dertyp.utils.Barcodes
import dev.dertyp.utils.LogParam
import dev.dertyp.utils.parsers.ParserFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class AlbumRpcService(private val user: User, private val albumService: AlbumService) :
    IAlbumService {
    override suspend fun byId(id: UUID): Album? = albumService.byId(id, user.id)
    override suspend fun byMusicBrainzId(mbId: UUID): List<Album> = albumService.byMusicBrainzId(mbId, user.id)
    override suspend fun byMusicBrainzIds(@LogParam("size") mbIds: List<UUID>): List<Album?> =
        albumService.byMusicBrainzIds(mbIds, user.id)

    override suspend fun byOriginalIds(@LogParam("size") ids: Collection<PrefixedId>): List<Album> =
        albumService.byOriginalIds(ids)

    override suspend fun byOriginalUrls(urls: Collection<String>): Map<String, Album?> =
        albumService.byOriginalUrls(urls)

    override suspend fun byIds(@LogParam("size") ids: List<UUID>): List<Album> = albumService.byIds(ids, user.id)
    override suspend fun versions(id: UUID): List<Album> = albumService.versions(id, user.id)
    override suspend fun byVersionGroup(versionGroupId: UUID, explicit: Boolean): List<Album> =
        albumService.byVersionGroup(versionGroupId, explicit, user.id)

    override suspend fun byName(page: Int, pageSize: Int, name: String): PaginatedResponse<Album> =
        albumService.byName(page, pageSize, name, user.id)

    override suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String
    ): PaginatedResponse<Album> =
        albumService.rankedSearch(page, pageSize, query, user.id)

    override suspend fun allAlbums(page: Int, pageSize: Int, explicit: Boolean?): PaginatedResponse<Album> =
        albumService.allAlbumsGrouped(page, pageSize, explicit ?: true, user.id)

    override suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int
    ): PaginatedResponse<Album> = albumService.byColor(page, pageSize, color, range, user.id)

    override suspend fun updateAlbum(album: Album): Album? =
        albumService.updateAlbum(album, user.id)

    override suspend fun deleteAlbums(ids: List<UUID>): Boolean = albumService.deleteAlbums(ids)

    override suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        singles: Boolean,
        explicit: Boolean?
    ): PaginatedResponse<Album> =
        albumService.byArtistGrouped(page, pageSize, artistId, singles, explicit ?: true, user.id)

    override suspend fun fetchMusicBrainzId(id: UUID): Album? =
        albumService.fetchMusicBrainzId(id, user.id, HttpClientPriority.HIGH)

    override suspend fun setMusicBrainzId(id: UUID, musicBrainzId: UUID?): Album? =
        albumService.setMusicBrainzId(id, musicBrainzId, user.id)

    override suspend fun extendedMetadata(id: UUID): AlbumExtendedMetadata? =
        albumService.extendedMetadata(id)
}

private const val VERSION_GROUP_UPDATE_CHUNK_SIZE = 1000
private const val RELEASE_DATE_CHUNK_SIZE = 5000

class AlbumService(private val searchIndexWorker: SearchIndexWorker? = null) : AlbumLibrary, Service() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val cachedMusicBrainzService by inject<CachedMusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val artistService by inject<ArtistService>()
    private val genreService by inject<GenreService>()
    private val libraryMergeService by inject<LibraryMergeService>()
    private val linkResolverService by inject<LinkResolverService>()
    private val libraryFileDeleter by inject<LibraryFileDeleter>()
    private val entityEvents by inject<EntityEventPublisher>()
    private val versionGroupTrigger by inject<VersionGroupTrigger>()
    private val redisSearchService by inject<RedisSearchService>()
    val artistGroupAlias = ArtistTable.alias("artistGroup")
    val artistMemberAlias = ArtistTable.alias("artistMember")
    val artistGroupJoinAlias = ArtistMemberTable.alias("artistGroupJoin")
    val artistMemberJoinAlias = ArtistMemberTable.alias("artistMemberJoin")
    val albumArtistCreditedAlias = ArtistAliasTable.alias("albumArtistCreditedAlias")
    val albumAnimatedImageAlias = AnimatedImageTable.alias("albumAnimatedImage")
    val albumAnimatedFrameAlias = ImageTable.alias("albumAnimatedFrame")

    private val versionGroupMutex = Mutex()
    private val versionGroupPending = AtomicBoolean(false)
    private val versionGroupKey = Coalesce(AlbumTable.versionGroupId, AlbumTable.id)
    private val unknownReleaseDate = AlbumTable.releaseDate.isNull() or (AlbumTable.releaseDateEstimated eq true)
    private val knownReleaseDate = case()
        .When(AlbumTable.releaseDateEstimated eq false, AlbumTable.releaseDate)
        .Else(Op.nullOp())

    companion object {
        fun mapAlbum(
            resultRow: ResultRow,
            genres: List<Genre> = listOf(),
            blurHashColumn: Expression<String?>? = null,
            animatedCoverImageIdColumn: Expression<EntityID<UUID>?>? = null,
            animatedCoverBlurHashColumn: Expression<String?>? = null,
        ): Album {
            val id = resultRow[AlbumTable.id].value

            return Album(
                id = id,
                name = resultRow[AlbumTable.name],
                releaseDate = getDateFromISO(resultRow[AlbumTable.releaseDate]),
                artists = listOf(),
                songCount = resultRow[AlbumTable.songCount],
                totalDuration = -1,
                coverId = resultRow[AlbumTable.cover]?.value,
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash),
                genres = genres,
                originalId = resultRow[AlbumTable.originalId],
                barcode = resultRow[AlbumTable.barcode],
                musicBrainzId = resultRow.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value,
                animatedCoverId = resultRow[AlbumTable.animatedCover]?.value,
                animatedCoverImageId = animatedCoverImageIdColumn?.let { resultRow.getOrNull(it) }?.value,
                animatedCoverBlurHash = animatedCoverBlurHashColumn?.let { resultRow.getOrNull(it) },
                tags = resultRow.albumTitleTags(),
                versionGroupId = resultRow[AlbumTable.versionGroupId]?.value,
            )
        }

        suspend fun calculateAlbumStats(albumIds: List<UUID>): Map<UUID, Pair<Long, Long>> =
            dbQuery {
                SongTable
                    .select(SongTable.albumId, SongTable.duration.sum(), SongTable.fileSize.sum())
                    .where { SongTable.albumId inList albumIds }
                    .groupBy(SongTable.albumId)
                    .associate { row ->
                        row[SongTable.albumId].value to Pair(
                            row[SongTable.duration.sum()] ?: -1L,
                            row[SongTable.fileSize.sum()] ?: -1L
                        )
                    }
            }

        fun identityKey(album: InsertableAlbum): Any =
            album.originalId ?: listOf(album.name, album.tags, album.artists.sorted(), album.releaseDate)
    }

    fun map(resultRow: ResultRow): Album = mapAlbum(resultRow)

    suspend fun fetchMusicBrainzId(
        id: UUID,
        userId: UUID? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
        triggerMerge: Boolean = true
    ): Album? {
        val album = byId(id, userId) ?: return null

        val mbId = album.musicBrainzId ?: musicBrainzService.searchAlbumMb(album, priority)?.also {
            musicBrainzCacheService.updateReleaseCache(it)
        }?.id ?: return album

        val mbRelease = cachedMusicBrainzService.getRelease(mbId, priority)

        if (mbRelease != null) {
            val trackCount = mbRelease.media?.sumOf { it.trackCount ?: 0 } ?: 0

            val artistCredits = mbRelease.artistCredit ?: emptyList()

            val mbArtistIds = artistCredits.mapNotNull { it.artist?.id }.distinct()
            val existingArtistsByMbId = if (mbArtistIds.isNotEmpty()) {
                artistService.byMusicBrainzIds(mbArtistIds, userId).associateBy { it.musicBrainzId }
            } else emptyMap()

            val resolvedArtists = mutableListOf<ResolvedCredit>()
            val namesToResolve = artistCredits
                .filter { it.artist?.id == null || !existingArtistsByMbId.containsKey(it.artist?.id) }
                .mapNotNull { it.name ?: it.artist?.name }
                .distinct()

            val artistsByName = if (namesToResolve.isNotEmpty()) {
                artistService.getOrBulkCreateWithResult(namesToResolve)
            } else null

            val allCandidateIds =
                artistsByName?.nameToIds?.values?.flatten()?.distinct() ?: emptyList()
            val candidatesById = if (allCandidateIds.isNotEmpty()) {
                artistService.byIds(allCandidateIds, userId).associateBy { it.id }
            } else emptyMap()

            val candidatesWithEvidence =
                if (allCandidateIds.isNotEmpty() && mbArtistIds.isNotEmpty()) {
                    dbQuery {
                        val fromSongs = SongArtistTable
                            .innerJoin(
                                SongMusicBrainzTable,
                                onColumn = { SongArtistTable.songId },
                                otherColumn = { SongMusicBrainzTable.songId }
                            )
                            .innerJoin(
                                MBRecordingArtistCreditTable,
                                onColumn = { SongMusicBrainzTable.musicBrainzId },
                                otherColumn = { MBRecordingArtistCreditTable.recordingId }
                            )
                            .innerJoin(SongTable, onColumn = { SongArtistTable.songId }, otherColumn = { SongTable.id })
                            .select(SongArtistTable.artistId, MBRecordingArtistCreditTable.artistId)
                            .where { (SongArtistTable.artistId inList allCandidateIds) and (MBRecordingArtistCreditTable.artistId inList mbArtistIds) and (SongTable.albumId neq id) }
                            .map { it[SongArtistTable.artistId].value to it[MBRecordingArtistCreditTable.artistId].value }

                        val fromAlbums = AlbumArtistTable
                            .innerJoin(
                                AlbumMusicBrainzTable,
                                onColumn = { AlbumArtistTable.albumId },
                                otherColumn = { AlbumMusicBrainzTable.albumId }
                            )
                            .innerJoin(
                                MBReleaseArtistCreditTable,
                                onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                                otherColumn = { MBReleaseArtistCreditTable.releaseId }
                            )
                            .select(AlbumArtistTable.artistId, MBReleaseArtistCreditTable.artistId)
                            .where { (AlbumArtistTable.artistId inList allCandidateIds) and (MBReleaseArtistCreditTable.artistId inList mbArtistIds) and (AlbumArtistTable.albumId neq id) }
                            .map { it[AlbumArtistTable.artistId].value to it[MBReleaseArtistCreditTable.artistId].value }

                        (fromSongs + fromAlbums).toSet()
                    }
                } else emptySet()

            artistCredits.forEach { credit ->
                val mbId = credit.artist?.id
                val name = credit.name ?: credit.artist?.name ?: return@forEach
                val canonicalName = credit.artist?.name ?: name

                var artist = existingArtistsByMbId[mbId]
                if (artist == null && artistsByName != null) {
                    val ids = artistsByName.nameToIds[name] ?: emptyList()
                    val candidates = ids.mapNotNull { candidatesById[it] }

                    artist = candidates.find { candidate ->
                        mbId != null && candidatesWithEvidence.contains(candidate.id to mbId)
                    }

                    if (artist != null) {
                        if (mbId != null) {
                            artistService.setMusicBrainzId(artist.id, mbId, userId)
                            artist = artist.copy(musicBrainzId = mbId)
                        }
                    } else if (mbId != null) {
                        artist = artistService.createArtist(
                            name = canonicalName,
                            musicBrainzId = mbId,
                            userId = userId
                        )
                    } else {
                        artist = candidates.firstOrNull()
                    }
                }

                if (artist != null) {
                    val creditedName = credit.name?.takeIf { it.isNotBlank() && it != artist.name }
                    resolvedArtists.add(ResolvedCredit(artist, creditedName, credit.joinphrase))
                }
            }

            val finalArtists = resolvedArtists.distinctBy { it.artist.id }

            val mbTracks = mbRelease.media?.flatMapIndexed { mediaIndex, media ->
                val discNumber = mediaIndex + 1
                media.tracks?.map { track ->
                    Triple(discNumber, track.position ?: 1, track)
                } ?: emptyList()
            } ?: emptyList()

            dbQuery {
                val before = entityStates(EntityType.ALBUM, listOf(id))
                val artistsBefore = entityStates(EntityType.ARTIST, finalArtists.map { it.artist.id })
                if (trackCount > 0) {
                    AlbumTable.update({ AlbumTable.id eq id }) { row ->
                        row[songCount] = trackCount
                    }
                }

                if (finalArtists.isNotEmpty()) {
                    AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId eq id }
                    val creditedAliasIds = finalArtists.associate { (artist, creditedName) ->
                        artist.id to creditedName?.let { artistService.getOrCreateAliasTx(artist.id, it) }
                    }
                    AlbumArtistTable.batchInsert(finalArtists.withIndex().toList()) { (index, credit) ->
                        this[AlbumArtistTable.albumId] = id
                        this[AlbumArtistTable.artistId] = credit.artist.id
                        this[AlbumArtistTable.creditedAliasId] = creditedAliasIds[credit.artist.id]
                        this[AlbumArtistTable.position] = index
                        this[AlbumArtistTable.joinPhrase] = credit.joinPhrase
                    }
                }
                entityEvents.recordChanges(artistsBefore)
                entityEvents.recordChanges(before)
            }

            syncSongsWithMusicBrainz(id, mbTracks)

            val genres = (mbRelease.genres?.map { it.name }
                ?: emptyList()) + (mbRelease.releaseGroup?.genres?.map { it.name } ?: emptyList())
            if (genres.isNotEmpty()) {
                val genreIds = genreService.getOrCreateGenres(genres)
                dbQuery {
                    val before = entityStates(EntityType.ALBUM, listOf(id))
                    AlbumGenreTable.deleteWhere { AlbumGenreTable.albumId eq id }
                    AlbumGenreTable.batchInsert(genreIds) { genreId ->
                        this[AlbumGenreTable.albumId] = id
                        this[AlbumGenreTable.genreId] = genreId
                    }
                    entityEvents.recordChanges(before)
                }
            }
        }

        return setMusicBrainzId(id, mbId, userId, triggerMerge, triggerSync = false)
    }

    suspend fun syncAlbumSongsWithMusicBrainz(albumId: UUID, mbId: UUID) {
        val mbRelease = cachedMusicBrainzService.getRelease(mbId) ?: return

        val mbReleaseDate = parsePartialDate(mbRelease.date)
        if (mbReleaseDate != null) {
            dbQuery {
                fillUnknownReleaseDatesTx(mapOf(albumId to mbReleaseDate))
            }
        }

        if (mbRelease.barcode != null) {
            dbQuery {
                val before = entityStates(EntityType.ALBUM, listOf(albumId))
                AlbumTable.update({ AlbumTable.id eq albumId }) {
                    it[barcode] = mbRelease.barcode?.take(32)
                }
                entityEvents.recordChanges(before)
            }
        }

        val trackCount = mbRelease.media?.sumOf { it.trackCount ?: 0 } ?: 0
        val mbTracks = mbRelease.media?.flatMapIndexed { mediaIndex, media ->
            val discNumber = mediaIndex + 1
            media.tracks?.map { track ->
                Triple(discNumber, track.position ?: 1, track)
            } ?: emptyList()
        } ?: emptyList()

        if (trackCount > 0) {
            dbQuery {
                val before = entityStates(EntityType.ALBUM, listOf(albumId))
                AlbumTable.update({ AlbumTable.id eq albumId }) {
                    it[songCount] = trackCount
                }
                entityEvents.recordChanges(before)
            }
        }

        syncSongsWithMusicBrainz(albumId, mbTracks)
    }

    private data class DbSongMatch(
        val songId: UUID,
        val musicBrainzId: UUID?,
        val title: String,
        val fullTitle: String,
        val duration: Long,
        val isrc: String?,
    )

    suspend fun syncSongsWithMusicBrainz(id: UUID, mbTracks: List<Triple<Int, Int, MusicBrainzTrack>>) = dbQuery {
        if (mbTracks.isEmpty()) return@dbQuery

        val dbSongs = SongTable
            .leftJoin(SongMusicBrainzTable)
            .select(
                SongTable.id,
                SongTable.title,
                SongTable.titleTags,
                SongTable.trackNumber,
                SongTable.discNumber,
                SongTable.duration,
                SongTable.isrc,
                SongMusicBrainzTable.musicBrainzId
            )
            .where { SongTable.albumId eq id }
            .map { row ->
                val songId = row[SongTable.id].value
                val smbId = row.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value
                val title = row[SongTable.title]
                val fullTitle = row.fullSongTitle()
                val duration = row[SongTable.duration]
                val isrc = row[SongTable.isrc]
                DbSongMatch(songId, smbId, title, fullTitle, duration, isrc)
            }
        val before = entityStates(EntityType.SONG, dbSongs.map { it.songId })

        for ((discNo, trackNo, mbTrack) in mbTracks) {
            val mbRecordingId = mbTrack.recording?.id
            val mbTitle = mbTrack.title ?: mbTrack.recording?.title
            val mbDuration = mbTrack.recording?.length
            val mbIsrc = mbTrack.recording?.isrcs?.firstOrNull()

            val matchedSong = dbSongs.find { it.musicBrainzId == mbRecordingId }
                ?: dbSongs.find { mbIsrc != null && it.isrc == mbIsrc }
                ?: dbSongs.find { mbTitle != null && it.fullTitle.equals(mbTitle, ignoreCase = true) }
                ?: dbSongs.find {
                    mbTitle != null && it.fullTitle.cleanTitle().equals(mbTitle.cleanTitle(), ignoreCase = true)
                }
                ?: dbSongs.find { mbTitle != null && it.title.equals(mbTitle, ignoreCase = true) }
                ?: dbSongs.find {
                    mbTitle != null && it.title.cleanTitle().equals(mbTitle.cleanTitle(), ignoreCase = true)
                }
                ?: dbSongs.find {
                    mbTitle != null &&
                        mbDuration != null &&
                        abs(it.duration - mbDuration) < 2000 &&
                        it.title.cleanTitle().contains(mbTitle.cleanTitle(), ignoreCase = true)
                }

            if (matchedSong != null) {
                SongTable.update({ SongTable.id eq matchedSong.songId }) {
                    it[trackNumber] = trackNo
                    it[discNumber] = discNo
                    if (mbIsrc != null) {
                        it[isrc] = mbIsrc
                    }
                }

                if (mbRecordingId != null) {
                    mbTrack.recording?.let { musicBrainzCacheService.updateRecordingCache(it) }

                    SongMusicBrainzTable.upsert(SongMusicBrainzTable.songId) {
                        it[songId] = matchedSong.songId
                        it[musicBrainzId] = EntityID(mbRecordingId, MBRecordingTable)
                        it[lastCheck] = Clock.System.now().toEpochMilliseconds()
                    }
                }
            }
        }
        entityEvents.recordChanges(before)
    }

    suspend fun updateMusicBrainzLastCheck(id: UUID) = dbQuery {
        val exists = AlbumTable.select(AlbumTable.id).where { AlbumTable.id eq id }.any()
        if (!exists) return@dbQuery

        AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
            it[albumId] = id
            it[lastCheck] = Clock.System.now().toEpochMilliseconds()
        }
    }

    suspend fun setMusicBrainzId(
        id: UUID,
        musicBrainzId: UUID?,
        userId: UUID? = null,
        triggerMerge: Boolean = true,
        triggerSync: Boolean = true
    ): Album? {
        val currentMbId = dbQuery {
            AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.albumId eq id }
                .firstOrNull()?.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
        }

        if (musicBrainzId != null && triggerSync && musicBrainzId != currentMbId) {
            syncAlbumSongsWithMusicBrainz(id, musicBrainzId)
        }

        val mbRelease = if (musicBrainzId != null) {
            cachedMusicBrainzService.getRelease(musicBrainzId, HttpClientPriority.HIGH)
        } else null

        dbQuery {
            val before = entityStates(EntityType.ALBUM, listOf(id))
            AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
                it[albumId] = id
                it[AlbumMusicBrainzTable.musicBrainzId] = musicBrainzId
                it[lastCheck] = Clock.System.now().toEpochMilliseconds()
            }

            if (mbRelease?.barcode != null) {
                AlbumTable.update({ AlbumTable.id eq id }) {
                    it[barcode] = mbRelease.barcode?.take(32)
                }
            }
            entityEvents.recordChanges(before)

            val mbReleaseDate = parsePartialDate(mbRelease?.date)
            if (mbReleaseDate != null) fillUnknownReleaseDatesTx(mapOf(id to mbReleaseDate))
            if (musicBrainzId != null && triggerMerge) entityEvents.albumsLinkedToMusicBrainz(listOf(id))
        }

        versionGroupTrigger.requestRebuild()

        return byId(id, userId)
    }

    private fun albumIdsWithUnknownReleaseDate(albumIds: Collection<UUID>): Set<UUID> =
        albumIds.distinct().chunked(RELEASE_DATE_CHUNK_SIZE).flatMapTo(mutableSetOf()) { chunk ->
            AlbumTable
                .select(AlbumTable.id)
                .where { AlbumTable.id inList chunk }
                .andWhere { unknownReleaseDate }
                .map { it[AlbumTable.id].value }
        }

    fun fillUnknownReleaseDatesTx(releaseDates: Map<UUID, PlatformLocalDate>) {
        val open = albumIdsWithUnknownReleaseDate(releaseDates.keys)
        if (open.isEmpty()) return

        val before = entityStates(EntityType.ALBUM, open)
        for ((date, albumIds) in open.groupBy { releaseDates.getValue(it) }) {
            albumIds.chunked(RELEASE_DATE_CHUNK_SIZE).forEach { chunk ->
                AlbumTable.update({ AlbumTable.id inList chunk }) {
                    it[releaseDate] = getISOFromDate(date)
                    it[releaseDateEstimated] = false
                }
            }
        }
        entityEvents.recordChanges(before)
    }

    fun fillUnknownReleaseDatesFromSongsTx(albumIds: Collection<UUID>) {
        val open = albumIdsWithUnknownReleaseDate(albumIds)
        if (open.isEmpty()) return

        val songDates = open.chunked(RELEASE_DATE_CHUNK_SIZE).flatMap { chunk ->
            SongTable
                .select(SongTable.albumId, SongTable.releaseDate)
                .where { SongTable.albumId inList chunk }
                .andWhere { SongTable.releaseDate.isNotNull() }
                .withDistinct()
                .mapNotNull { row ->
                    parsePartialDate(row[SongTable.releaseDate])?.let { row[SongTable.albumId].value to it }
                }
        }
        fillUnknownReleaseDatesTx(songDates.groupBy({ it.first }, { it.second }).mapValues { (_, dates) -> dates.min() })
    }

    suspend fun byId(id: UUID, userId: UUID? = null): Album? = querySingle(userId = userId) {
        where { AlbumTable.id eq id }
    }

    suspend fun extendedMetadata(id: UUID): AlbumExtendedMetadata? = dbQuery {
        val albumExists = AlbumTable.selectAll().where { AlbumTable.id eq id }.any()
        if (!albumExists) return@dbQuery null

        val providers = AlbumProviderTable.selectAll()
            .where { AlbumProviderTable.albumId eq id }
            .map {
                ProviderEntry(
                    provider = it[AlbumProviderTable.provider],
                    externalId = it[AlbumProviderTable.externalId],
                    type = it[AlbumProviderTable.type],
                    rawUrl = it[AlbumProviderTable.rawUrl],
                    addedAt = it[AlbumProviderTable.addedAt]
                )
            }

        AlbumExtendedMetadata(
            providers = providers
        )
    }

    fun albumIdsForProviderEnrichment(excludeSingles: Boolean = false, onlySingles: Boolean = false): Flow<UUID> =
        flow {
            val oneWeekAgo = Clock.System.now() - 7.days

            AlbumTable
                .select(AlbumTable.id)
                .where {
                    var condition: Op<Boolean> = AlbumTable.lastProviderEnrichment.isNull() or
                        (AlbumTable.lastProviderEnrichment less oneWeekAgo.toEpochMilliseconds())

                    if (excludeSingles) {
                        condition = condition and (AlbumTable.songCount greater 1)
                    }
                    if (onlySingles) {
                        condition = condition and (AlbumTable.songCount eq 1)
                    }
                    condition
                }
                .orderBy(AlbumTable.lastProviderEnrichment, SortOrder.ASC)
                .orderBy(AlbumTable.id, SortOrder.ASC)
                .fetchBatchedResults(1000) { batch ->
                    batch.forEach {
                        emit(it[AlbumTable.id].value)
                    }
                }
        }

    fun albumIdsForBarcodeEnrichment(provider: String): Flow<UUID> = flow {
        val now = Clock.System.now().toEpochMilliseconds()
        val threshold = now - 30.days.inWholeMilliseconds

        AlbumTable
            .leftJoin(
                ProviderEnrichmentCheckTable,
                onColumn = { AlbumTable.id },
                otherColumn = { ProviderEnrichmentCheckTable.entityId },
                additionalConstraint = {
                    (ProviderEnrichmentCheckTable.provider eq provider) and
                        (ProviderEnrichmentCheckTable.type eq ProviderEnrichmentType.ALBUM)
                }
            )
            .select(AlbumTable.id)
            .where { (AlbumTable.barcode.isNotNull()) and (AlbumTable.barcode neq "") }
            .andWhere {
                notExists(AlbumProviderTable.select(AlbumProviderTable.albumId).where {
                    (AlbumProviderTable.albumId eq AlbumTable.id) and (AlbumProviderTable.provider eq provider)
                })
            }
            .andWhere {
                ProviderEnrichmentCheckTable.lastCheck.isNull() or
                    (ProviderEnrichmentCheckTable.lastCheck less threshold)
            }
            .fetchBatchedResultsByIdKeyset(AlbumTable.id, 1000) { batch ->
                batch.forEach {
                    emit(it[AlbumTable.id].value)
                }
            }
    }

    suspend fun updateProviderEnrichmentCheck(id: UUID, provider: String, type: ProviderEnrichmentType) = dbQuery {
        ProviderEnrichmentCheckTable.upsert(
            ProviderEnrichmentCheckTable.entityId,
            ProviderEnrichmentCheckTable.provider,
            ProviderEnrichmentCheckTable.type
        ) {
            it[entityId] = id
            it[ProviderEnrichmentCheckTable.provider] = provider
            it[ProviderEnrichmentCheckTable.type] = type
            it[lastCheck] = Clock.System.now().toEpochMilliseconds() + (1..5).random().days.inWholeMilliseconds
        }
    }

    suspend fun addProviderUrl(albumId: UUID, url: String) = dbQuery {
        val parser = ParserFactory.getParser(url)
        val parsed = parser?.parse(url)

        val provider = parser?.name ?: "unknown"
        val externalId = parsed?.first ?: url
        val type = parsed?.second?.value ?: Type.ALBUM.value
        val unchanged = AlbumProviderTable
            .select(AlbumProviderTable.albumId)
            .where { AlbumProviderTable.albumId eq albumId }
            .andWhere { AlbumProviderTable.provider eq provider }
            .andWhere { AlbumProviderTable.externalId eq externalId }
            .andWhere { AlbumProviderTable.type eq type }
            .andWhere { AlbumProviderTable.rawUrl eq url }
            .any()

        AlbumProviderTable.upsert(
            AlbumProviderTable.albumId,
            AlbumProviderTable.provider,
            AlbumProviderTable.externalId,
            onUpdateExclude = listOf(AlbumProviderTable.addedAt)
        ) {
            it[AlbumProviderTable.albumId] = albumId
            it[AlbumProviderTable.provider] = provider
            it[AlbumProviderTable.externalId] = externalId
            it[AlbumProviderTable.type] = type
            it[AlbumProviderTable.rawUrl] = url
        }
        if (!unchanged) entityEvents.updated(EntityType.ALBUM, listOf(albumId))
    }

    suspend fun enrichProviders(id: UUID, priority: HttpClientPriority = HttpClientPriority.NORMAL) {
        val album = byId(id) ?: return
        val urls = mutableSetOf<String>()
        var upc: String? = null

        album.musicBrainzId?.let { mbId ->
            cachedMusicBrainzService.getRelease(mbId, priority)?.let { release ->
                val mbUrls = (release.relations ?: emptyList())
                    .mapNotNull { it.url?.resource }
                urls.addAll(mbUrls)
                upc = release.barcode

                release.releaseGroup?.id?.let { rgId ->
                    val rg = cachedMusicBrainzService.getReleaseGroup(rgId, priority)
                    val rgUrls = (rg?.relations ?: emptyList())
                        .mapNotNull { it.url?.resource }
                    urls.addAll(rgUrls)
                }
            }
        }

        val seedUrls = dbQuery {
            AlbumProviderTable.selectAll()
                .where { AlbumProviderTable.albumId eq id }
                .mapNotNull { row ->
                    val provider = row[AlbumProviderTable.provider]
                    val externalId = row[AlbumProviderTable.externalId]
                    val typeValue = row[AlbumProviderTable.type]
                    val type = typeValue?.let { Type.fromValue(it) } ?: Type.ALBUM

                    ParserFactory.toUrl(provider, externalId, type) ?: row[AlbumProviderTable.rawUrl]
                }
        }.toMutableSet()

        seedUrls.addAll(urls)

        val resolvedLinks = linkResolverService.batchResolve(seedUrls, upc = upc, priority = priority)

        val allUrls = (urls + resolvedLinks).distinct()

        dbQuery {
            val before = entityStates(EntityType.ALBUM, listOf(id))
            allUrls.forEach { url ->
                val parser = ParserFactory.getParser(url)
                val parsed = parser?.parse(url)
                val provider = parser?.name ?: "unknown"
                val externalId = parsed?.first ?: url

                AlbumProviderTable.upsert(
                    AlbumProviderTable.albumId,
                    AlbumProviderTable.provider,
                    AlbumProviderTable.externalId,
                    onUpdateExclude = listOf(AlbumProviderTable.addedAt)
                ) {
                    it[AlbumProviderTable.albumId] = id
                    it[AlbumProviderTable.provider] = provider
                    it[AlbumProviderTable.externalId] = externalId
                    it[AlbumProviderTable.type] = parsed?.second?.value ?: Type.ALBUM.value
                    it[AlbumProviderTable.rawUrl] = url
                }
            }

            AlbumTable.update({ AlbumTable.id eq id }) {
                it[lastProviderEnrichment] = Clock.System.now().toEpochMilliseconds()
            }
            entityEvents.recordChanges(before)
        }
    }

    override suspend fun byMusicBrainzId(mbId: UUID): List<Album> = byMusicBrainzId(mbId, null)

    override suspend fun syncMusicBrainzForAlbums(albumIds: List<UUID>) {
        if (albumIds.isEmpty()) return
        albumIds.forEach { albumId ->
            val mbId = dbQuery {
                AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.musicBrainzId)
                    .where { AlbumMusicBrainzTable.albumId eq albumId }
                    .firstOrNull()?.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
            } ?: return@forEach
            syncAlbumSongsWithMusicBrainz(albumId, mbId)
        }
        scope.launch {
            libraryMergeService.mergeDuplicateAlbums()
        }
    }

    suspend fun byMusicBrainzId(mbId: UUID, userId: UUID? = null): List<Album> {
        return byMusicBrainzIdsMap(listOf(mbId), userId)[mbId] ?: emptyList()
    }

    suspend fun byMusicBrainzIds(mbIds: List<UUID>, userId: UUID? = null): List<Album?> {
        val map = byMusicBrainzIdsMap(mbIds, userId)
        return mbIds.map { map[it]?.firstOrNull() }
    }

    private suspend fun byMusicBrainzIdsMap(mbIds: List<UUID>, userId: UUID? = null): Map<UUID, List<Album>> {
        val results = mutableMapOf<UUID, List<Album>>()
        val remainingIds = mbIds.distinct().toMutableList()

        val directMatches = queryAlbums(0, Int.MAX_VALUE, userId = userId) {
            where { AlbumMusicBrainzTable.musicBrainzId inList remainingIds.map { EntityID(it, MBReleaseTable) } }
        }.data

        directMatches.forEach { album ->
            album.musicBrainzId?.let { mbId ->
                if (mbId in remainingIds) {
                    val list = results.getOrPut(mbId) { mutableListOf() } as MutableList<Album>
                    if (album !in list) list.add(album)
                }
            }
        }

        remainingIds.removeAll(results.keys)
        if (remainingIds.isEmpty()) return results

        val idToReleaseGroupId = mutableMapOf<UUID, UUID>()
        remainingIds.forEach { mbId ->
            val releaseGroupId = musicBrainzCacheService.getReleaseGroup(mbId)?.id
                ?: cachedMusicBrainzService.getRelease(mbId)?.releaseGroup?.id
                ?: cachedMusicBrainzService.getReleaseGroup(mbId)?.id

            if (releaseGroupId != null) {
                idToReleaseGroupId[mbId] = releaseGroupId
            }
        }

        if (idToReleaseGroupId.isEmpty()) return results

        val uniqueReleaseGroupIds = idToReleaseGroupId.values.distinct()
        val rgIdToAlbumIds = dbQuery {
            AlbumMusicBrainzTable
                .innerJoin(
                    MBReleaseTable,
                    onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                    otherColumn = { MBReleaseTable.id })
                .select(AlbumMusicBrainzTable.albumId, MBReleaseTable.releaseGroupId)
                .where {
                    MBReleaseTable.releaseGroupId inList uniqueReleaseGroupIds.map {
                        EntityID(
                            it,
                            MBReleaseGroupTable
                        )
                    }
                }
                .mapNotNull { row ->
                    val rgId = row[MBReleaseTable.releaseGroupId]?.value ?: return@mapNotNull null
                    val albumId = row[AlbumMusicBrainzTable.albumId].value
                    rgId to albumId
                }
                .groupBy({ it.first }, { it.second })
        }

        val allAlbumIdsToFetch = rgIdToAlbumIds.values.flatten().distinct()
        if (allAlbumIdsToFetch.isEmpty()) return results

        val albumsById = byIds(allAlbumIdsToFetch, userId).associateBy { it.id }

        idToReleaseGroupId.forEach { (mbId, rgId) ->
            val albumIds = rgIdToAlbumIds[rgId] ?: emptyList()
            val albums = albumIds.mapNotNull { albumsById[it] }.distinctBy { it.id }.sortedWith(
                compareByDescending<Album> { it.songCount }
                    .thenByDescending { it.coverId != null }
            )
            if (albums.isNotEmpty()) {
                results[mbId] = albums
            }
        }

        return results
    }

    suspend fun byOriginalIds(ids: Collection<PrefixedId>): List<Album> {
        if (ids.isEmpty()) return emptyList()

        val albumIdsFromProviders = ids.chunked(PROVIDER_LOOKUP_CHUNK_SIZE).flatMap { idChunk ->
            val parsedLookups = idChunk.mapNotNull { providerLookup(it, Type.ALBUM) }
            dbQuery {
                AlbumProviderTable
                    .select(AlbumProviderTable.albumId)
                    .where {
                        (AlbumProviderTable.type eq Type.ALBUM.value) and (
                            (AlbumProviderTable.rawUrl inList idChunk) or
                                (AlbumProviderTable.externalId inList idChunk) or
                                AlbumProviderTable.matchesAny(parsedLookups)
                            )
                    }
                    .map { it[AlbumProviderTable.albumId].value }
            }
        }

        return queryAlbums(0, Int.MAX_VALUE) {
            where {
                (AlbumTable.originalId inList ids) or
                    (AlbumTable.id inList albumIdsFromProviders)
            }
        }.data
    }

    suspend fun byOriginalUrls(urls: Collection<String>): Map<String, Album?> {
        if (urls.isEmpty()) return mutableMapOf()

        val distinctUrls = urls.distinct()
        val parsedLookups =
            distinctUrls.mapNotNull { url -> providerLookup(url, Type.ALBUM)?.let { url to it } }.toMap()

        val winners = dbQuery {
            val urlChunks = distinctUrls.chunked(PROVIDER_LOOKUP_CHUNK_SIZE)
            val providerRows = urlChunks.flatMap { urlChunk ->
                AlbumProviderTable
                    .select(
                        AlbumProviderTable.albumId,
                        AlbumProviderTable.provider,
                        AlbumProviderTable.externalId,
                        AlbumProviderTable.rawUrl
                    )
                    .where {
                        (AlbumProviderTable.type eq Type.ALBUM.value) and (
                            (AlbumProviderTable.rawUrl inList urlChunk) or
                                AlbumProviderTable.matchesAny(urlChunk.mapNotNull { parsedLookups[it] })
                            )
                    }
                    .map { row ->
                        ProviderUrlRow(
                            row[AlbumProviderTable.albumId].value,
                            row[AlbumProviderTable.rawUrl],
                            row[AlbumProviderTable.provider],
                            row[AlbumProviderTable.externalId]
                        )
                    }
            }
            val exactMatches = urlChunks.flatMap { urlChunk ->
                AlbumTable
                    .select(AlbumTable.id, AlbumTable.originalId)
                    .where { AlbumTable.originalId inList urlChunk }
                    .mapNotNull { row -> row[AlbumTable.originalId]?.let { row[AlbumTable.id].value to it } }
            }

            resolveUrlWinners(
                urls = distinctUrls,
                lookups = parsedLookups,
                exactMatches = exactMatches,
                providerRows = providerRows,
                order = uuidOrder,
            )
        }

        val albumsById =
            winners.values.filterNotNull().distinct().chunked(PROVIDER_LOOKUP_CHUNK_SIZE).flatMap { chunk ->
                queryAlbums(0, Int.MAX_VALUE) {
                    where { AlbumTable.id inList chunk }
                }.data
            }.associateBy { it.id }

        return winners.mapValuesTo(mutableMapOf()) { (_, id) -> id?.let { albumsById[it] } }
    }

    suspend fun byIds(@LogParam("size") ids: List<UUID>, userId: UUID? = null): List<Album> =
        queryAlbums(0, Int.MAX_VALUE, userId = userId) {
            where { AlbumTable.id inList ids }
        }.let { response ->
            val albumMap = response.data.associateBy { it.id }
            ids.mapNotNull { albumMap[it] }
        }

    suspend fun versions(id: UUID, userId: UUID? = null): List<Album> {
        val memberIds = dbQuery {
            val groupId = AlbumTable
                .select(AlbumTable.versionGroupId)
                .where { AlbumTable.id eq id }
                .singleOrNull()
                ?.get(AlbumTable.versionGroupId)
                ?: return@dbQuery emptyList()

            AlbumTable
                .select(AlbumTable.id)
                .where { AlbumTable.versionGroupId eq groupId }
                .map { it[AlbumTable.id].value }
        }
        if (memberIds.size < 2) return emptyList()

        return mainFirst(
            byIds(memberIds, userId),
            albumIdsWithExplicitSong(memberIds),
            dbQuery { albumIdsWithUnknownReleaseDate(memberIds) },
            explicit = true
        ).filter { it.id != id }
    }

    suspend fun byVersionGroup(versionGroupId: UUID, explicit: Boolean, userId: UUID? = null): List<Album> {
        val memberIds = dbQuery {
            AlbumTable
                .select(AlbumTable.id)
                .where { AlbumTable.versionGroupId eq versionGroupId }
                .map { it[AlbumTable.id].value }
        }
        if (memberIds.isEmpty()) return emptyList()

        return mainFirst(
            byIds(memberIds, userId),
            albumIdsWithExplicitSong(memberIds),
            dbQuery { albumIdsWithUnknownReleaseDate(memberIds) },
            explicit
        )
    }

    suspend fun rebuildVersionGroups(): Int {
        versionGroupPending.set(true)
        var updated = 0
        while (versionGroupPending.get() && versionGroupMutex.tryLock()) {
            try {
                while (versionGroupPending.getAndSet(false)) {
                    updated += rebuildVersionGroupsPass()
                }
            } finally {
                versionGroupMutex.unlock()
            }
        }
        return updated
    }

    private suspend fun rebuildVersionGroupsPass(): Int {
        val current = mutableMapOf<UUID, UUID?>()
        val editions = dbQuery {
            val artistIds = AlbumArtistTable
                .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId)
                .groupBy({ it[AlbumArtistTable.albumId].value }, { it[AlbumArtistTable.artistId].value })

            AlbumTable
                .leftJoin(AlbumMusicBrainzTable)
                .leftJoin(
                    MBReleaseTable,
                    onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                    otherColumn = { MBReleaseTable.id })
                .select(
                    AlbumTable.id,
                    AlbumTable.name,
                    AlbumTable.titleTags,
                    AlbumTable.cover,
                    AlbumTable.releaseDate,
                    AlbumTable.releaseDateEstimated,
                    AlbumTable.versionGroupId,
                    MBReleaseTable.releaseGroupId
                )
                .map { row ->
                    val albumId = row[AlbumTable.id].value
                    current[albumId] = row[AlbumTable.versionGroupId]?.value
                    AlbumVersionGroups.Edition(
                        id = albumId,
                        name = row[AlbumTable.name],
                        tags = row.albumTitleTags(),
                        artistIds = artistIds[albumId].orEmpty().toSet(),
                        coverId = row[AlbumTable.cover]?.value,
                        releaseGroupId = row.getOrNull(MBReleaseTable.releaseGroupId)?.value,
                        releaseDate = getDateFromISO(row[AlbumTable.releaseDate])
                            .takeUnless { row[AlbumTable.releaseDateEstimated] },
                        explicit = false
                    )
                }
        }

        val assignment = AlbumVersionGroups.assign(AlbumVersionGroups.groups(editions), current)
        val membersBefore = current.entries.groupBy({ it.value }, { it.key })
        fun editionsOfGroups(groupIds: Collection<UUID?>) =
            groupIds.filterNotNull().distinct().flatMap { membersBefore[it].orEmpty() }

        assignment.created.chunked(VERSION_GROUP_UPDATE_CHUNK_SIZE).forEach { chunk ->
            dbQuery {
                val groupIds = AlbumVersionGroupTable.batchInsert(chunk) { }.map { it[AlbumVersionGroupTable.id] }
                chunk.zip(groupIds).forEach { (albumIds, groupId) -> moveToVersionGroup(albumIds, groupId) }
                val albumIds = chunk.flatten()
                entityEvents.updated(
                    EntityType.ALBUM,
                    albumIds + editionsOfGroups(albumIds.map { current[it] })
                )
            }
        }

        assignment.moved.entries.chunked(VERSION_GROUP_UPDATE_CHUNK_SIZE).forEach { chunk ->
            dbQuery {
                for ((groupId, albumIds) in chunk) {
                    moveToVersionGroup(albumIds, EntityID(groupId, AlbumVersionGroupTable))
                }
                val albumIds = chunk.flatMap { it.value }
                entityEvents.updated(
                    EntityType.ALBUM,
                    albumIds + editionsOfGroups(chunk.map { it.key } + albumIds.map { current[it] })
                )
            }
        }

        val removed = dbQuery {
            AlbumVersionGroupTable.deleteWhere {
                AlbumVersionGroupTable.id notInSubQuery AlbumTable
                    .select(AlbumTable.versionGroupId)
                    .where { AlbumTable.versionGroupId.isNotNull() }
            }
        }

        val updated = assignment.created.sumOf { it.size } + assignment.moved.values.sumOf { it.size }
        if (updated > 0 || removed > 0) {
            logger.info("Rebuilt album version groups, updated $updated album(s), removed $removed empty group(s)")
        }
        return updated
    }

    private fun moveToVersionGroup(albumIds: List<UUID>, groupId: EntityID<UUID>) {
        albumIds.chunked(VERSION_GROUP_UPDATE_CHUNK_SIZE).forEach { ids ->
            AlbumTable.update({ AlbumTable.id inList ids }) {
                it[versionGroupId] = groupId
            }
        }
    }

    private suspend fun albumIdsWithExplicitSong(albumIds: List<UUID>): Set<UUID> = dbQuery {
        SongTable
            .select(SongTable.albumId)
            .where { SongTable.albumId inList albumIds }
            .andWhere { SongTable.explicit eq true }
            .withDistinct()
            .mapTo(mutableSetOf()) { it[SongTable.albumId].value }
    }

    private fun mainFirst(
        albums: List<Album>,
        explicitAlbumIds: Set<UUID>,
        unknownDateAlbumIds: Set<UUID>,
        explicit: Boolean
    ): List<Album> {
        val albumsById = albums.associateBy { it.id }
        val editions = albums.map { album ->
            AlbumVersionGroups.Edition(
                id = album.id,
                name = album.name,
                tags = album.tags,
                artistIds = album.artists.mapTo(mutableSetOf()) { it.id },
                coverId = album.coverId,
                releaseGroupId = null,
                releaseDate = album.releaseDate.takeUnless { album.id in unknownDateAlbumIds },
                explicit = album.id in explicitAlbumIds
            )
        }
        return AlbumVersionGroups.mainFirst(editions, explicit).map { albumsById.getValue(it.id) }
    }

    private suspend fun queryVersionGroups(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID?,
        newestFirst: Boolean,
        filter: Query.() -> Query
    ): PaginatedResponse<Album> {
        val groupCount = Count(versionGroupKey, distinct = true)
        val newestReleaseDate = Max(knownReleaseDate, AlbumTable.releaseDate.columnType)
        val paged = pageSize != Int.MAX_VALUE

        val (total, groupMemberIds) = dbQuery {
            val total = AlbumTable.select(groupCount).filter().first()[groupCount]
            if (total == 0L) return@dbQuery 0L to emptyList()

            val keyQuery = AlbumTable
                .select(versionGroupKey, newestReleaseDate)
                .filter()
                .groupBy(versionGroupKey)
            if (newestFirst) keyQuery.orderBy(newestReleaseDate, SortOrder.DESC_NULLS_LAST)
            keyQuery.orderBy(versionGroupKey, SortOrder.ASC)
            if (paged) keyQuery.limit(pageSize).offset((page * pageSize).toLong())
            val keys = keyQuery.map { it[versionGroupKey] }

            val memberQuery = AlbumTable.select(AlbumTable.id, versionGroupKey).filter()
            if (paged) memberQuery.andWhere { versionGroupKey inList keys }
            val membersByKey = memberQuery.groupBy({ it[versionGroupKey] }, { it[AlbumTable.id].value })

            total to keys.map { membersByKey[it].orEmpty() }
        }

        val memberIds = groupMemberIds.flatten()
        val albumsById = if (memberIds.isEmpty()) emptyMap() else byIds(memberIds, userId).associateBy { it.id }
        val explicitAlbumIds = if (memberIds.isEmpty()) emptySet() else albumIdsWithExplicitSong(memberIds)
        val unknownDateAlbumIds = dbQuery { albumIdsWithUnknownReleaseDate(memberIds) }

        val data = groupMemberIds.mapNotNull { ids ->
            val ordered = mainFirst(ids.mapNotNull { albumsById[it] }, explicitAlbumIds, unknownDateAlbumIds, explicit)
            ordered.firstOrNull()?.copy(versions = ordered.drop(1))
        }

        return PaginatedResponse(
            data = data,
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
            hasNextPage = (page + 1).toLong() * pageSize < total,
        )
    }

    suspend fun byName(
        page: Int,
        pageSize: Int,
        name: String,
        userId: UUID? = null
    ): PaginatedResponse<Album> = queryAlbums(page, pageSize, userId = userId) {
        where { AlbumTable.name eq name }
    }

    suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        singles: Boolean,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        queryAlbums(page, pageSize, userId = userId) {
            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.columns)
                .where { AlbumArtistTable.artistId eq artistId }
                .map { it[AlbumArtistTable.albumId].value }

            if (!singles) where { AlbumTable.songCount greater 1 }
            else where { AlbumTable.songCount eq 1 }
            andWhere { AlbumTable.id inList albumIds }
            orderBy(knownReleaseDate, SortOrder.DESC_NULLS_LAST)
        }

    suspend fun byArtistGrouped(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        singles: Boolean,
        explicit: Boolean,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        queryVersionGroups(page, pageSize, explicit, userId, newestFirst = true) {
            where { if (singles) AlbumTable.songCount eq 1 else AlbumTable.songCount greater 1 }
                .andWhere {
                    AlbumTable.id inSubQuery AlbumArtistTable
                        .select(AlbumArtistTable.albumId)
                        .where { AlbumArtistTable.artistId eq artistId }
                }
        }

    suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        rankedAlbumSearch(page, pageSize, query, userId) {
            andWhere { AlbumTable.songCount greater 1 }
        }

    suspend fun rankedSearchInCollection(
        collectionId: UUID,
        page: Int,
        pageSize: Int,
        query: String,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        rankedAlbumSearch(page, pageSize, query, userId) {
            andWhere {
                AlbumTable.id inSubQuery CollectionAlbumTable
                    .select(CollectionAlbumTable.albumId)
                    .where { CollectionAlbumTable.collectionId eq collectionId }
            }
        }

    suspend fun rankedSearchInRadioChannel(
        channelId: UUID,
        page: Int,
        pageSize: Int,
        query: String,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        rankedAlbumSearch(page, pageSize, query, userId) {
            andWhere {
                AlbumTable.id inSubQuery RadioChannelAlbumTable
                    .select(RadioChannelAlbumTable.albumId)
                    .where { RadioChannelAlbumTable.channelId eq channelId }
            }
        }

    private suspend fun rankedAlbumSearch(
        page: Int,
        pageSize: Int,
        query: String,
        userId: UUID? = null,
        scope: Query.() -> Query = { this }
    ): PaginatedResponse<Album> =
        queryAlbumsRanked(page, pageSize, userId = userId, columnSet = {
            leftJoin(
                artistGroupJoinAlias,
                onColumn = { ArtistTable.id },
                otherColumn = { artistGroupJoinAlias[ArtistMemberTable.artistId] })
                .leftJoin(
                    artistGroupAlias,
                    onColumn = { artistGroupJoinAlias[ArtistMemberTable.groupId] },
                    otherColumn = { artistGroupAlias[ArtistTable.id] })
                .leftJoin(
                    artistMemberJoinAlias,
                    onColumn = { ArtistTable.id },
                    otherColumn = { artistMemberJoinAlias[ArtistMemberTable.groupId] })
                .leftJoin(
                    artistMemberAlias,
                    onColumn = { artistMemberJoinAlias[ArtistMemberTable.artistId] },
                    otherColumn = { artistMemberAlias[ArtistTable.id] })
                .withMBReleaseSearch()
                .withMBArtistSearch()
        }) {
            rankedSearchQuery(
                redisSearchService,
                query,
                listOf(10, 5, 5, 3, 3, 5, 3, 5, 5, 3, 8),
                listOf(
                    AlbumTable.name,
                    ArtistTable.name,
                    ArtistAliasTable.name,
                    artistGroupAlias[ArtistTable.name],
                    artistMemberAlias[ArtistTable.name]
                ) + mbReleaseSearchColumns + mbArtistSearchColumns + AlbumTable.titleTags,
                AlbumTable.id,
                searchVectorColumn = if (searchIndexWorker != null) AlbumTable.searchVector else null
            ).let { it.copy(query = it.query.scope()) }
        }

    suspend fun allAlbums(
        page: Int,
        pageSize: Int,
        userId: UUID? = null
    ): PaginatedResponse<Album> = queryAlbums(page, pageSize, userId = userId)

    suspend fun allAlbumsGrouped(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        queryVersionGroups(page, pageSize, explicit, userId, newestFirst = false) { this }

    suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int,
        userId: UUID? = null
    ): PaginatedResponse<Album> {
        val match = ColorMatch(color, range)
        return queryAlbums(page, pageSize, userId = userId, columnSet = { match.join(this, AlbumTable.cover) }) {
            match.filterAndOrder(this)
        }
    }

    suspend fun updateAlbum(album: Album, userId: UUID? = null): Album? {
        upsertAlbum(album, triggerSync = true)
        return byId(album.id, userId)
    }

    fun allAlbumIds(): Flow<UUID> = flow {
        AlbumTable
            .select(AlbumTable.id)
            .fetchBatchedResultsByIdKeyset(AlbumTable.id, 1000) { batch ->
                for (row in batch) {
                    emit(row[AlbumTable.id].value)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun allAlbumsFlow(): Flow<Album> = allAlbumIds().chunked(100).flatMapConcat { ids ->
        byIds(ids).asFlow()
    }

    fun albumIdsWithoutMusicBrainzId(): Flow<UUID> = flow {
        val oneWeekAgo = Clock.System.now() - 7.days

        AlbumTable
            .leftJoin(AlbumMusicBrainzTable)
            .select(AlbumTable.id)
            .where {
                AlbumMusicBrainzTable.albumId.isNull() or
                    (AlbumMusicBrainzTable.lastCheck eq 0L) or
                    (AlbumMusicBrainzTable.musicBrainzId.isNull() and (AlbumMusicBrainzTable.lastCheck less oneWeekAgo.toEpochMilliseconds()))
            }
            .fetchBatchedResultsByIdKeyset(AlbumTable.id, 1000) { batch ->
                for (row in batch) {
                    emit(row[AlbumTable.id].value)
                }
            }
    }

    suspend fun deleteAlbums(ids: List<UUID>): Boolean {
        val requested = ids.toSet()
        val deletedAlbums = dbQuery {
            val songIds = requested.chunked(5000).flatMap { chunk ->
                SongTable
                    .select(SongTable.id)
                    .where { SongTable.albumId inList chunk }
                    .map { it[SongTable.id].value }
            }
            libraryFileDeleter.deleteSongRows(songIds).deletedAlbumIds.count { it in requested }
        }
        return deletedAlbums == requested.size
    }

    private suspend fun querySingle(
        userId: UUID? = null,
        query: Query.() -> Query = { this }
    ) = queryAlbums(0, Int.MAX_VALUE, userId = userId, query = query).data.singleOrNull()

    private suspend fun queryAlbums(
        page: Int,
        pageSize: Int,
        userId: UUID? = null,
        columnSet: ColumnSet.() -> ColumnSet = { this },
        query: Query.() -> Query = { this }
    ) = queryAlbumsRanked(page, pageSize, userId, columnSet) { RankedSearch(query()) }

    private suspend fun queryAlbumsRanked(
        page: Int,
        pageSize: Int,
        userId: UUID? = null,
        columnSet: ColumnSet.() -> ColumnSet = { this },
        search: Query.() -> RankedSearch
    ) = dbQuery {
        val ranked = AlbumTable
            .leftJoin(
                AlbumArtistTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumArtistTable.albumId })
            .leftJoin(
                ArtistTable,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .followedArtist(userId)
            .leftJoin(
                ArtistMusicBrainzTable,
                onColumn = { ArtistTable.id },
                otherColumn = { ArtistMusicBrainzTable.artistId }
            )
            .leftJoin(ArtistAliasTable)
            .leftJoin(AlbumMusicBrainzTable)
            .leftJoin(AlbumGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(ImageTable, onColumn = { AlbumTable.cover }, otherColumn = { ImageTable.id })
            .leftJoin(
                albumAnimatedImageAlias,
                onColumn = { AlbumTable.animatedCover },
                otherColumn = { albumAnimatedImageAlias[AnimatedImageTable.id] })
            .leftJoin(
                albumAnimatedFrameAlias,
                onColumn = { albumAnimatedImageAlias[AnimatedImageTable.imageId] },
                otherColumn = { albumAnimatedFrameAlias[ImageTable.id] })
            .columnSet()
            .selectAll()
            .search()
        val baseSelect = ranked.query

        val countExpression = AlbumTable.id.countDistinct()
        val countQuery = Query(Slice(baseSelect.set.source, listOf(countExpression)), baseSelect.where)
        baseSelect.having?.let { h -> countQuery.having { h } }
        val total = ranked.redisTotal ?: countQuery.first()[countExpression]

        if (total == 0L) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = 0,
            page = page,
            pageSize = pageSize,
        )

        val sortAliases = baseSelect.orderByExpressions.mapIndexed { index, (expr, _) ->
            expr.alias("sort_$index")
        }
        val idQuery = Query(Slice(baseSelect.set.source, listOf(AlbumTable.id) + sortAliases), baseSelect.where)
        baseSelect.having?.let { h -> idQuery.having { h } }
        baseSelect.orderByExpressions.forEachIndexed { index, (_, order) ->
            idQuery.orderBy(sortAliases[index], order)
        }
        idQuery.withDistinct(true)

        if (pageSize != Int.MAX_VALUE) {
            idQuery.limit(pageSize)
            idQuery.offset((page * pageSize).toLong())
        }

        val ids = idQuery.map { it[AlbumTable.id].value }.distinct()

        if (ids.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
        )

        val rows = AlbumTable
            .leftJoin(
                AlbumArtistTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumArtistTable.albumId })
            .leftJoin(
                ArtistTable,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .followedArtist(userId)
            .leftJoin(
                ArtistMusicBrainzTable,
                onColumn = { ArtistTable.id },
                otherColumn = { ArtistMusicBrainzTable.artistId }
            )
            .leftJoin(ArtistAliasTable)
            .leftJoin(
                albumArtistCreditedAlias,
                onColumn = { AlbumArtistTable.creditedAliasId },
                otherColumn = { albumArtistCreditedAlias[ArtistAliasTable.id] }
            )
            .leftJoin(AlbumMusicBrainzTable)
            .leftJoin(AlbumGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(ImageTable, onColumn = { AlbumTable.cover }, otherColumn = { ImageTable.id })
            .leftJoin(
                albumAnimatedImageAlias,
                onColumn = { AlbumTable.animatedCover },
                otherColumn = { albumAnimatedImageAlias[AnimatedImageTable.id] })
            .leftJoin(
                albumAnimatedFrameAlias,
                onColumn = { albumAnimatedImageAlias[AnimatedImageTable.imageId] },
                otherColumn = { albumAnimatedFrameAlias[ImageTable.id] })
            .columnSet()
            .selectAll()
            .where { AlbumTable.id inList ids }
            .toList()

        val statsByAlbumId = if (ids.isNotEmpty()) {
            calculateAlbumStats(ids)
        } else {
            emptyMap()
        }

        idOrderedPage(ids, mapEagerly(rows, statsByAlbumId), total, page, pageSize) { it.id }
    }

    private fun mapEagerly(
        rows: List<ResultRow>,
        albumStats: Map<UUID, Pair<Long, Long>>
    ): List<Album> {
        val albumMap = mutableMapOf<UUID, Album>()
        val albumArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()
        val albumGenresMap = mutableMapOf<UUID, MutableList<Genre>>()
        val albumArtistPositions = mutableMapOf<Pair<UUID, UUID>, Int>()

        for (row in rows) {
            val albumId = row[AlbumTable.id].value

            albumMap.getOrPut(albumId) {
                val genres = rows.filter { it[AlbumTable.id].value == albumId }
                    .mapNotNull { r ->
                        val gid = r.getOrNull(GenreTable.id)?.value ?: return@mapNotNull null
                        val gname = r.getOrNull(GenreTable.name) ?: return@mapNotNull null
                        Genre(gid, gname)
                    }.distinctBy { it.id }
                mapAlbum(
                    row, genres,
                    animatedCoverImageIdColumn = albumAnimatedImageAlias[AnimatedImageTable.imageId],
                    animatedCoverBlurHashColumn = albumAnimatedFrameAlias[ImageTable.blurHash],
                )
            }

            if (row.getOrNull(ArtistTable.id) != null) {
                val artist = mapArtist(row, followedTable = followedArtistAlias)
                    .copy(
                        creditedName = row.getOrNull(albumArtistCreditedAlias[ArtistAliasTable.name]),
                        joinPhrase = row.getOrNull(AlbumArtistTable.joinPhrase),
                    )
                albumArtistPositions[albumId to artist.id] = row[AlbumArtistTable.position]
                if (artist !in albumArtistsMap.getOrDefault(albumId, emptyList())) {
                    albumArtistsMap.getOrPut(albumId) { mutableListOf() }.add(artist)
                }
            }

            if (row.getOrNull(GenreTable.id) != null) {
                val genre = Genre(row[GenreTable.id].value, row[GenreTable.name])
                if (genre !in albumGenresMap.getOrDefault(albumId, emptyList())) {
                    albumGenresMap.getOrPut(albumId) { mutableListOf() }.add(genre)
                }
            }
        }

        return albumMap.values.map { album ->
            val albumArtists = albumArtistsMap[album.id]?.distinctBy { it.id }
                ?.inCreditOrder { albumArtistPositions[album.id to it.id] ?: 0 }?.map { it.toCredit() } ?: listOf()
            val albumGenres = albumGenresMap[album.id]?.distinctBy { it.id }?.inNameOrder() ?: listOf()

            album.copy(
                artists = albumArtists,
                genres = albumGenres,
                totalDuration = albumStats[album.id]?.first ?: -1L,
                totalSize = albumStats[album.id]?.second ?: -1L
            )
        }
    }

    data class BulkCreateAlbumResult(
        val albumToIds: Map<InsertableAlbum, UUID>,
        val newlyCreated: Set<InsertableAlbum>
    )

    override suspend fun createBatch(albums: List<InsertableAlbum>): Map<UUID, Album> {
        val result = getOrBulkCreateWithResult(albums)
        return byIds(result.albumToIds.values.toList()).associateBy { it.id }
    }

    suspend fun getOrBulkCreateWithResult(
        inputAlbums: List<InsertableAlbum>,
        songReleaseDates: Map<InsertableAlbum, PlatformLocalDate> = emptyMap()
    ): BulkCreateAlbumResult {
        if (inputAlbums.isEmpty()) return BulkCreateAlbumResult(emptyMap(), emptySet())

        val albums = inputAlbums.map { it.withSplitTitleTags() }

        val artistService = get<ArtistService>()
        val imageService = get<ImageService>()

        val uniqueCoverHashed = albums.distinctBy { it.coverHash }.mapNotNull { it.coverHash }
        val albumsByIdentity = albums.groupBy { identityKey(it) }.mapValues { (_, group) ->
            group.maxByOrNull {
                (if (it.releaseDate != null) 1 else 0) +
                    (if (it.songCount > 0) 1 else 0) +
                    (if (it.coverHash != null) 1 else 0)
            }!!
        }
        val uniqueAlbumMetadata = albumsByIdentity.values.toList()
        val uniqueAlbumNames = uniqueAlbumMetadata.map { it.name }
        val uniqueSongCounts = uniqueAlbumMetadata.map { it.songCount }
        val uniqueReleaseDates = uniqueAlbumMetadata.mapNotNull { getISOFromDate(it.releaseDate) }
        val anyUndatedInput = uniqueAlbumMetadata.any { it.releaseDate == null }
        val uniqueOriginalIds = uniqueAlbumMetadata.map { it.originalId }
        val uniqueBarcodes = uniqueAlbumMetadata.flatMap { Barcodes.variants(it.barcode) }.distinct()
        val uniqueMbIds = uniqueAlbumMetadata.mapNotNull { it.musicBrainzId }.distinct()
        val allRequiredArtistNames = albums.flatMap { it.artists }.distinct()

        val artistIdMap: Map<String, List<UUID>> =
            artistService.getOrBulkCreate(allRequiredArtistNames)
        val imageMap: Map<String, UUID> = imageService.getCoverHashes(uniqueCoverHashed)

        val parsedLookupsForMatching = uniqueOriginalIds.filterNotNull()
            .mapNotNull { id -> providerLookup(id)?.let { id to it } }
            .toMap()

        val albumIdsFromProviders = uniqueOriginalIds.filterNotNull()
            .chunked(PROVIDER_LOOKUP_CHUNK_SIZE)
            .flatMap { idChunk ->
                dbQuery {
                    AlbumProviderTable.select(AlbumProviderTable.albumId).where {
                        (AlbumProviderTable.rawUrl inList idChunk) or
                            AlbumProviderTable.matchesAny(idChunk.mapNotNull { parsedLookupsForMatching[it] })
                    }.map { it[AlbumProviderTable.albumId].value }
                }
            }

        val potentialAlbumRows = queryAlbums(0, Int.MAX_VALUE) {
            where { AlbumTable.name inList uniqueAlbumNames }
            if (!anyUndatedInput) andWhere { (AlbumTable.releaseDate inList uniqueReleaseDates) or unknownReleaseDate }
            andWhere { AlbumTable.songCount inList uniqueSongCounts }
            orWhere { AlbumTable.originalId inList uniqueOriginalIds.filterNotNull() }
            orWhere { if (uniqueBarcodes.isNotEmpty()) AlbumTable.barcode inList uniqueBarcodes else Op.FALSE }
            orWhere {
                if (uniqueMbIds.isNotEmpty()) AlbumMusicBrainzTable.musicBrainzId inList uniqueMbIds.map {
                    EntityID(
                        it,
                        MBReleaseTable
                    )
                } else Op.FALSE
            }
            orWhere { AlbumTable.id inList albumIdsFromProviders }
        }.data

        val potentialAlbumIds = potentialAlbumRows.map { it.id }.toSet()
        val unknownDateAlbumIds = dbQuery { albumIdsWithUnknownReleaseDate(potentialAlbumIds) }

        val albumArtistLinks = dbQuery {
            AlbumArtistTable
                .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId)
                .where { AlbumArtistTable.albumId inList potentialAlbumIds }
                .toList()
        }

        val providersByPotentialAlbumId = dbQuery {
            AlbumProviderTable
                .select(
                    AlbumProviderTable.albumId,
                    AlbumProviderTable.provider,
                    AlbumProviderTable.externalId,
                    AlbumProviderTable.rawUrl
                )
                .where { AlbumProviderTable.albumId inList potentialAlbumIds }
                .toList()
        }.groupBy { it[AlbumProviderTable.albumId].value }

        val artistsByPotentialAlbumId = albumArtistLinks
            .groupBy(
                { it[AlbumArtistTable.albumId].value },
                { it[AlbumArtistTable.artistId].value })
            .mapValues { (_, artistIds) -> artistIds.toSet() }

        fun getIdentityKey(
            originalId: String?,
            name: String,
            tags: List<TitleTag>,
            artists: List<String>,
            releaseDate: PlatformLocalDate?
        ): Any {
            return originalId ?: listOf(name, tags, artists.sorted(), getISOFromDate(releaseDate))
        }

        val finalMatchMap = mutableMapOf<Any, UUID>()
        val barcodesToFill = mutableMapOf<UUID, String>()
        val releaseDatesToFill = mutableMapOf<UUID, PlatformLocalDate>()

        for (row in potentialAlbumRows) {
            val albumId = row.id
            val albumArtists = artistsByPotentialAlbumId[albumId] ?: emptySet()
            val albumProviders = providersByPotentialAlbumId[albumId] ?: emptyList()

            val inputAlbum = uniqueAlbumMetadata.firstOrNull {
                val inputMbId = it.musicBrainzId
                if (inputMbId != null && row.musicBrainzId == inputMbId) return@firstOrNull true

                if (Barcodes.same(row.barcode, it.barcode)) return@firstOrNull true

                if (it.originalId != null) {
                    if (row.originalId == it.originalId) return@firstOrNull true
                    val lookup = parsedLookupsForMatching[it.originalId]
                    if (lookup != null) {
                        albumProviders.any { p ->
                            p[AlbumProviderTable.provider] == lookup.provider && p[AlbumProviderTable.externalId] == lookup.externalId
                        }
                    } else albumProviders.any { p -> p[AlbumProviderTable.rawUrl] == it.originalId }
                } else if (row.originalId == null) {
                    it.name == row.name &&
                        it.tags == row.tags &&
                        getISOFromDate(it.releaseDate) == getISOFromDate(row.releaseDate) &&
                        it.songCount == row.songCount
                } else {
                    false
                }
            }

            if (inputAlbum != null) {
                val requiredArtistIdsForInput =
                    inputAlbum.artists.flatMap { artistIdMap[it] ?: emptyList() }.toSet()

                if (inputAlbum.originalId != null || albumArtists == requiredArtistIdsForInput) {
                    finalMatchMap[getIdentityKey(
                        inputAlbum.originalId,
                        inputAlbum.name,
                        inputAlbum.tags,
                        inputAlbum.artists,
                        inputAlbum.releaseDate
                    )] = albumId

                    val inputBarcode = inputAlbum.barcode
                    if (inputBarcode != null && Barcodes.normalize(row.barcode) == null && Barcodes.normalize(inputBarcode) != null) {
                        barcodesToFill[albumId] = inputBarcode
                    }

                    val inputReleaseDate = inputAlbum.releaseDate
                    if (inputReleaseDate != null && albumId in unknownDateAlbumIds) {
                        releaseDatesToFill.putIfAbsent(albumId, inputReleaseDate)
                    }
                }
            }
        }

        for (row in potentialAlbumRows) {
            if (row.originalId != null) continue
            val albumId = row.id
            val albumArtists = artistsByPotentialAlbumId[albumId] ?: emptySet()

            for (inputAlbum in uniqueAlbumMetadata) {
                if (inputAlbum.originalId != null) continue
                val inputReleaseDate = inputAlbum.releaseDate
                if (inputReleaseDate != null && (albumId !in unknownDateAlbumIds || albumId in releaseDatesToFill)) continue
                if (inputAlbum.name != row.name || inputAlbum.tags != row.tags || inputAlbum.songCount != row.songCount) continue
                if (albumArtists != inputAlbum.artists.flatMap { artistIdMap[it] ?: emptyList() }.toSet()) continue

                val key = getIdentityKey(null, inputAlbum.name, inputAlbum.tags, inputAlbum.artists, inputReleaseDate)
                if (finalMatchMap.containsKey(key)) continue

                finalMatchMap[key] = albumId
                if (inputReleaseDate != null) releaseDatesToFill[albumId] = inputReleaseDate
            }
        }

        if (releaseDatesToFill.isNotEmpty()) {
            dbQuery {
                fillUnknownReleaseDatesTx(releaseDatesToFill)
            }
        }

        if (barcodesToFill.isNotEmpty()) {
            dbQuery {
                for ((albumId, inputBarcode) in barcodesToFill) {
                    AlbumTable.update({ AlbumTable.id eq albumId }) {
                        it[barcode] = inputBarcode
                    }
                }
                entityEvents.updated(EntityType.ALBUM, barcodesToFill.keys)
            }
        }

        val existingAlbumsWithMb = uniqueAlbumMetadata.filter { album ->
            if (album.musicBrainzId == null) return@filter false
            val key = getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
            finalMatchMap.containsKey(key)
        }

        if (existingAlbumsWithMb.isNotEmpty()) {
            val existingIds = existingAlbumsWithMb.mapNotNull { album ->
                val key = getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
                finalMatchMap[key]
            }
            val alreadyHasMbIds = dbQuery {
                AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.albumId)
                    .where {
                        (AlbumMusicBrainzTable.albumId inList existingIds) and
                            AlbumMusicBrainzTable.musicBrainzId.isNotNull()
                    }
                    .map { it[AlbumMusicBrainzTable.albumId].value }
                    .toSet()
            }
            existingAlbumsWithMb.forEach { album ->
                val key = getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
                val albumId = finalMatchMap[key] ?: return@forEach
                if (albumId !in alreadyHasMbIds) {
                    setMusicBrainzId(albumId, album.musicBrainzId, triggerSync = false, triggerMerge = false)
                }
            }
        }

        val newAlbumsToInsert = uniqueAlbumMetadata.filter { album ->
            val key = getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
            !finalMatchMap.containsKey(key)
        }

        val songReleaseDatesByIdentity = inputAlbums.zip(albums)
            .mapNotNull { (inputAlbum, album) ->
                songReleaseDates[inputAlbum]?.let { songDate ->
                    getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate) to songDate
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) -> dates.min() }

        val newRows = if (newAlbumsToInsert.isNotEmpty()) {
            dbQuery {
                val cachedReleaseDates = newAlbumsToInsert
                    .mapNotNull { it.musicBrainzId }
                    .distinct()
                    .chunked(RELEASE_DATE_CHUNK_SIZE)
                    .flatMap { chunk ->
                        MBReleaseTable
                            .select(MBReleaseTable.id, MBReleaseTable.date)
                            .where { MBReleaseTable.id inList chunk }
                            .mapNotNull { row ->
                                parsePartialDate(row[MBReleaseTable.date])?.let { row[MBReleaseTable.id].value to it }
                            }
                    }
                    .toMap()
                val knownReleaseDates = newAlbumsToInsert.map { album ->
                    album.releaseDate
                        ?: album.musicBrainzId?.let { cachedReleaseDates[it] }
                        ?: songReleaseDatesByIdentity[
                            getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
                        ]
                }
                val addedOn = PlatformLocalDate.now()
                val groupIds = AlbumVersionGroupTable
                    .batchInsert(newAlbumsToInsert) { }
                    .map { it[AlbumVersionGroupTable.id] }
                AlbumTable.batchInsert(newAlbumsToInsert.indices.toList()) { index ->
                    val album = newAlbumsToInsert[index]
                    val knownReleaseDate = knownReleaseDates[index]
                    this[AlbumTable.versionGroupId] = groupIds[index]
                    this[AlbumTable.name] = album.name
                    this[AlbumTable.titleTags] = encodeTitleTags(album.tags)
                    this[AlbumTable.releaseDate] = getISOFromDate(knownReleaseDate ?: addedOn)
                    this[AlbumTable.releaseDateEstimated] = knownReleaseDate == null
                    this[AlbumTable.songCount] = album.songCount
                    this[AlbumTable.cover] = imageMap[album.coverHash]
                    this[AlbumTable.originalId] = album.originalId
                    this[AlbumTable.barcode] = album.barcode
                }.also { rows -> entityEvents.created(EntityType.ALBUM, rows.map { it[AlbumTable.id].value }) }
            }
        } else {
            emptyList()
        }

        if (newRows.isNotEmpty()) {
            val taggedRows = newRows
                .map { it[AlbumTable.id].value to it.albumTitleTags() }
                .filter { it.second.isNotEmpty() }
            if (taggedRows.isNotEmpty()) {
                dbQuery {
                    syncAlbumTitleTags(taggedRows)
                    entityEvents.updated(EntityType.ALBUM, taggedRows.map { it.first })
                }
            }

            val mbEntries = newAlbumsToInsert.zip(newRows).mapNotNull { (album, row) ->
                album.musicBrainzId?.let { mbId ->
                    row[AlbumTable.id].value to mbId
                }
            }

            if (mbEntries.isNotEmpty()) {
                mbEntries.forEach { (albumId, mbId) ->
                    setMusicBrainzId(albumId, mbId, triggerSync = false, triggerMerge = false)
                }
            }

            val providerEntries = mutableListOf<Pair<Triple<UUID, String, Pair<String, String>>, String>>()
            for (row in newRows) {
                val albumId = row[AlbumTable.id].value
                val originalId = row[AlbumTable.originalId] ?: continue
                val parser = ParserFactory.getParser(originalId)
                val parsed = parser?.parse(originalId)
                val provider = parser?.name ?: "unknown"
                val externalId = parsed?.first
                    ?: (if (originalId.contains(":")) originalId.substringAfter(":") else originalId)

                providerEntries.add(
                    Triple(
                        albumId,
                        provider,
                        externalId to (parsed?.second?.value ?: Type.ALBUM.value)
                    ) to originalId
                )
            }

            if (providerEntries.isNotEmpty()) {
                dbQuery {
                    AlbumProviderTable.batchInsert(providerEntries) { (meta, originalId) ->
                        this[AlbumProviderTable.albumId] = meta.first
                        this[AlbumProviderTable.provider] = meta.second
                        this[AlbumProviderTable.externalId] = meta.third.first
                        this[AlbumProviderTable.type] = meta.third.second
                        this[AlbumProviderTable.rawUrl] = originalId
                    }
                    entityEvents.updated(EntityType.ALBUM, providerEntries.map { it.first.first })
                }
            }
        }

        val newAlbumIdLookupMap = newAlbumsToInsert.zip(newRows).associate { (album, row) ->
            getIdentityKey(
                album.originalId,
                album.name,
                album.tags,
                album.artists,
                album.releaseDate
            ) to row[AlbumTable.id].value
        }

        val newAlbumArtistLinks = newAlbumsToInsert.flatMap { album ->
            val key = getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
            val albumId = newAlbumIdLookupMap[key]
            if (albumId != null) {
                album.artists
                    .flatMap { artistName -> artistIdMap[artistName] ?: emptyList() }
                    .mapIndexed { index, artistId -> Triple(albumId, artistId, index) }
            } else {
                emptyList()
            }
        }

        if (newAlbumArtistLinks.isNotEmpty()) {
            dbQuery {
                AlbumArtistTable.batchInsert(newAlbumArtistLinks) { (albumId, artistId, index) ->
                    this[AlbumArtistTable.albumId] = albumId
                    this[AlbumArtistTable.artistId] = artistId
                    this[AlbumArtistTable.position] = index
                }
                applyCachedAlbumCreditOrder(newAlbumArtistLinks.map { it.first })
                entityEvents.updated(
                    EntityType.ALBUM,
                    newAlbumArtistLinks.map { it.first },
                    containersChanged = true
                )
            }
        }

        val matchedSongReleaseDates = finalMatchMap.entries
            .filter { it.value in unknownDateAlbumIds }
            .mapNotNull { (key, albumId) -> songReleaseDatesByIdentity[key]?.let { albumId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) -> dates.min() }
        if (matchedSongReleaseDates.isNotEmpty()) {
            dbQuery {
                fillUnknownReleaseDatesTx(matchedSongReleaseDates)
            }
        }

        val finalCombinedIdMap = finalMatchMap + newAlbumIdLookupMap

        val resultMap = inputAlbums.zip(albums).associate { (inputAlbum, album) ->
            val key = getIdentityKey(album.originalId, album.name, album.tags, album.artists, album.releaseDate)
            inputAlbum to finalCombinedIdMap[key]
        }.filterValueNotNull()

        val insertedAlbums = newAlbumsToInsert.toSet()
        val newlyCreated = inputAlbums.zip(albums).filter { it.second in insertedAlbums }.map { it.first }.toSet()

        return BulkCreateAlbumResult(resultMap, newlyCreated)
    }

    suspend fun getOrBulkCreate(
        albums: List<InsertableAlbum>,
        songReleaseDates: Map<InsertableAlbum, PlatformLocalDate> = emptyMap()
    ): Map<InsertableAlbum, UUID> =
        getOrBulkCreateWithResult(albums, songReleaseDates).albumToIds

    suspend fun deleteEmptyAlbums(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int {
        val deleted = dbQuery {
            val emptyAlbums = AlbumTable
                .select(AlbumTable.id)
                .where {
                    notExists(
                        SongTable.select(SongTable.id).where {
                            SongTable.albumId eq AlbumTable.id
                        }
                    )
                }
                .map { it[AlbumTable.id].value }

            onProgress(0.0, "Found ${emptyAlbums.size} empty albums")

            entityEvents.deleting(EntityType.ALBUM, emptyAlbums)

            val chunks = emptyAlbums.chunked(5000)
            chunks.forEachIndexed { index, batch ->
                val progress = (index.toDouble() / chunks.size) * 100.0
                onProgress(
                    progress,
                    "Deleting batch ${index + 1}/${chunks.size} (${batch.size} albums)"
                )

                AlbumTable.deleteWhere { AlbumTable.id inList batch }
                AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId inList batch }
            }

            onProgress(100.0, "Deleted ${emptyAlbums.size} albums")
            logger.info("Deleted ${emptyAlbums.size} empty albums")
            emptyAlbums.size
        }
        return deleted
    }

    suspend fun upsertAlbum(inputAlbum: Album, triggerSync: Boolean = false, triggerMerge: Boolean = true) {
        val album = inputAlbum.withSplitTitleTags()
        val currentMbId = dbQuery {
            AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.albumId eq album.id }
                .firstOrNull()?.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
        }

        dbQuery {
            val before = entityStates(EntityType.ALBUM, listOf(album.id))
            val artistsBefore = entityStates(EntityType.ARTIST, album.artists.map { it.id })
            val stored = AlbumTable
                .select(AlbumTable.releaseDate, AlbumTable.releaseDateEstimated)
                .where { AlbumTable.id eq album.id }
                .singleOrNull()
            val newGroupId = if (stored == null) AlbumVersionGroupTable.insertAndGetId { } else null

            val incomingReleaseDate = getISOFromDate(album.releaseDate)
            val storedReleaseDate = stored?.get(AlbumTable.releaseDate)
            val cachedReleaseDate = if (incomingReleaseDate == null && storedReleaseDate == null) {
                album.musicBrainzId
                    ?.let { mbId ->
                        MBReleaseTable
                            .select(MBReleaseTable.date)
                            .where { MBReleaseTable.id eq mbId }
                            .singleOrNull()
                            ?.get(MBReleaseTable.date)
                    }
                    ?.let { getISOFromDate(parsePartialDate(it)) }
            } else null
            val knownReleaseDate = incomingReleaseDate ?: storedReleaseDate ?: cachedReleaseDate
            val releaseDateIsEstimated = when {
                knownReleaseDate == null -> true
                stored == null -> false
                else -> stored[AlbumTable.releaseDateEstimated] && knownReleaseDate == storedReleaseDate
            }

            AlbumTable.upsert(AlbumTable.id) {
                if (newGroupId != null) it[versionGroupId] = newGroupId
                it[id] = album.id
                it[name] = album.name
                it[titleTags] = encodeTitleTags(album.tags)
                it[releaseDate] = knownReleaseDate ?: getISOFromDate(PlatformLocalDate.now())
                it[releaseDateEstimated] = releaseDateIsEstimated
                it[songCount] = album.songCount
                it[cover] = album.coverId?.let { coverId -> EntityID(coverId, ImageTable) }
                it[originalId] = album.originalId
                it[barcode] = album.barcode
            }
            syncAlbumTitleTags(album.id, album.tags)
            if (releaseDateIsEstimated) fillUnknownReleaseDatesFromSongsTx(listOf(album.id))

            if (album.originalId != null) {
                val originalId = album.originalId!!
                val parser = ParserFactory.getParser(originalId)
                val parsed = parser?.parse(originalId)
                val provider = parser?.name ?: "unknown"
                val externalId =
                    parsed?.first ?: (if (originalId.contains(":")) originalId.substringAfter(":") else originalId)

                AlbumProviderTable.upsert(
                    AlbumProviderTable.albumId,
                    AlbumProviderTable.provider,
                    AlbumProviderTable.externalId,
                    onUpdateExclude = listOf(AlbumProviderTable.addedAt)
                ) {
                    it[AlbumProviderTable.albumId] = album.id
                    it[AlbumProviderTable.provider] = provider
                    it[AlbumProviderTable.externalId] = externalId
                    it[AlbumProviderTable.type] = parsed?.second?.value ?: Type.ALBUM.value
                    it[AlbumProviderTable.rawUrl] = originalId
                }
            }

            if (album.musicBrainzId != null) {
                val mbId = album.musicBrainzId!!
                if (MBReleaseTable.selectAll().where { MBReleaseTable.id eq mbId }.empty()) {
                    MBReleaseTable.insert {
                        it[id] = EntityID(mbId, MBReleaseTable)
                        it[title] = album.name
                    }
                }

                AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
                    it[albumId] = album.id
                    it[AlbumMusicBrainzTable.musicBrainzId] = mbId
                }
            }

            AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId eq album.id }
            val creditedAliasIds = album.artists.associate { artist ->
                artist.id to artist.creditedName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { artistService.getOrCreateAliasTx(artist.id, it) }
            }
            AlbumArtistTable.batchInsert(album.artists.withIndex().toList()) { (index, artist) ->
                this[AlbumArtistTable.albumId] = album.id
                this[AlbumArtistTable.artistId] = artist.id
                this[AlbumArtistTable.creditedAliasId] = creditedAliasIds[artist.id]
                this[AlbumArtistTable.position] = index
                this[AlbumArtistTable.joinPhrase] = artist.joinPhrase
            }
            entityEvents.recordChanges(artistsBefore)
            entityEvents.recordChanges(before)
        }

        if (triggerSync && album.musicBrainzId != null && album.musicBrainzId != currentMbId) {
            syncAlbumSongsWithMusicBrainz(album.id, album.musicBrainzId!!)

            if (triggerMerge) entityEvents.albumsLinkedToMusicBrainz(listOf(album.id))
        }
    }
}
