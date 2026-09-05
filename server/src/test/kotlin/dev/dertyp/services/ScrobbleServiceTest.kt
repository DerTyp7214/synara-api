package dev.dertyp.services

import dev.dertyp.data.PlaybackReport
import dev.dertyp.data.RecentListens
import dev.dertyp.data.ScrobbleRequest
import dev.dertyp.data.UserSong
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class ScrobbleServiceTest : KoinTest {
    private lateinit var listenService: ListenService
    private lateinit var songService: SongService
    private val listenChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val collectorScope = CoroutineScope(Dispatchers.Default)

    private fun setup() {
        listenService = mockk(relaxed = true)
        songService = mockk(relaxed = true)
        every { listenService.listenChanges } returns listenChanges
        coEvery { listenService.recentListens(any(), any()) } returns emptyList()
        hookService = HookService()
        startKoin {
            modules(module {
                single { listenService }
                single { songService }
                single<HookBus> { hookService }
            })
        }
    }

    private lateinit var hookService: HookService

    @AfterEach
    fun tearDown() {
        collectorScope.cancel()
        stopKoin()
    }

    private fun songStub(id: UUID, duration: Long): UserSong {
        val song = mockk<UserSong>(relaxed = true)
        every { song.id } returns id
        every { song.duration } returns duration
        return song
    }

    private fun collect(service: ScrobbleService, user: UUID): List<RecentListens> {
        val emissions = CopyOnWriteArrayList<RecentListens>()
        collectorScope.launch { service.recentListensFlow(user, 10).collect { emissions.add(it) } }
        return emissions
    }

    private suspend fun awaitCondition(timeoutMs: Long = 3_000, check: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (check()) return
            delay(20.milliseconds)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    @Test
    fun `now-playing is reflected in the recent-listens flow`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 3_000))

        val emissions = collect(service, user)
        awaitCondition { emissions.isNotEmpty() }
        assertNull(emissions.last().nowPlaying)

        service.setNowPlaying(user, songId)
        awaitCondition { emissions.lastOrNull()?.nowPlaying?.song?.id == songId }
    }

    @Test
    fun `now-playing auto-clears after the song duration`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 200))

        val emissions = collect(service, user)
        service.setNowPlaying(user, songId)
        awaitCondition { emissions.lastOrNull()?.nowPlaying?.song?.id == songId }
        awaitCondition { emissions.isNotEmpty() && emissions.last().nowPlaying == null }
    }

    @Test
    fun `a stale expiry does not clear a newer now-playing`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songA = UUID.randomUUID()
        val songB = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songA), user) } returns listOf(songStub(songA, 200))
        coEvery { songService.byIds(listOf(songB), user) } returns listOf(songStub(songB, 3_000))

        val emissions = collect(service, user)
        service.setNowPlaying(user, songA)
        service.setNowPlaying(user, songB)
        awaitCondition { emissions.lastOrNull()?.nowPlaying?.song?.id == songB }

        delay(600.milliseconds)
        assertEquals(songB, emissions.last().nowPlaying?.song?.id)
    }

    @Test
    fun `now-playing changes are emitted as hook events`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 200))
        val events = CopyOnWriteArrayList<HookEvent.NowPlayingChanged>()
        hookService.on<HookEvent.NowPlayingChanged> { events.add(it) }

        service.setNowPlaying(user, songId)
        awaitCondition { events.size == 1 }
        assertEquals(songId, events[0].songId)
        assertEquals(user, events[0].userId)

        awaitCondition { events.size == 2 }
        assertNull(events[1].songId)
        assertEquals(events[0].generation, events[1].generation)

        service.clearNowPlaying(user)
        awaitCondition { events.size == 3 }
        assertNull(events[2].songId)
        assertTrue(events[2].generation > events[1].generation)
    }

    @Test
    fun `clearNowPlaying removes the state`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 3_000))

        val emissions = collect(service, user)
        service.setNowPlaying(user, songId)
        awaitCondition { emissions.lastOrNull()?.nowPlaying?.song?.id == songId }

        service.clearNowPlaying(user)
        awaitCondition { emissions.isNotEmpty() && emissions.last().nowPlaying == null }
    }

    @Test
    fun `listened delegates to ingestLocal with the provided timestamp`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()

        service.listened(user, ScrobbleRequest(songId = songId, listenedAt = 123L, msPlayed = 456L))

        coVerify { listenService.ingestLocal(user, songId, 123L, 456L) }
    }

    @Test
    fun `listened defaults the timestamp when none is provided`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()

        service.listened(user, ScrobbleRequest(songId = songId))

        coVerify { listenService.ingestLocal(eq(user), eq(songId), any(), isNull()) }
    }

    @Test
    fun `playback reports carry position and re-arm expiry from the remaining duration`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 2_000))
        val events = CopyOnWriteArrayList<HookEvent.NowPlayingChanged>()
        hookService.on<HookEvent.NowPlayingChanged> { events.add(it) }
        val emissions = collect(service, user)

        val before = System.currentTimeMillis()
        val received = service.reportPlayback(user, PlaybackReport(songId, positionMs = 1_000, sentAt = before - 300))
        assertTrue(received >= before)
        awaitCondition { events.size == 1 }
        assertEquals(songId, events[0].songId)
        assertTrue(events[0].playing)
        assertTrue(events[0].positionMs in 1_300..1_450, "position ${events[0].positionMs}")

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 1_500, playing = false))
        awaitCondition { events.size == 2 }
        assertEquals(1_500, events[1].positionMs)
        assertTrue(!events[1].playing)
        assertTrue(events[1].generation > events[0].generation)
        delay(800)
        assertEquals(2, events.size)

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 1_800))
        awaitCondition { events.size == 4 }
        assertNull(events[3].songId)
        assertEquals(events[2].generation, events[3].generation)
        coVerify(exactly = 1) { songService.byIds(listOf(songId), user) }
        awaitCondition { emissions.isNotEmpty() && emissions.last().nowPlaying == null }
        assertTrue(emissions.count { it.nowPlaying?.song?.id == songId } <= 1, "heartbeats must not re-emit now playing")
    }

    @Test
    fun `a paused report expires after the paused lease`() = runBlocking {
        setup()
        val service = ScrobbleService()
        service.pausedLeaseMs = 200
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 600_000))
        val events = CopyOnWriteArrayList<HookEvent.NowPlayingChanged>()
        hookService.on<HookEvent.NowPlayingChanged> { events.add(it) }

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 1_000, playing = false))
        awaitCondition { events.size == 1 }
        assertEquals(songId, events[0].songId)

        awaitCondition { events.size == 2 }
        assertNull(events[1].songId)
        assertEquals(events[0].generation, events[1].generation)
    }

    @Test
    fun `a heartbeat while playing keeps extending the lease`() = runBlocking {
        setup()
        val service = ScrobbleService()
        service.playingLeaseMs = 300
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 600_000))
        val events = CopyOnWriteArrayList<HookEvent.NowPlayingChanged>()
        hookService.on<HookEvent.NowPlayingChanged> { events.add(it) }

        repeat(5) { beat ->
            service.reportPlayback(user, PlaybackReport(songId, positionMs = 1_000L + beat * 150))
            delay(150.milliseconds)
        }
        assertEquals(5, events.size)
        assertTrue(events.all { it.songId == songId }, events.map { it.songId }.toString())

        awaitCondition { events.size == 6 }
        assertNull(events[5].songId)
    }

    @Test
    fun `the legacy now-playing call resumes at the projected position`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 600_000))
        val events = CopyOnWriteArrayList<HookEvent.NowPlayingChanged>()
        hookService.on<HookEvent.NowPlayingChanged> { events.add(it) }

        service.setNowPlaying(user, songId)
        awaitCondition { events.size == 1 }
        assertEquals(0, events[0].positionMs)

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 30_000, playing = false))
        awaitCondition { events.size == 2 }
        service.setNowPlaying(user, songId)
        awaitCondition { events.size == 3 }
        assertEquals(30_000, events[2].positionMs)
        assertTrue(events[2].playing)

        delay(150.milliseconds)
        service.setNowPlaying(user, songId)
        awaitCondition { events.size == 4 }
        assertTrue(events[3].positionMs >= 30_100, "position ${events[3].positionMs}")
    }

    @Test
    fun `skewed or future client timestamps do not shift the position`() = runBlocking {
        setup()
        val service = ScrobbleService()
        val user = UUID.randomUUID()
        val songId = UUID.randomUUID()
        coEvery { songService.byIds(listOf(songId), user) } returns listOf(songStub(songId, 60_000))
        val events = CopyOnWriteArrayList<HookEvent.NowPlayingChanged>()
        hookService.on<HookEvent.NowPlayingChanged> { events.add(it) }

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 5_000, sentAt = System.currentTimeMillis() - 10_000))
        awaitCondition { events.size == 1 }
        assertEquals(5_000, events[0].positionMs)

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 6_000, sentAt = System.currentTimeMillis() + 5_000))
        awaitCondition { events.size == 2 }
        assertEquals(6_000, events[1].positionMs)

        service.reportPlayback(user, PlaybackReport(songId, positionMs = 7_000, playing = false, sentAt = System.currentTimeMillis() - 500))
        awaitCondition { events.size == 3 }
        assertEquals(7_000, events[2].positionMs)
    }
}
