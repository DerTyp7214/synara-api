package dev.dertyp.services

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.data.RemoteServerConfig
import io.ktor.client.engine.cio.CIOEngineConfig
import dev.dertyp.services.lyrics.LyricsCandidateService
import dev.dertyp.services.models.*
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.util.Date
import java.util.UUID
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

    private fun candidate(provider: String, level: LyricsSyncLevel, plain: String, lyrics: SyncedLyrics? = null) = LyricsCandidate(
        id = UUID.randomUUID(),
        songId = UUID.randomUUID(),
        provider = LyricsProviderInfo(provider, provider, null, enabled = true, priority = 0),
        syncLevel = level,
        instrumental = false,
        match = LyricsMatch.PROVIDER,
        durationGapSec = null,
        rank = 1,
        preferred = false,
        fetchedAt = Date(),
        plain = plain,
        lyrics = lyrics,
    )

    private fun remoteWith(vararg candidates: LyricsCandidate): ILyricsCandidateService = mockk {
        coEvery { getLyricsCandidates(any(), false) } returns LyricsSearchResult(candidates.toList(), candidates.firstOrNull()?.id)
        candidates.forEach { candidate -> coEvery { getLyricsCandidate(candidate.id) } returns candidate }
    }

    @Test
    fun `mirrored songs get the plain lyrics of the remote song`() = runBlocking {
        coEvery { songService.storeLyrics(any(), any()) } returns Unit
        val local = UUID.randomUUID()
        val remote = remoteWith(
            candidate("betterlyrics", LyricsSyncLevel.WORD, "Provider text"),
            candidate(LyricsCandidateService.SONG, LyricsSyncLevel.PLAIN, "First line\nSecond line"),
        )

        RemoteMirrorService().mirrorLyrics(remote, UUID.randomUUID(), local)

        coVerify(exactly = 1) { songService.storeLyrics(local, "First line\nSecond line") }
    }

    @Test
    fun `mirrored songs keep the timing of synced remote song lyrics`() = runBlocking {
        coEvery { songService.storeLyrics(any(), any()) } returns Unit
        val local = UUID.randomUUID()
        val lines = listOf(
            LyricLine(1500.milliseconds, 3000.milliseconds, emptyList(), text = "First line"),
            LyricLine(61_250.milliseconds, 63_000.milliseconds, emptyList(), text = "Second line"),
        )
        val remote = remoteWith(
            candidate(LyricsCandidateService.SONG, LyricsSyncLevel.LINE, "First line\nSecond line", SyncedLyrics(lines))
        )

        RemoteMirrorService().mirrorLyrics(remote, UUID.randomUUID(), local)

        coVerify(exactly = 1) { songService.storeLyrics(local, "[00:01.500]First line\n[01:01.250]Second line") }
    }

    @Test
    fun `lyrics stay untouched without remote song lyrics or when the remote fails`() = runBlocking {
        val providerOnly = remoteWith(candidate("lrclib", LyricsSyncLevel.LINE, "Provider text"))
        val blank = remoteWith(candidate(LyricsCandidateService.SONG, LyricsSyncLevel.PLAIN, " "))
        val failing = mockk<ILyricsCandidateService> {
            coEvery { getLyricsCandidates(any(), any()) } throws IllegalStateException("unknown service")
        }
        val service = RemoteMirrorService()

        listOf(providerOnly, blank, failing).forEach { service.mirrorLyrics(it, UUID.randomUUID(), UUID.randomUUID()) }

        coVerify(exactly = 0) { songService.storeLyrics(any(), any()) }
    }

    companion object {
        const val ITERATIONS = 20
    }
}
