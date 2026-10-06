package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class LibraryMergeServiceChunkedFixTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: LibraryMergeService

    fun setup(dialect: DbDialect) {
        val albumService = mockk<AlbumService> {
            coEvery { syncAlbumSongsWithMusicBrainz(any(), any()) } returns Unit
            coEvery { rebuildVersionGroups() } returns 0
        }
        val pluginManager = mockk<PluginManager>()

        startKoin {
            modules(module {
                single { albumService }
                single { pluginManager }
            })
        }

        database = TestDatabase.connect(dialect, "merge_chunked_fix_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable, AlbumTable, AlbumTitleTagTable, SongTable, SongVariantTable, ImageTable, PlaylistTable,
                UserTable, UserPlaylistTable, UserPlaylistSongTable, PlaylistSongTable,
                SongArtistTable, AlbumArtistTable, AlbumMusicBrainzTable, SongMusicBrainzTable,
                TranscodedSongTable, UserSongTable, SongProviderTable, AlbumProviderTable,
                AlbumGenreTable, GenreTable,
                *allMusicBrainzTables
            )
        }
        service = LibraryMergeService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private enum class Kind { COVER, RELEASE, CLEAN, COLLISION }

    private data class Seeded(
        val albumId: UUID,
        val kind: Kind,
        val keptTitles: Set<String>,
        val splitTitles: Set<String>
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fixIncorrectMerges splits every album across several chunks as a single pass would`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            val perKind = LibraryMergeService.FIX_MERGES_CHUNK_SIZE / 2 + 10
            val albumTotal = perKind * Kind.entries.size
            assertTrue(albumTotal > 2 * LibraryMergeService.FIX_MERGES_CHUNK_SIZE)

            val otherReleaseTitle = "Other Release"
            val seeded = transaction(database) {
                val keptCover = ImageTable.insertAndGetId {
                    it[ImageTable.id] = UUID.randomUUID()
                    it[ImageTable.path] = "kept"; it[ImageTable.imageHash] = "kept"; it[ImageTable.origin] = "o"
                }
                val strayCover = ImageTable.insertAndGetId {
                    it[ImageTable.id] = UUID.randomUUID()
                    it[ImageTable.path] = "stray"; it[ImageTable.imageHash] = "stray"; it[ImageTable.origin] = "o"
                }

                val groupId = MBReleaseGroupTable.insertAndGetId {
                    it[MBReleaseGroupTable.id] = UUID.randomUUID()
                    it[MBReleaseGroupTable.title] = "Group"
                }
                val albumRelease = MBReleaseTable.insertAndGetId {
                    it[MBReleaseTable.id] = UUID.randomUUID()
                    it[MBReleaseTable.title] = "Album Release"
                    it[MBReleaseTable.releaseGroupId] = groupId
                }
                val otherRelease = MBReleaseTable.insertAndGetId {
                    it[MBReleaseTable.id] = UUID.randomUUID()
                    it[MBReleaseTable.title] = otherReleaseTitle
                    it[MBReleaseTable.releaseGroupId] = groupId
                }

                fun song(
                    albumId: EntityID<UUID>,
                    title: String,
                    track: Int,
                    cover: EntityID<UUID>? = null,
                    release: EntityID<UUID>? = null
                ) {
                    val songId = SongTable.insertAndGetId {
                        it[SongTable.title] = title
                        it[SongTable.albumId] = albumId
                        it[SongTable.trackNumber] = track
                        it[SongTable.filePath] = title
                        it[SongTable.cover] = cover
                    }
                    if (release != null) {
                        val recordingId = MBRecordingTable.insertAndGetId {
                            it[MBRecordingTable.id] = UUID.randomUUID()
                            it[MBRecordingTable.title] = title
                        }
                        MBRecordingReleaseTable.insert {
                            it[MBRecordingReleaseTable.recordingId] = recordingId
                            it[MBRecordingReleaseTable.releaseId] = release
                        }
                        SongMusicBrainzTable.insert {
                            it[SongMusicBrainzTable.songId] = songId
                            it[SongMusicBrainzTable.musicBrainzId] = recordingId
                        }
                    }
                }

                (0 until perKind).flatMap { index ->
                    Kind.entries.map { kind ->
                        val prefix = "${kind.name.lowercase()}-$index"
                        val albumId = AlbumTable.insertAndGetId {
                            it[AlbumTable.name] = prefix
                            it[AlbumTable.songCount] = 2
                            if (kind == Kind.COVER) it[AlbumTable.cover] = keptCover
                        }
                        when (kind) {
                            Kind.COVER -> {
                                song(albumId, "$prefix-kept", 1, cover = keptCover)
                                song(albumId, "$prefix-split", 2, cover = strayCover)
                                Seeded(albumId.value, kind, setOf("$prefix-kept"), setOf("$prefix-split"))
                            }

                            Kind.RELEASE -> {
                                AlbumMusicBrainzTable.insert {
                                    it[AlbumMusicBrainzTable.albumId] = albumId
                                    it[AlbumMusicBrainzTable.musicBrainzId] = albumRelease
                                }
                                song(albumId, "$prefix-kept", 1, release = albumRelease)
                                song(albumId, "$prefix-split", 2, release = otherRelease)
                                Seeded(albumId.value, kind, setOf("$prefix-kept"), setOf("$prefix-split"))
                            }

                            Kind.CLEAN -> {
                                song(albumId, "$prefix-a", 1)
                                song(albumId, "$prefix-b", 2)
                                Seeded(albumId.value, kind, setOf("$prefix-a", "$prefix-b"), emptySet())
                            }

                            Kind.COLLISION -> {
                                song(albumId, "$prefix-a1", 1)
                                song(albumId, "$prefix-a2", 2)
                                song(albumId, "$prefix-b1", 1)
                                song(albumId, "$prefix-b2", 2)
                                Seeded(
                                    albumId.value,
                                    kind,
                                    setOf("$prefix-a1", "$prefix-a2"),
                                    setOf("$prefix-b1", "$prefix-b2")
                                )
                            }
                        }
                    }
                }
            }

            var progressCalls = 0
            val fixed = service.fixIncorrectMerges { _, _ -> progressCalls++ }

            assertEquals(albumTotal, progressCalls)
            assertEquals(perKind * 3, fixed)

            transaction(database) {
                fun songsOf(albumId: UUID) = SongTable.select(SongTable.title, SongTable.trackNumber)
                    .where { SongTable.albumId eq albumId }
                    .associate { it[SongTable.title] to it[SongTable.trackNumber] }

                fun titlesOf(albumId: UUID) = songsOf(albumId).keys

                fun albumOf(title: String) = SongTable.select(SongTable.albumId)
                    .where { SongTable.title eq title }
                    .single()[SongTable.albumId].value

                val seededIds = seeded.map { it.albumId }.toSet()
                seeded.forEach { album ->
                    val songCount =
                        AlbumTable.selectAll().where { AlbumTable.id eq album.albumId }.single()[AlbumTable.songCount]
                    assertEquals(album.keptTitles.size, songCount)
                    if (album.kind == Kind.COLLISION) {
                        assertEquals(
                            listOf(1, 2),
                            songsOf(album.albumId).values.sorted(),
                            "songs left in ${album.kind} album"
                        )
                    } else {
                        assertEquals(album.keptTitles, titlesOf(album.albumId), "songs left in ${album.kind} album")
                    }
                }

                seeded.filter { it.kind == Kind.COVER }.forEach { album ->
                    val newAlbum = album.splitTitles.map { albumOf(it) }.single()
                    assertFalse(newAlbum in seededIds)
                    assertEquals(album.splitTitles, titlesOf(newAlbum))
                }

                seeded.filter { it.kind == Kind.COLLISION }.forEach { album ->
                    val allTitles = album.keptTitles + album.splitTitles
                    val newAlbums = allTitles.map { albumOf(it) }.toSet() - album.albumId
                    val newAlbum = newAlbums.single()
                    assertFalse(newAlbum in seededIds)
                    assertEquals(listOf(1, 2), songsOf(newAlbum).values.sorted())
                    assertEquals(allTitles, titlesOf(album.albumId) + titlesOf(newAlbum))
                }

                val releaseTargets =
                    seeded.filter { it.kind == Kind.RELEASE }.flatMap { album -> album.splitTitles.map { albumOf(it) } }
                        .toSet()
                assertEquals(1, releaseTargets.size)
                val releaseTarget = releaseTargets.single()
                assertFalse(releaseTarget in seededIds)
                val releaseAlbum = AlbumTable.selectAll().where { AlbumTable.id eq releaseTarget }.single()
                assertEquals(otherReleaseTitle, releaseAlbum[AlbumTable.name])
                assertEquals(perKind, releaseAlbum[AlbumTable.songCount])
                assertEquals(perKind, titlesOf(releaseTarget).size)

                assertEquals((albumTotal + perKind * 2 + 1).toLong(), AlbumTable.selectAll().count())
            }
        }
}
