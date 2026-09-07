package dev.dertyp.services.hue

import dev.dertyp.data.AudioBand
import dev.dertyp.data.SongAudioBand
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
    private val bandHz = 50
    private val beatCount = 240
    private val bandFrames = beatCount * beatMs * bandHz / 1000

    private fun beats(count: Int, offset: Int = 0) = List(count) { offset + it * beatMs }

    private fun envelope(durationMs: Long, hz: Int = 10, level: (Long) -> Float): List<Float> =
        List((durationMs * hz / 1000).toInt()) { level(it * 1000L / hz) }

    private fun timeline(beats: List<Int>, envelope: List<Float>, bass: List<Float> = emptyList()) =
        SongAudioTimeline(songId, beatsMs = beats, envelopeHz = 10, envelopeDb = envelope, bassEnvelopeDb = bass)

    private fun flatLoudness() = envelope(durationMs) { ms -> if ((ms / 100) % 2 == 0L) -9f else -11f }

    private fun flatSub() = List(bandFrames) { -30f }

    private fun bandFrame(beat: Int) = beat * beatMs * bandHz / 1000

    private fun kickBand(base: Float = -40f, rise: (Int) -> Float): List<Float> {
        val levels = MutableList(bandFrames) { base }
        for (beat in 0 until beatCount) {
            val start = bandFrame(beat)
            for (offset in 0 until 3) if (start + offset < bandFrames) levels[start + offset] = base + rise(beat)
        }
        return levels
    }

    private fun bandTimeline(
        beats: List<Int>,
        kick: List<Float>,
        sub: List<Float> = flatSub(),
        loudness: List<Float> = flatLoudness(),
    ) = SongAudioTimeline(
        songId,
        beatsMs = beats,
        envelopeHz = 10,
        envelopeDb = loudness,
        bandHz = bandHz,
        bands = listOf(
            SongAudioBand(AudioBand.SUB, 20, 60, sub),
            SongAudioBand(AudioBand.KICK, 60, 130, kick),
        ),
    )

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
    fun `the bass source follows bass accents when the loudness envelope is flat`() {
        val beats = beats(240)
        val flat = envelope(durationMs) { -30f }
        val bass = envelope(durationMs) { ms ->
            val beatIndex = (ms / beatMs).toInt()
            if (beatIndex % 4 == 1 && ms % beatMs < 100) -8f else -55f
        }
        val score = HueLightScore.build(timeline(beats, flat, bass), null, durationMs, 8_000, LevelSource.BASS)
        assertEquals(1, score.downbeatPhase)
        val downbeats = score.keyframes.filter { it.kind != KeyframeKind.BEAT }
        assertTrue(downbeats.all { it.index % 4 == 1 }, downbeats.take(5).toString())
        assertEquals(0, HueLightScore.build(timeline(beats, flat, bass), null, durationMs, 8_000).downbeatPhase)
    }

    @Test
    fun `the bass source falls back to the loudness envelope when no bass band is stored`() {
        val beats = beats(240)
        val loud = envelope(durationMs) { ms ->
            val beatIndex = (ms / beatMs).toInt()
            if (beatIndex % 4 == 2 && ms % beatMs < 200) -10f else -40f
        }
        val loudness = HueLightScore.build(timeline(beats, loud), null, durationMs, 8_000)
        val bass = HueLightScore.build(timeline(beats, loud), null, durationMs, 8_000, LevelSource.BASS)
        assertEquals(loudness.keyframes.map { it.copy(floor = 0.0) }, bass.keyframes.map { it.copy(floor = 0.0) })
        assertTrue(bass.keyframes.all { it.floor == 0.30 }, bass.keyframes.take(3).toString())
        assertTrue(loudness.keyframes.all { it.floor == 0.55 }, loudness.keyframes.take(3).toString())
        assertEquals(loudness.downbeatPhase, bass.downbeatPhase)
        assertEquals(loudness.beatMs, bass.beatMs)
    }

    @Test
    fun `the level floor widens for the bass source`() {
        val beats = beats(240)
        val loud = envelope(durationMs) { -30f + (it % 400) / 10f }
        assertEquals(0.55, HueLightScore.build(timeline(beats, loud), null, durationMs, 8_000).levelFloor)
        assertEquals(0.30, HueLightScore.build(timeline(beats, loud), null, durationMs, 8_000, LevelSource.BASS).levelFloor)
        assertEquals(0.30, HueLightScore.build(null, null, 30_000, 8_000, LevelSource.BASS).levelFloor)
        assertEquals(0.55, HueLightScore.build(null, null, 30_000, 8_000).levelFloor)
    }

    @Test
    fun `the bass level takes the peak of a short kick while loudness averages it away`() {
        val beats = beats(240)
        val spikes = envelope(durationMs) { ms -> if (ms % beatMs < 100) -6f else -60f }
        val bass = HueLightScore.build(timeline(beats, spikes, spikes), null, durationMs, 8_000, LevelSource.BASS)
        val loudness = HueLightScore.build(timeline(beats, spikes), null, durationMs, 8_000)
        assertEquals(1.0, bass.keyframes[8].level, 0.01)
        assertTrue(loudness.keyframes[8].level < 0.4, "loudness ${loudness.keyframes[8].level}")
        assertTrue(bass.keyframes.all { it.level > 0.9 })
    }

    @Test
    fun `kick pulses are normalised within eight bars so verse kicks stay bright`() {
        val beats = beats(beatCount)
        val kick = kickBand { if (it < 120) 30f else 12f }
        val score = HueLightScore.build(bandTimeline(beats, kick), null, durationMs, 8_000, LevelSource.BASS)

        assertEquals(0.0, score.keyframes[0].level, 0.01, "the track opens on the kick so there is no rise to measure")
        val chorus = (1..119).map { score.keyframes[it].level }
        val verse = (160..239).map { score.keyframes[it].level }
        assertTrue(chorus.all { it >= 0.9 }, "chorus kicks should stay bright: ${chorus.min()}")
        assertTrue(verse.all { it >= 0.9 }, "verse kicks should stay bright: ${verse.min()}")
        val afterDrop = (120..140).map { score.keyframes[it].level }
        assertTrue(afterDrop.any { it < 0.6 }, "the beats after the drop are still measured against it: $afterDrop")
    }

    @Test
    fun `a kick-less passage does not blow up`() {
        val beats = beats(beatCount)
        val kick = List(bandFrames) { frame ->
            when {
                frame >= bandFrames / 2 -> if (frame % 2 == 0) -40f else -38f
                frame % bandFrame(1) < 3 -> -20f
                else -> -40f
            }
        }
        val score = HueLightScore.build(bandTimeline(beats, kick), null, durationMs, 8_000, LevelSource.BASS)

        val quiet = (120..239).map { score.keyframes[it].level }
        assertTrue(quiet.all { it <= 0.4 }, "a wobbling kick band should stay dim: ${quiet.max()}")
    }

    @Test
    fun `a bass-light track is scaled down against a bass-heavy one`() {
        val beats = beats(beatCount)
        val heavy = HueLightScore.build(
            bandTimeline(beats, kickBand(base = -20f) { 12f }), null, durationMs, 8_000, LevelSource.BASS
        )
        val light = HueLightScore.build(
            bandTimeline(beats, kickBand(base = -47f) { 12f }), null, durationMs, 8_000, LevelSource.BASS
        )

        heavy.keyframes.drop(1).forEach { assertEquals(1.0, it.level, 0.02, "heavy beat ${it.index}") }
        light.keyframes.drop(1).forEach {
            assertEquals(0.45 * heavy.keyframes[it.index].level, it.level, 0.02, "light beat ${it.index}")
        }
    }

    @Test
    fun `a flat kick band falls back to the loudness envelope`() {
        val beats = beats(beatCount)
        val loud = envelope(durationMs) { ms ->
            val beatIndex = (ms / beatMs).toInt()
            if (beatIndex % 4 == 2 && ms % beatMs < 200) -10f else -40f
        }
        val timeline = bandTimeline(beats, List(bandFrames) { -30f }, loudness = loud)
        val bass = HueLightScore.build(timeline, null, durationMs, 8_000, LevelSource.BASS)
        val loudness = HueLightScore.build(timeline, null, durationMs, 8_000)

        assertEquals(loudness.keyframes.map { it.level to it.kind }, bass.keyframes.map { it.level to it.kind })
        assertEquals(0.30, bass.levelFloor)
        assertTrue(bass.keyframes.all { it.floor == 0.30 }, bass.keyframes.take(3).toString())
    }

    @Test
    fun `the sub band moves the floor`() {
        val beats = beats(beatCount)
        val sub = List(bandFrames) { if (it < bandFrames / 2) -50f else -10f }
        val timeline = bandTimeline(beats, kickBand { 20f }, sub)
        val score = HueLightScore.build(timeline, null, durationMs, 8_000, LevelSource.BASS)

        (4..100).forEach { assertEquals(0.30, score.keyframes[it].floor, 0.03, "beat $it") }
        (130..239).forEach { assertEquals(0.55, score.keyframes[it].floor, 0.03, "beat $it") }
        val loudness = HueLightScore.build(timeline, null, durationMs, 8_000)
        assertTrue(loudness.keyframes.all { it.floor == 0.55 }, loudness.keyframes.take(3).toString())
    }

    @Test
    fun `downbeat phase follows the strongest kick onset`() {
        val beats = beats(beatCount)
        val kick = kickBand { if (it % 4 == 1) 25f else 10f }
        val score = HueLightScore.build(bandTimeline(beats, kick), null, durationMs, 8_000, LevelSource.BASS)

        assertEquals(1, score.downbeatPhase)
        val downbeats = score.keyframes.filter { it.kind != KeyframeKind.BEAT }
        assertTrue(downbeats.all { it.index % 4 == 1 }, downbeats.take(5).toString())
    }

    @Test
    fun `sections still come from the loudness envelope in bass mode`() {
        val beats = beats(beatCount)
        val stepped = envelope(durationMs) { ms ->
            val base = if (ms < 60_000) -45f else -12f
            val beatIndex = (ms / beatMs).toInt()
            if (beatIndex % 4 == 0 && ms % beatMs < 200) base + 6f else base
        }
        val kick = kickBand { if (it % 4 == 0) 20f else 10f }
        val score = HueLightScore.build(bandTimeline(beats, kick, loudness = stepped), null, durationMs, 8_000, LevelSource.BASS)

        val sections = score.keyframes.filter { it.kind == KeyframeKind.SECTION }
        assertEquals(1, sections.size, sections.toString())
        assertEquals(60_000, sections.single().atMs)
    }

    @Test
    fun `legacy bass envelopes without bands keep the peak path`() {
        val beats = beats(beatCount)
        val spikes = envelope(durationMs) { ms -> if (ms % beatMs < 100) -6f else -60f }
        val bass = HueLightScore.build(timeline(beats, spikes, spikes), null, durationMs, 8_000, LevelSource.BASS)
        val loudness = HueLightScore.build(timeline(beats, spikes), null, durationMs, 8_000)

        assertEquals(1.0, bass.keyframes[8].level, 0.01)
        assertTrue(loudness.keyframes[8].level < 0.4, "loudness ${loudness.keyframes[8].level}")
        assertTrue(bass.keyframes.all { it.level > 0.9 })
        assertTrue(bass.keyframes.all { it.floor == 0.30 }, bass.keyframes.take(3).toString())
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
