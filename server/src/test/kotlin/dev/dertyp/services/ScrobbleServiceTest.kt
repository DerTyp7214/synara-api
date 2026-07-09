package dev.dertyp.services

import dev.dertyp.data.RecentListens
import dev.dertyp.data.ScrobbleRequest
import dev.dertyp.data.UserSong
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
        startKoin {
            modules(module {
                single { listenService }
                single { songService }
            })
        }
    }

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
}
