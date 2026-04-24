package dev.dertyp.services

import dev.dertyp.data.Artist
import dev.dertyp.data.Song
import dev.dertyp.data.User

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class MirrorServiceTest : KoinTest {
    private lateinit var songService: SongService
    private lateinit var artistService: ArtistService
    private lateinit var albumService: AlbumService
    private lateinit var playlistService: PlaylistService
    private lateinit var userPlaylistService: UserPlaylistService
    private lateinit var imageService: ImageService
    private lateinit var storageService: StorageService
    private lateinit var userService: UserService
    
    private lateinit var service: MirrorService

    @BeforeEach
    fun setup() {
        songService = mockk<SongService>()
        artistService = mockk<ArtistService>()
        albumService = mockk<AlbumService>()
        playlistService = mockk<PlaylistService>()
        userPlaylistService = mockk<UserPlaylistService>()
        imageService = mockk<ImageService>()
        storageService = mockk<StorageService>()
        userService = mockk<UserService>()

        startKoin {
            modules(module {
                single { songService }
                single { artistService }
                single { albumService }
                single { playlistService }
                single { userPlaylistService }
                single { imageService }
                single { storageService }
                single { userService }
            })
        }
        
        service = MirrorService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `getServerPaths should return paths from storageService`() {
        every { storageService.tracksPath } returns "/tracks"
        every { storageService.albumsPath } returns "/albums"
        every { storageService.playlistsPath } returns "/playlists"
        every { storageService.customAudioPath } returns "/custom"
        every { storageService.secondaryTracksPaths } returns listOf("/extra")
        
        val paths = service.getServerPaths()
        assertEquals("/tracks", paths.tracksPath)
        assertEquals("/albums", paths.albumsPath)
        assertEquals("/playlists", paths.playlistsPath)
        assertEquals("/custom", paths.customAudioPath)
        assertEquals(listOf("/extra"), paths.secondaryTracksPaths)
    }

    @Test
    fun `getUsers should return users without password hashes`() = runBlocking {
        val user = User(UUID.randomUUID(), "test", passwordHash = "secret")
        coEvery { userService.queryUser(any()) } returns listOf(user)

        val users = service.getUsers().toList()
        assertEquals(1, users.size)
        assertEquals("test", users[0].username)
        assertEquals("", users[0].passwordHash)
    }

    @Test
    fun `getSongs should delegate to songService`() = runBlocking {
        val song = mockk<Song>()
        every { songService.allSongsFlow() } returns flowOf(song)
        
        val songs = service.getSongs().toList()
        assertEquals(1, songs.size)
        assertEquals(song, songs[0])
    }

    @Test
    fun `getArtists should delegate to artistService`() = runBlocking {
        val artist = mockk<Artist>()
        every { artistService.allArtistsFlow() } returns flowOf(artist)
        
        val artists = service.getArtists().toList()
        assertEquals(1, artists.size)
        assertEquals(artist, artists[0])
    }

    @Test
    fun `getSongData should delegate to songService streamSong when quality is -1`() = runBlocking {
        val songId = UUID.randomUUID()
        val data = byteArrayOf(1, 2, 3)
        every { songService.streamSong(songId, 0, any()) } returns flowOf(data)
        
        val result = service.getSongData(songId, -1).toList()
        assertEquals(1, result.size)
        assertEquals(data.toList(), result[0].toList())
    }

    @Test
    fun `getSongData should delegate to songService downloadSong when quality is not -1`() = runBlocking {
        val songId = UUID.randomUUID()
        val data = byteArrayOf(4, 5, 6)
        every { songService.downloadSong(songId, 320, 0, any()) } returns flowOf(data)
        
        val result = service.getSongData(songId, 320).toList()
        assertEquals(1, result.size)
        assertEquals(data.toList(), result[0].toList())
    }
}
