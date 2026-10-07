package dev.dertyp.services

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.data.RemoteServerConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RemoteMirrorServiceTest : KoinTest {
    private lateinit var songService: SongService
    private lateinit var artistService: ArtistService
    private lateinit var albumService: AlbumService
    private lateinit var imageService: ImageService
    private lateinit var storageService: StorageService
    private lateinit var playlistService: PlaylistService
    private lateinit var userPlaylistService: UserPlaylistService
    private lateinit var httpClientFactory: HttpClientFactory

    @BeforeEach
    fun setup() {
        songService = mockk<SongService>()
        artistService = mockk<ArtistService>()
        albumService = mockk<AlbumService>()
        imageService = mockk<ImageService>()
        storageService = mockk<StorageService>()
        playlistService = mockk<PlaylistService>()
        userPlaylistService = mockk<UserPlaylistService>()
        httpClientFactory = mockk<HttpClientFactory>()

        startKoin {
            modules(module {
                single { songService }
                single { artistService }
                single { albumService }
                single { imageService }
                single { storageService }
                single { playlistService }
                single { userPlaylistService }
                single { httpClientFactory }
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

    private fun remote(host: String) = RemoteServerConfig(host = host, port = 1, username = "user", password = "pass")

    private fun mirrorsBlockedUntil(release: CountDownLatch): AtomicInteger {
        val runs = AtomicInteger(0)
        every { httpClientFactory.shared<CIOEngineConfig>(any(), any(), any(), any()) } answers {
            runs.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            throw IllegalStateException("no remote server")
        }
        return runs
    }

    @Test
    fun `a mirror is in progress as soon as startMirror returns`() = runBlocking {
        repeat(ITERATIONS) {
            val release = CountDownLatch(1)
            mirrorsBlockedUntil(release)
            val service = RemoteMirrorService()

            service.startMirror(remote("first"))
            val mirroring = service.isMirroring
            release.countDown()
            withTimeout(5.seconds) { while (service.isMirroring) delay(1.milliseconds) }

            assertTrue(mirroring)
        }
    }

    @Test
    fun `a second start right after the first is ignored`() = runBlocking {
        repeat(ITERATIONS) {
            val release = CountDownLatch(1)
            val runs = mirrorsBlockedUntil(release)
            val service = RemoteMirrorService()

            service.startMirror(remote("first"))
            service.startMirror(remote("second"))
            release.countDown()
            withTimeout(5.seconds) { while (runs.get() == 0 || service.isMirroring) delay(1.milliseconds) }

            assertEquals(1, runs.get())
        }
    }

    companion object {
        const val ITERATIONS = 20
    }
}
