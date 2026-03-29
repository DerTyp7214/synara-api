package dev.dertyp.services

import dev.dertyp.data.User
import dev.dertyp.db.*
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class SongRpcServiceTest {
    private lateinit var database: Database
    private lateinit var songService: SongService
    private lateinit var rpcService: SongRpcService
    
    private val musicBrainzService = mockk<MusicBrainzService>()
    private val environment = mockk<ApplicationEnvironment>()
    
    private val user = User(
        id = UUID.randomUUID(),
        username = "testuser",
        passwordHash = "hash",
        isAdmin = true
    )

    @BeforeEach
    fun setup() {
        database = Database.connect("jdbc:h2:mem:song_rpc_test_${UUID.randomUUID().toString().replace("-", "")};MODE=MYSQL;DB_CLOSE_DELAY=-1", "org.h2.Driver")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                SongTable,
                AlbumTable,
                ArtistTable,
                SongArtistTable,
                AlbumArtistTable,
                SongMusicBrainzTable,
                AlbumMusicBrainzTable,
                ArtistMusicBrainzTable,
                UserSongTable,
                ArtistAliasTable,
                PlaylistSongTable,
                UserPlaylistSongTable,
                ImageTable
            )
            
            UserTable.insert {
                it[id] = user.id
                it[username] = user.username
                it[passwordHash] = user.passwordHash
                it[isAdmin] = user.isAdmin
            }
        }

        try {
            startKoin {
                modules(module {
                    single { environment }
                    single { musicBrainzService }
                })
            }
        } catch (_: Exception) {
            // Already started
        }

        songService = SongService()
        rpcService = SongRpcService(user, songService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `byId should return song with full metadata`() = runBlocking {
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/to/song.mp3"
                it[duration] = 180000
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val song = rpcService.byId(songId)
        assertNotNull(song)
        assertEquals("Test Song", song?.title)
        assertEquals("Test Album", song?.album?.name)
        assertEquals(1, song?.artists?.size)
        assertEquals("Test Artist", song?.artists?.firstOrNull()?.name)
    }

    @Test
    fun `setLiked should update UserSongTable`() = runBlocking {
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Likable Song"
                it[SongTable.albumId] = albumId
            }
        }

        val updated = rpcService.setLiked(songId, true, null)
        assertNotNull(updated)
        assertEquals(true, updated?.isFavourite)

        val retrieved = rpcService.byId(songId)
        assertEquals(true, retrieved?.isFavourite)
    }

    @Test
    fun `rankedSearch should return matching songs by title`() = runBlocking {
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Searching for this"
                it[SongTable.albumId] = albumId
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Not this one"
                it[SongTable.albumId] = albumId
            }
        }

        val result = rpcService.rankedSearch(0, 10, "Searching", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("Searching for this", result.data[0].title)
    }

    @Test
    fun `rankedSearch should find songs by artist and album`() = runBlocking {
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Unique Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Legendary Album"
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Some Track"
                it[SongTable.albumId] = albumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val artistResult = rpcService.rankedSearch(0, 10, "Unique", explicit = false, liked = false)
        assertEquals(1, artistResult.data.size)
        assertEquals("Some Track", artistResult.data[0].title)

        val albumResult = rpcService.rankedSearch(0, 10, "Legendary", explicit = false, liked = false)
        assertEquals(1, albumResult.data.size)
        assertEquals("Some Track", albumResult.data[0].title)
    }

    @Test
    fun `rankedSearch should find songs by MusicBrainz ID`() = runBlocking {
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val mbId = "550e8400-e29b-41d4-a716-446655440000"

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "MBID Song"
                it[SongTable.albumId] = albumId
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = songId
                it[musicBrainzId] = mbId
            }
        }

        val result = rpcService.rankedSearch(0, 10, mbId, explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("MBID Song", result.data[0].title)
    }

    @Test
    fun `rankedSearch should support negative keywords`() = runBlocking {
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Keep This"
                it[SongTable.albumId] = albumId
                it[filePath] = "/keep"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Remove This"
                it[SongTable.albumId] = albumId
                it[filePath] = "/remove"
            }
        }

        val result = rpcService.rankedSearch(0, 10, "This -Remove", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("Keep This", result.data[0].title)
    }

    @Test
    fun `rankedSearch should return one song for multiple artists`() = runBlocking {
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val artistId1 = UUID.randomUUID()
        val artistId2 = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId1
                it[name] = "Artist One"
            }
            ArtistTable.insert {
                it[id] = artistId2
                it[name] = "Artist Two"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Multiple Artists Track"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path"
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId1
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId2
            }
        }

        val result = rpcService.rankedSearch(0, 10, "Multiple Artists", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        val song = result.data[0]
        assertEquals("Multiple Artists Track", song.title)
        assertEquals(2, song.artists.size)

        val result2 = rpcService.rankedSearch(0, 10, "Artist", explicit = false, liked = false)
        assertEquals(1, result2.data.size)
        val song2 = result2.data[0]
        assertEquals("Multiple Artists Track", song2.title)
        assertEquals(2, song2.artists.size)
    }
}
