package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMusicBrainzService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class UserPlaylistServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: UserPlaylistService
    private lateinit var mbService: CachedMusicBrainzService
    private lateinit var songService: SongService

    fun setup(dialect: DbDialect) {
        mbService = mockk(relaxed = true)
        songService = mockk(relaxed = true)
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
                single<IMusicBrainzService> { mbService }
                single { mbService }
                single { songService }
            })
        }

        database = TestDatabase.connect(dialect, "user_playlist_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                UserPlaylistTable,
                UserPlaylistSongTable,
                SongTable,
                AlbumTable,
                ArtistTable,
                SongArtistTable,
                AlbumArtistTable,
                ImageTable,
                ImageMetadataTable,
                SongMusicBrainzTable,
                MBRecordingTable,
                MBReleaseTable
            )
        }
        service = UserPlaylistService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createPlaylistFromArtists should create a playlist and add songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()
        val mbId1 = UUID.randomUUID()
        val mbId2 = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
                it[isGroup] = false
            }
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            MBRecordingTable.insert {
                it[id] = mbId1
                it[title] = "Song 1"
            }
            MBRecordingTable.insert {
                it[id] = mbId2
                it[title] = "Song 2"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId
                it[filePath] = "path1"
                it[duration] = 1000
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId
                it[filePath] = "path2"
                it[duration] = 2000
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId1
                it[musicBrainzId] = mbId1
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId2
                it[musicBrainzId] = mbId2
            }
        }

        val song1 = UserSong(
            id = songId1, title = "Song 1", artists = emptyList(), album = null,
            duration = 1000, explicit = false, path = "path1", musicBrainzId = mbId1
        )
        val song2 = UserSong(
            id = songId2, title = "Song 2", artists = emptyList(), album = null,
            duration = 2000, explicit = false, path = "path2", musicBrainzId = mbId2
        )

        coEvery { songService.byArtist(0, 10, artistId, userId) } returns PaginatedResponse(listOf(song1, song2), 2, 0, 10)
        coEvery { mbService.getRecording(mbId1) } returns MusicBrainzRecording(id = mbId1, releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2020-01-01")))
        coEvery { mbService.getRecording(mbId2) } returns MusicBrainzRecording(id = mbId2, releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2010-01-01")))

        val playlistId = service.createPlaylistFromArtists(userId, "Smart Playlist", listOf(artistId), 10, ArtistPlaylistSortStrategy.MB_RELEASE_DATE)

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("Smart Playlist", playlist?.name)
        assertEquals(listOf(songId1, songId2), playlist?.songs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createPlaylistFromArtists with MB_RELEASE_DATE_ASC should sort correctly`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()
        val mbId1 = UUID.randomUUID()
        val mbId2 = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
                it[isGroup] = false
            }
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            MBRecordingTable.insert {
                it[id] = mbId1
                it[title] = "Song 1"
            }
            MBRecordingTable.insert {
                it[id] = mbId2
                it[title] = "Song 2"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId
                it[filePath] = "path1"
                it[duration] = 1000
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId
                it[filePath] = "path2"
                it[duration] = 2000
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId1
                it[musicBrainzId] = mbId1
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId2
                it[musicBrainzId] = mbId2
            }
        }

        val song1 = UserSong(
            id = songId1, title = "Song 1", artists = emptyList(), album = null,
            duration = 1000, explicit = false, path = "path1", musicBrainzId = mbId1
        )
        val song2 = UserSong(
            id = songId2, title = "Song 2", artists = emptyList(), album = null,
            duration = 2000, explicit = false, path = "path2", musicBrainzId = mbId2
        )

        coEvery { songService.byArtist(0, 10, artistId, userId) } returns PaginatedResponse(listOf(song1, song2), 2, 0, 10)
        coEvery { mbService.getRecording(mbId1) } returns MusicBrainzRecording(id = mbId1, releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2020-01-01")))
        coEvery { mbService.getRecording(mbId2) } returns MusicBrainzRecording(id = mbId2, releases = listOf(MusicBrainzRelease(id = UUID.randomUUID(), date = "2010-01-01")))

        val playlistId = service.createPlaylistFromArtists(userId, "Smart Playlist Asc", listOf(artistId), 10, ArtistPlaylistSortStrategy.MB_RELEASE_DATE_ASC)

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals(listOf(songId2, songId1), playlist?.songs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return user playlist with cover blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val playlistId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "user_playlist_blurhash"
            }
            UserPlaylistTable.insert {
                it[id] = playlistId
                it[name] = "User Playlist with Cover"
                it[UserPlaylistTable.imageId] = imageId
                it[creator] = userId
                it[description] = ""
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals(imageId, playlist?.imageId)
        assertEquals("user_playlist_blurhash", playlist?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return user playlist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val playlistId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            UserPlaylistTable.insert {
                it[id] = playlistId
                it[name] = "My Playlist"
                it[description] = ""
                it[creator] = userId
            }
        }

        val playlist = service.byId(playlistId)
        assertNotNull(playlist)
        assertEquals("My Playlist", playlist?.name)
    }
}
