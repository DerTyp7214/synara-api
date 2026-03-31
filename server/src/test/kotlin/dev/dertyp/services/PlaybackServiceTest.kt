package dev.dertyp.services

import dev.dertyp.data.PlaybackState
import dev.dertyp.data.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceTest {
    private val service = PlaybackService()

    private fun createDummyState(isPlaying: Boolean = false): PlaybackState {
        return PlaybackState(
            queue = emptyList(),
            currentIndex = 0,
            isPlaying = isPlaying,
            positionMs = 0,
            shuffleMode = false,
            repeatMode = RepeatMode.OFF
        )
    }

    @Test
    fun `getPlaybackState should return null initially`() {
        val sessionId = UUID.randomUUID()
        assertNull(service.getPlaybackState(sessionId))
    }

    @Test
    fun `setPlaybackState should update state and be retrievable`() = runTest {
        val sessionId = UUID.randomUUID()
        val state = createDummyState(isPlaying = true)

        service.setPlaybackState(sessionId, state)

        assertEquals(state, service.getPlaybackState(sessionId))
    }

    @Test
    fun `observePlaybackState should emit new states`() = runTest {
        val sessionId = UUID.randomUUID()
        val state = createDummyState(isPlaying = true)

        val results = mutableListOf<PlaybackState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observePlaybackState(sessionId).collect {
                results.add(it)
            }
        }

        service.setPlaybackState(sessionId, state)

        assertEquals(1, results.size)
        assertEquals(state, results[0])
        job.cancel()
    }

    @Test
    fun `different sessions should have independent states`() = runTest {
        val session1 = UUID.randomUUID()
        val session2 = UUID.randomUUID()
        val state1 = createDummyState(isPlaying = true)
        val state2 = createDummyState(isPlaying = false)

        service.setPlaybackState(session1, state1)
        service.setPlaybackState(session2, state2)

        assertEquals(state1, service.getPlaybackState(session1))
        assertEquals(state2, service.getPlaybackState(session2))
    }
}
