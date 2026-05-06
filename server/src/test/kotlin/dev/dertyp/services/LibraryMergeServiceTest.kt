package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.metadata.TidalService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class LibraryMergeServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: LibraryMergeService
    private lateinit var environment: ApplicationEnvironment
    private lateinit var songService: SongService
    private lateinit var albumService: AlbumService
    private lateinit var pluginManager: PluginManager
    private lateinit var tidalService: TidalService

    fun setup(dialect: DbDialect) {
        environment = mockk()
        songService = mockk()
        albumService = mockk()
        pluginManager = mockk()
        tidalService = mockk()

        startKoin {
            modules(module {
                single { environment }
                single { songService }
                single { albumService }
                single { pluginManager }
                single { tidalService }
            })
        }

        database = TestDatabase.connect(dialect, "merge_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable, AlbumTable, SongTable, ImageTable, PlaylistTable,
                UserTable, UserPlaylistTable, UserPlaylistSongTable, PlaylistSongTable,
                SongArtistTable, AlbumArtistTable, AlbumMusicBrainzTable, SongMusicBrainzTable,
                TranscodedSongTable, UserSongTable
            )
        }
        service = LibraryMergeService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicates should merge exact duplicate songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(any(), any()) } returns tidalService

        transaction(database) {
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]

            SongTable.insert {
                it[title] = "Duplicate"
                it[this.albumId] = albumId
                it[fileSize] = 100L
                it[duration] = 60L
                it[filePath] = "path/1"
                it[inserted] = 1000L
            }
            SongTable.insert {
                it[title] = "Duplicate"
                it[this.albumId] = albumId
                it[fileSize] = 100L
                it[duration] = 60L
                it[filePath] = "path/1"
                it[inserted] = 2000L
            }
        }

        val result = service.mergeDuplicates()
        
        assertEquals(1, result["songsMerged"])
        transaction(database) {
            assertEquals(1, SongTable.selectAll().count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicates should merge same-album duplicate songs via SongService`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(any(), any()) } returns tidalService
        coEvery { songService.deleteSongs(any()) } returns true

        transaction(database) {
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]

            SongTable.insert {
                it[title] = "Same Album"
                it[this.albumId] = albumId
                it[trackNumber] = 1
                it[fileSize] = 100L
                it[inserted] = 1000L
                it[filePath] = "/path/1"
            }
            SongTable.insert {
                it[title] = "Same Album"
                it[this.albumId] = albumId
                it[trackNumber] = 1
                it[fileSize] = 200L
                it[inserted] = 2000L
                it[filePath] = "/path/2"
            }
        }

        val result = service.mergeDuplicates()
        
        assertEquals(1, result["sameAlbumSongsMerged"])
        coVerify { songService.deleteSongs(match { it.size == 1 }) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicates should merge identical images`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "path1"
                it[imageHash] = "hash1"
                it[origin] = "origin1"
            }
            ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "path2"
                it[imageHash] = "hash1"
                it[origin] = "origin2"
            }
        }

        val result = service.mergeDuplicates()
        assertEquals(1, result["imagesMerged"])
        transaction(database) {
            assertEquals(1, ImageTable.selectAll().count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicates should merge albums with same originalId`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(any(), any()) } returns tidalService
        coEvery { tidalService.getAlbumsByIds(any()) } returns emptyList()
        coEvery { albumService.fetchMusicBrainzId(any()) } returns null
        every { pluginManager.getAllDownloaders() } returns emptyList()

        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album 1"
                it[originalId] = "tidal:orig1"
                it[songCount] = 10
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album 1 (Duplicate)"
                it[originalId] = "tidal:orig1"
                it[songCount] = 12
            }
        }

        val result = service.mergeDuplicates()
        assertEquals(1, result["albumsMerged"])
        transaction(database) {
            assertEquals(1, AlbumTable.selectAll().count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicates should merge similar albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(any(), any()) } returns tidalService
        coEvery { tidalService.getAlbumsByIds(any()) } returns emptyList()
        coEvery { albumService.fetchMusicBrainzId(any()) } returns null
        every { pluginManager.getAllDownloaders() } returns emptyList()

        transaction(database) {
            val imageId = ImageTable.insert {
                it[id] = UUID.randomUUID()
                it[path] = "cover"
                it[imageHash] = "hash"
                it[origin] = "origin"
            }[ImageTable.id]

            val artistId = ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Artist"
            }[ArtistTable.id]

            val album1 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
                it[cover] = imageId
                it[songCount] = 10
                it[releaseDate] = "2023-01-01"
            }[AlbumTable.id]

            val album2 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
                it[cover] = imageId
                it[songCount] = 5
                it[releaseDate] = "2023-01-01"
            }[AlbumTable.id]

            AlbumArtistTable.insert {
                it[albumId] = album1
                it[this.artistId] = artistId
            }
            AlbumArtistTable.insert {
                it[albumId] = album2
                it[this.artistId] = artistId
            }
        }

        val result = service.mergeDuplicates()
        assertEquals(1, result["albumsMerged"])
        transaction(database) {
            assertEquals(1, AlbumTable.selectAll().count())
            assertEquals(1, AlbumArtistTable.selectAll().count())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeDuplicates should handle emoji title variations and path-based duplicates`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val path = "/music/song.flac"

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
                it[id] = UUID.randomUUID()
                it[title] = "Song Title \uD83C\uDD74"
                it[SongTable.albumId] = albumId
                it[filePath] = path
                it[explicit] = false
                it[inserted] = 1000
            }
            
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song Title"
                it[SongTable.albumId] = albumId
                it[filePath] = path
                it[explicit] = true
                it[inserted] = 2000
            }
        }

        service.mergeDuplicates()

        transaction(database) {
            val songs = SongTable.selectAll().where { SongTable.filePath eq path }.toList()
            assertEquals(1, songs.size, "Should have merged duplicates with same path")
            assertEquals("Song Title", songs[0][SongTable.title])
            assertEquals(true, songs[0][SongTable.explicit], "Should have propagated explicit flag")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mergeSongReferences should update all dependent tables`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        transaction(database) {
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]
            val artistId = ArtistTable.insert { it[name] = "Artist" }[ArtistTable.id]
            val userId = UserTable.insert { it[username] = "user"; it[passwordHash] = "pass" }[UserTable.id]

            val song1 = SongTable.insert {
                it[title] = "S1"
                it[this.albumId] = albumId
                it[fileSize] = 100L
                it[inserted] = 1000L
                it[filePath] = "p1"
            }[SongTable.id]

            val song2 = SongTable.insert {
                it[title] = "S1"
                it[this.albumId] = albumId
                it[fileSize] = 100L
                it[inserted] = 2000L
                it[filePath] = "p1"
            }[SongTable.id]

            SongArtistTable.insert { it[songId] = song1; it[this.artistId] = artistId }
            UserSongTable.insert { it[songId] = song1; it[this.userId] = userId; it[isFavourite] = true }
            UserSongTable.insert { it[songId] = song2; it[this.userId] = userId; it[isFavourite] = false }
            
            PlaylistSongTable.insert {
                it[playlistId] = PlaylistTable.insert { table -> table[name] = "P" }[PlaylistTable.id]
                it[songId] = song1
                it[position] = 0
            }
        }

        service.mergeDuplicates()

        transaction(database) {
            val remainingSongId = SongTable.selectAll().single()[SongTable.id].value
            assertEquals(1, SongArtistTable.selectAll().count())
            assertEquals(remainingSongId, SongArtistTable.selectAll().single()[SongArtistTable.songId].value)
            
            assertEquals(1, UserSongTable.selectAll().count())
            val userSong = UserSongTable.selectAll().single()
            assertEquals(remainingSongId, userSong[UserSongTable.songId].value)
            assertEquals(true, userSong[UserSongTable.isFavourite]) // Should merge favorites
            
            assertEquals(1, PlaylistSongTable.selectAll().count())
            assertEquals(remainingSongId, PlaylistSongTable.selectAll().single()[PlaylistSongTable.songId].value)
        }
    }
}
