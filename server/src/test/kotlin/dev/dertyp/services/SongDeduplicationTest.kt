package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableSong
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@Suppress("UNCHECKED_CAST")
class SongDeduplicationTest : KoinTest {
    private lateinit var database: Database
    private val artistService = mockk<ArtistService>(relaxed = true)
    private val albumService = mockk<AlbumService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val genreService = mockk<GenreService>(relaxed = true)
    private val pluginManager = mockk<PluginManager>(relaxed = true)

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongArtistTable, 
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        SongProviderTable,
        *allMusicBrainzTables
    )

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "song_dedupe")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { artistService }
                single { albumService }
                single { genreService }
                single { imageService }
                single { pluginManager }
                single { LibraryMergeService() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if originalUrl matches but path differs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
        }

        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { albumId }
        }

        val song1 = InsertableSong(
            title = "Dedupe Test",
            artists = listOf("Test Artist"),
            album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
            path = "/old/path/song.flac",
            originalUrl = "https://tidal.com/track/dedupe-123",
            duration = 3.minutes.inWholeMilliseconds,
            explicit = false,
            fileSize = 1000,
            bitRate = 1000,
            sampleRate = 44100,
            bitsPerSample = 16
        )

        val song2 = song1.copy(path = "/new/path/song.flac")

        songService.createBatch(listOf(song1))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
        }

        songService.createBatch(listOf(song2))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count(), "Should not have inserted a second song when originalUrl matches")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if title and album match but path differs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
        }

        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { albumId }
        }

        val song1 = InsertableSong(
            title = "Dedupe Test Metadata",
            artists = listOf("Test Artist"),
            album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
            path = "/old/path/song2.flac",
            originalUrl = "",
            trackNumber = 1,
            discNumber = 1,
            duration = 3.minutes.inWholeMilliseconds,
            explicit = false,
            fileSize = 1000,
            bitRate = 1000,
            sampleRate = 44100,
            bitsPerSample = 16
        )

        val song2 = song1.copy(path = "/new/path/song2.flac")

        songService.createBatch(listOf(song1))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
        }

        songService.createBatch(listOf(song2))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count(), "Should not have inserted a second song when metadata matches")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if ISRC matches`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val isrc = "USAT20300184"

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
        }

        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { albumId }
        }

        val song1 = InsertableSong(
            title = "Title A",
            artists = listOf("Test Artist"),
            album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
            path = "/path/1.flac",
            originalUrl = "https://service1.com/track/1",
            isrc = isrc,
            duration = 180000,
            explicit = false
        )

        val song2 = song1.copy(
            title = "Title B",
            path = "/path/2.flac",
            originalUrl = "https://service2.com/track/2"
        )

        songService.createBatch(listOf(song1))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
        }

        songService.createBatch(listOf(song2))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count(), "Should not have inserted a second song when ISRC matches")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should not insert duplicate if different URLs point to the same song via SongProviderTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songService = SongService()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
        }

        coEvery { artistService.getOrBulkCreate(any()) } answers {
            val names = it.invocation.args[0] as List<String>
            names.associateWith { listOf(artistId) }
        }
        coEvery { albumService.getOrBulkCreate(any()) } answers {
            val albums = it.invocation.args[0] as List<InsertableAlbum>
            albums.associateWith { albumId }
        }

        val song1 = InsertableSong(
            title = "Dedupe Test Provider",
            artists = listOf("Test Artist"),
            album = InsertableAlbum(name = "Test Album", artists = listOf("Test Artist")),
            path = "/old/path/song3.flac",
            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            duration = 3.minutes.inWholeMilliseconds,
            explicit = false,
            fileSize = 1000,
            bitRate = 1000,
            sampleRate = 44100,
            bitsPerSample = 16
        )

        val song2 = song1.copy(
            path = "/new/path/song3.flac",
            originalUrl = "https://youtu.be/dQw4w9WgXcQ"
        )

        songService.createBatch(listOf(song1))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
            assertEquals(1L, SongProviderTable.selectAll().count())
        }

        songService.createBatch(listOf(song2))
        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count(), "Should not have inserted a second song when provider ID matches")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicateSongs should merge SongProviderTable entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val libraryMergeService = get<LibraryMergeService>()

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }

            SongTable.insert {
                it[id] = songId1
                it[title] = "Duplicate"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/1"
                it[duration] = 1000
                it[fileSize] = 1000
                it[inserted] = 100
            }
            SongProviderTable.insert {
                it[songId] = songId1
                it[provider] = "provider1"
                it[externalId] = "id1"
                it[rawUrl] = "url1"
            }

            SongTable.insert {
                it[id] = songId2
                it[title] = "Duplicate"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/1"
                it[duration] = 1000
                it[fileSize] = 1000
                it[inserted] = 200
            }
            SongProviderTable.insert {
                it[songId] = songId2
                it[provider] = "provider2"
                it[externalId] = "id2"
                it[rawUrl] = "url2"
            }
        }

        val merged = transaction(database) { libraryMergeService.mergeDuplicateSongs() }
        assertEquals(1, merged)

        transaction(database) {
            assertEquals(1L, SongTable.selectAll().count())
            val providers = SongProviderTable.selectAll().where { SongProviderTable.songId eq songId1 }.toList()
            assertEquals(2, providers.size)
            assertTrue(providers.any { it[SongProviderTable.provider] == "provider1" })
            assertTrue(providers.any { it[SongProviderTable.provider] == "provider2" })
        }
    }
}
