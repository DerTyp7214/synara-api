package dev.dertyp.services.hue

import dev.dertyp.data.SongAudioTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class HueLightScoreTest {
    private val songId = UUID.randomUUID()
    private val beatMs = 500
    private val durationMs = 120_000L

    private fun beats(count: Int, offset: Int = 0) = List(count) { offset + it * beatMs }

    private fun envelope(durationMs: Long, hz: Int = 10, level: (Long) -> Float): List<Float> =
        List((durationMs * hz / 1000).toInt()) { level(it * 1000L / hz) }

    private fun timeline(beats: List<Int>, envelope: List<Float>) =
        SongAudioTimeline(songId, beatsMs = beats, envelopeHz = 10, envelopeDb = envelope)

    @Test
    fun `downbeat phase follows the loudest beat in each bar`() {
        val beats = beats(240)
        val loud = envelope(durationMs) { ms ->
            val beatIndex = (ms / beatMs).toInt()
            if (beatIndex % 4 == 2 && ms % beatMs < 200) -10f else -40f
        }
        val score = HueLightScore.build(timeline(beats, loud), null, durationMs, 8_000)
        assertEquals(240, score.keyframes.size)
        assertEquals(beatMs, score.beatMs)
        val downbeats = score.keyframes.filter { it.kind != KeyframeKind.BEAT }
        assertTrue(downbeats.all { it.index % 4 == 2 }, downbeats.take(5).toString())
        assertEquals(60, downbeats.size)
        assertEquals(2, score.downbeatPhase)
    }

    @Test
    fun `a loudness step marks a section on a downbeat`() {
        val beats = beats(240)
        val stepped = envelope(durationMs) { ms ->
            val base = if (ms < 60_000) -45f else -12f
            val beatIndex = (ms / beatMs).toInt()
            if (beatIndex % 4 == 0 && ms % beatMs < 200) base + 6f else base
        }
        val score = HueLightScore.build(timeline(beats, stepped), null, durationMs, 8_000)
        val sections = score.keyframes.filter { it.kind == KeyframeKind.SECTION }
        assertEquals(1, sections.size, sections.toString())
        assertEquals(60_000, sections.single().atMs)
        assertTrue(score.keyframes.first { it.atMs < 60_000 }.level < 0.2)
        assertTrue(score.keyframes.last().level > 0.8)
    }

    @Test
    fun `levels are normalized between the 10th and 95th percentile`() {
        val beats = beats(240)
        val ramp = envelope(durationMs) { ms -> -60f + 50f * ms / durationMs }
        val score = HueLightScore.build(timeline(beats, ramp), null, durationMs, 8_000)
        assertEquals(0.0, score.keyframes.first().level, 0.05)
        assertEquals(1.0, score.keyframes.last().level, 0.05)
        val middle = score.keyframes[120].level
        assertTrue(middle in 0.4..0.7, "middle $middle")
    }

    @Test
    fun `bpm alone synthesizes a beat grid`() {
        val score = HueLightScore.build(null, 120.0, 10_000, 8_000)
        assertEquals(20, score.keyframes.size)
        assertEquals(500, score.beatMs)
        assertEquals(listOf(0, 500, 1000), score.keyframes.take(3).map { it.atMs })
        assertTrue(score.keyframes.all { it.level == 1.0 })
        assertEquals(KeyframeKind.DOWNBEAT, score.keyframes[0].kind)
        assertEquals(KeyframeKind.BEAT, score.keyframes[1].kind)
        assertEquals(KeyframeKind.DOWNBEAT, score.keyframes[4].kind)
        assertEquals(0, score.downbeatPhase)
    }

    @Test
    fun `nothing known falls back to the interval grid`() {
        val score = HueLightScore.build(null, null, 30_000, 8_000)
        assertEquals(listOf(0, 8_000, 16_000, 24_000), score.keyframes.map { it.atMs })
        assertTrue(score.keyframes.all { it.kind == KeyframeKind.DOWNBEAT })
        assertNull(score.beatMs)
        assertEquals(0.0, score.beatsPerSecond)
        assertEquals(0, score.downbeatPhase)
    }

    @Test
    fun `the fallback interval is not floored`() {
        val score = HueLightScore.build(null, null, 300, 60)
        assertEquals(listOf(0, 60, 120, 180, 240), score.keyframes.map { it.atMs })
        assertEquals(64, HueLightScore.build(null, null, 0, 60).keyframes.size)
    }

    @Test
    fun `next index search skips ineligible and past keyframes`() {
        val score = HueLightScore.build(null, 120.0, 5_000, 8_000)
        val onlyDownbeats: (Keyframe) -> Boolean = { it.kind == KeyframeKind.DOWNBEAT }
        assertEquals(4, score.nextIndexAfter(0, 0, onlyDownbeats))
        assertEquals(4, score.nextIndexAfter(1_999, 0, onlyDownbeats))
        assertEquals(8, score.nextIndexAfter(2_000, 0, onlyDownbeats))
        assertEquals(8, score.nextIndexAfter(0, 5, onlyDownbeats))
        assertEquals(-1, score.nextIndexAfter(4_500, 0, onlyDownbeats))
    }
}
