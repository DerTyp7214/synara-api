package dev.dertyp.services

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class RemoteMirrorServiceTest {
    private lateinit var songService: SongService
    private lateinit var artistService: ArtistService
    private lateinit var albumService: AlbumService
    private lateinit var imageService: ImageService
    private lateinit var storageService: StorageService
    private lateinit var playlistService: PlaylistService
    private lateinit var userPlaylistService: UserPlaylistService

    @BeforeEach
    fun setup() {
        songService = mockk<SongService>()
        artistService = mockk<ArtistService>()
        albumService = mockk<AlbumService>()
        imageService = mockk<ImageService>()
        storageService = mockk<StorageService>()
        playlistService = mockk<PlaylistService>()
        userPlaylistService = mockk<UserPlaylistService>()

        startKoin {
            modules(module {
                single { songService }
                single { artistService }
                single { albumService }
                single { imageService }
                single { storageService }
                single { playlistService }
                single { userPlaylistService }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `can instantiate service`() {
        RemoteMirrorService()
    }
}
