package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.User
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.SongService
import dev.dertyp.services.UserService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class MusicBrainzWorkerTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker should tag unmapped entities`() = runBlocking {
        val songService = mockk<SongService>()
        val albumService = mockk<AlbumService>()
        val artistService = mockk<ArtistService>()
        val userService = mockk<UserService>()
        
        val admin = mockk<User>()
        val adminId = UUID.randomUUID()
        coEvery { admin.id } returns adminId
        coEvery { userService.findAdmin() } returns admin

        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        coEvery { songService.songIdsWithoutMusicBrainzId() } returns flowOf(songId)
        coEvery { albumService.albumIdsWithoutMusicBrainzId() } returns flowOf(albumId)
        coEvery { artistService.artistIdsWithoutMusicBrainzId() } returns flowOf(artistId)

        coEvery { songService.fetchMusicBrainzId(songId, adminId, HttpClientPriority.LOW) } returns mockk(relaxed = true)
        coEvery { albumService.fetchMusicBrainzId(albumId, priority = HttpClientPriority.LOW) } returns mockk(relaxed = true)
        coEvery { artistService.fetchMusicBrainzId(artistId, priority = HttpClientPriority.LOW) } returns mockk(relaxed = true)

        startKoin {
            modules(module {
                single { songService }
                single { albumService }
                single { artistService }
                single { userService }
            })
        }

        val worker = MusicBrainzWorker()
        worker.run()

        coVerify { songService.fetchMusicBrainzId(songId, adminId, HttpClientPriority.LOW) }
        coVerify { albumService.fetchMusicBrainzId(albumId, priority = HttpClientPriority.LOW) }
        coVerify { artistService.fetchMusicBrainzId(artistId, priority = HttpClientPriority.LOW) }
    }
}
