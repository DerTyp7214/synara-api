package dev.dertyp.services.hue

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HueMotionSchedulerTest {
    private val score = HueLightScore.build(null, 120.0, 10_000, 8_000)

    private fun runScheduler(
        clock: MutableStateFlow<PlaybackClock>,
        latencyMs: Int,
        durationMs: Long = 10_000,
        cadence: (Keyframe) -> Boolean = { true },
        block: suspend TestScope.(List<Pair<Long, Keyframe>>, MutableStateFlow<PlaybackClock>) -> Unit,
    ) = runTest {
        val emitted = ArrayList<Pair<Long, Keyframe>>()
        val job = backgroundScope.launch {
            HueMotionScheduler(clock, score, durationMs, latencyMs, cadence, now = { testScheduler.currentTime }) { keyframe, _ ->
                emitted += testScheduler.currentTime to keyframe
            }.run()
        }
        block(emitted, clock)
        job.cancel()
    }

    @Test
    fun `frames fire at each keyframe minus latency`() {
        val clock = MutableStateFlow(PlaybackClock(0, 0, true))
        runScheduler(clock, 100) { emitted, _ ->
            advanceTimeBy(1_650)
            yield()
            assertEquals(listOf(400L, 900L, 1_400L), emitted.map { it.first })
            assertEquals(listOf(500, 1_000, 1_500), emitted.map { it.second.atMs })
        }
    }

    @Test
    fun `a keyframe inside the latency window fires immediately`() {
        val clock = MutableStateFlow(PlaybackClock(450, 0, true))
        runScheduler(clock, 200) { emitted, _ ->
            advanceTimeBy(1)
            yield()
            assertEquals(listOf(0L), emitted.map { it.first })
            assertEquals(500, emitted.single().second.atMs)
            advanceTimeBy(600)
            yield()
            assertEquals(listOf(500, 1_000), emitted.map { it.second.atMs })
            assertEquals(350L, emitted[1].first)
        }
    }

    @Test
    fun `a negative latency counts as zero`() {
        val clock = MutableStateFlow(PlaybackClock(0, 0, true))
        runScheduler(clock, -300) { emitted, _ ->
            advanceTimeBy(499)
            yield()
            assertTrue(emitted.isEmpty())
            advanceTimeBy(2)
            yield()
            assertEquals(listOf(500L), emitted.map { it.first })
        }
    }

    @Test
    fun `a seek moves the next frame`() {
        val clock = MutableStateFlow(PlaybackClock(0, 0, true))
        runScheduler(clock, 0) { emitted, flow ->
            advanceTimeBy(600)
            yield()
            assertEquals(listOf(500), emitted.map { it.second.atMs })
            flow.value = PlaybackClock(5_100, testScheduler.currentTime, true)
            advanceTimeBy(450)
            yield()
            assertEquals(listOf(500, 5_500), emitted.map { it.second.atMs })
        }
    }

    @Test
    fun `pause holds until resume`() {
        val clock = MutableStateFlow(PlaybackClock(0, 0, true))
        runScheduler(clock, 0) { emitted, flow ->
            advanceTimeBy(600)
            yield()
            assertEquals(1, emitted.size)
            flow.value = PlaybackClock(600, testScheduler.currentTime, false)
            advanceTimeBy(5_000)
            yield()
            assertEquals(1, emitted.size)
            flow.value = PlaybackClock(600, testScheduler.currentTime, true)
            advanceTimeBy(450)
            yield()
            assertEquals(listOf(500, 1_000), emitted.map { it.second.atMs })
        }
    }

    @Test
    fun `cadence filters keyframes and the loop ends at the duration`() {
        val clock = MutableStateFlow(PlaybackClock(0, 0, true))
        runScheduler(clock, 0, durationMs = 5_000, cadence = { it.kind == KeyframeKind.DOWNBEAT }) { emitted, _ ->
            advanceTimeBy(20_000)
            yield()
            assertEquals(listOf(2_000, 4_000), emitted.map { it.second.atMs })
            assertTrue(emitted.all { it.second.kind == KeyframeKind.DOWNBEAT })
        }
    }
}
