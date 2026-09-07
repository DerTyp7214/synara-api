package dev.dertyp.services.hue

import dev.dertyp.data.AudioBand
import dev.dertyp.data.SongAudioBand
import dev.dertyp.data.SongAudioTimeline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

enum class KeyframeKind { BEAT, DOWNBEAT, SECTION }

enum class LevelSource { LOUDNESS, BASS }

data class Keyframe(
    val index: Int,
    val atMs: Int,
    val kind: KeyframeKind,
    val level: Double,
    val floor: Double = HueLightScore.LOUDNESS_FLOOR,
)

data class LightScore(
    val keyframes: List<Keyframe>,
    val beatMs: Int?,
    val downbeatPhase: Int = 0,
    val levelFloor: Double = HueLightScore.LOUDNESS_FLOOR,
) {
    val beatsPerSecond: Double get() = beatMs?.takeIf { it > 0 }?.let { 1000.0 / it } ?: 0.0

    fun nextIndexAfter(positionMs: Long, fromIndex: Int, eligible: (Keyframe) -> Boolean): Int {
        val from = fromIndex.coerceIn(0, keyframes.size)
        val insertion = -(keyframes.binarySearch(fromIndex = from) { if (it.atMs <= positionMs) -1 else 1 } + 1)
        return (insertion until keyframes.size).firstOrNull { eligible(keyframes[it]) } ?: -1
    }
}

class NormalizedEnvelope(private val envelopeDb: List<Float>, private val hz: Int) {
    private val low: Float
    val highDb: Float
    val usable: Boolean

    init {
        if (envelopeDb.isEmpty() || hz <= 0) {
            low = 0f
            highDb = 0f
            usable = false
        } else {
            val sorted = envelopeDb.sorted()
            low = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
            highDb = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
            usable = highDb - low > 1f
        }
    }

    fun level(fromMs: Long, toMs: Long): Double {
        if (!usable) return 1.0
        val start = (fromMs * hz / 1000).toInt().coerceIn(0, envelopeDb.size - 1)
        val end = (toMs * hz / 1000).toInt().coerceIn(start + 1, envelopeDb.size)
        val average = envelopeDb.subList(start, end).average()
        return ((average - low) / (highDb - low)).coerceIn(0.0, 1.0)
    }

    fun peak(fromMs: Long, toMs: Long): Double {
        if (!usable) return 1.0
        val start = (fromMs * hz / 1000).toInt().coerceIn(0, envelopeDb.size - 1)
        val end = (toMs * hz / 1000).toInt().coerceIn(start + 1, envelopeDb.size)
        val max = envelopeDb.subList(start, end).max()
        return ((max - low).toDouble() / (highDb - low)).coerceIn(0.0, 1.0)
    }
}

object HueLightScore {
    private const val BEATS_PER_BAR = 4
    private const val BEAT_WINDOW_MS = 100L
    private const val BASS_LEAD_MS = 50L
    private const val BASS_TAIL_MS = 200L
    private const val SECTION_BARS = 8
    private const val SECTION_THRESHOLD = 0.15
    private const val PULSE_LEAD_MS = 40L
    private const val PULSE_TAIL_MS = 120L
    private const val PULSE_WINDOW_BEATS = 32
    private const val SUB_FLOOR_BEATS = 4
    const val ONSET_LAG_MS = 60L
    const val ONSET_NOISE_FLOOR_DB = -60f
    const val MIN_ONSET_REF_DB = 6.0
    const val BASS_GAIN_MIN = 0.45
    const val BASS_RATIO_LOW_DB = -18.0
    const val BASS_RATIO_HIGH_DB = -4.0
    const val SUB_FLOOR_MAX = 0.55
    const val LOUDNESS_FLOOR = 0.55
    const val BASS_FLOOR = 0.30

    fun build(
        timeline: SongAudioTimeline?,
        bpm: Double?,
        durationMs: Long,
        fallbackIntervalMs: Long,
        source: LevelSource = LevelSource.LOUDNESS,
    ): LightScore {
        val floor = if (source == LevelSource.BASS) BASS_FLOOR else LOUDNESS_FLOOR
        val beats = timeline?.beatsMs?.takeIf { it.isNotEmpty() }
            ?: HuePaletteMapper.beatMs(bpm)?.takeIf { durationMs > 0 }?.let { grid(it, durationMs) }
        if (beats == null) {
            val end = if (durationMs > 0) durationMs else fallbackIntervalMs * 64
            val grid = grid(fallbackIntervalMs.toInt(), end)
                .mapIndexed { index, at -> Keyframe(index, at, KeyframeKind.DOWNBEAT, 1.0, floor) }
            return LightScore(grid, null, levelFloor = floor)
        }
        val beatMs = beatMs(beats)
        val kick = timeline?.takeIf { source == LevelSource.BASS && it.bandHz > 0 }
            ?.bands?.firstOrNull { it.band == AudioBand.KICK && it.levelsDb.isNotEmpty() }
        return if (kick != null) {
            bandScore(timeline, kick, beats, beatMs, durationMs)
        } else {
            legacyScore(timeline, source, beats, beatMs, durationMs, floor)
        }
    }

    private fun legacyScore(
        timeline: SongAudioTimeline?,
        source: LevelSource,
        beats: List<Int>,
        beatMs: Int?,
        durationMs: Long,
        floor: Double,
    ): LightScore {
        val bass = timeline?.takeIf { source == LevelSource.BASS }
            ?.let { NormalizedEnvelope(it.bassEnvelopeDb, it.envelopeHz) }
            ?.takeIf { it.usable }
        val envelope = bass ?: timeline?.let { NormalizedEnvelope(it.envelopeDb, it.envelopeHz) }
        return envelopeScore(envelope, bass != null, beats, beatMs, durationMs, floor)
    }

    private fun envelopeScore(
        envelope: NormalizedEnvelope?,
        peaks: Boolean,
        beats: List<Int>,
        beatMs: Int?,
        durationMs: Long,
        floor: Double,
    ): LightScore {
        val usable = envelope?.usable == true
        val beatLevels = beats.map { measure(envelope, peaks, it - BEAT_WINDOW_MS, it + BEAT_WINDOW_MS) }
        val phase = downbeatPhase(beatLevels, usable)
        val kinds = kinds(beats.size, phase)
        markSections(kinds, beatLevels, usable)
        val keyframes = beats.mapIndexed { index, at ->
            val nextAt = nextAt(beats, index, durationMs, beatMs)
            val level = if (peaks) {
                measure(envelope, true, at - BASS_LEAD_MS, minOf(at + BASS_TAIL_MS, nextAt))
            } else {
                measure(envelope, false, at.toLong(), nextAt)
            }
            Keyframe(index, at, kinds[index], level, floor)
        }
        return LightScore(keyframes, beatMs, phase, floor)
    }

    private fun bandScore(
        timeline: SongAudioTimeline,
        kickBand: SongAudioBand,
        beats: List<Int>,
        beatMs: Int?,
        durationMs: Long,
    ): LightScore {
        val loudness = NormalizedEnvelope(timeline.envelopeDb, timeline.envelopeHz)
        val kick = KickOnsets(kickBand.levelsDb, timeline.bandHz)
        val sub = timeline.bands.firstOrNull { it.band == AudioBand.SUB && it.levelsDb.isNotEmpty() }
            ?.let { NormalizedEnvelope(it.levelsDb, timeline.bandHz) }
        val pulses = beats.map { kick.rise((it - PULSE_LEAD_MS).coerceAtLeast(0), it + PULSE_TAIL_MS) }
        if (percentile95(pulses) < MIN_ONSET_REF_DB) {
            return envelopeScore(loudness, false, beats, beatMs, durationMs, BASS_FLOOR)
        }
        val peaks = beats.map { kick.peakDb((it - PULSE_LEAD_MS).coerceAtLeast(0), it + PULSE_TAIL_MS).toDouble() }
        val local = pulses.indices.map { index ->
            val from = (index - PULSE_WINDOW_BEATS).coerceAtLeast(0)
            val reference = max(percentile95(pulses.subList(from, index + 1)), MIN_ONSET_REF_DB)
            (pulses[index] / reference).coerceIn(0.0, 1.0)
        }
        val gain = if (!loudness.usable) 1.0 else {
            val ratio = percentile95(peaks) - loudness.highDb
            val scaled = ((ratio - BASS_RATIO_LOW_DB) / (BASS_RATIO_HIGH_DB - BASS_RATIO_LOW_DB)).coerceIn(0.0, 1.0)
            BASS_GAIN_MIN + (1 - BASS_GAIN_MIN) * scaled
        }
        val subWindowMs = SUB_FLOOR_BEATS.toLong() * (beatMs ?: 500)
        val floors = beats.map { at ->
            val level = if (sub?.usable == true) sub.level((at - subWindowMs).coerceAtLeast(0), at.toLong()) else 0.0
            BASS_FLOOR + (SUB_FLOOR_MAX - BASS_FLOOR) * level
        }
        val phase = downbeatPhase(local, true)
        val kinds = kinds(beats.size, phase)
        val sectionLevels = sectionLevels(loudness, kickBand, timeline.bandHz, beats)
        markSections(kinds, sectionLevels ?: emptyList(), sectionLevels != null)
        val keyframes = beats.mapIndexed { index, at ->
            Keyframe(index, at, kinds[index], local[index] * gain, floors[index])
        }
        return LightScore(keyframes, beatMs, phase, BASS_FLOOR)
    }

    private fun sectionLevels(
        loudness: NormalizedEnvelope,
        kickBand: SongAudioBand,
        bandHz: Int,
        beats: List<Int>,
    ): List<Double>? {
        val envelope = loudness.takeIf { it.usable }
            ?: NormalizedEnvelope(kickBand.levelsDb, bandHz).takeIf { it.usable }
            ?: return null
        return beats.map { measure(envelope, false, it - BEAT_WINDOW_MS, it + BEAT_WINDOW_MS) }
    }

    private fun percentile95(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
    }

    private fun beatMs(beats: List<Int>): Int? =
        if (beats.size > 1) ((beats.last() - beats.first()).toDouble() / (beats.size - 1)).roundToInt() else null

    private fun nextAt(beats: List<Int>, index: Int, durationMs: Long, beatMs: Int?): Long {
        val at = beats[index]
        return beats.getOrNull(index + 1)?.toLong() ?: (if (durationMs > at) durationMs else at + (beatMs ?: 500).toLong())
    }

    private fun kinds(size: Int, phase: Int): Array<KeyframeKind> =
        Array(size) { index -> if ((index - phase).mod(BEATS_PER_BAR) == 0) KeyframeKind.DOWNBEAT else KeyframeKind.BEAT }

    private fun measure(envelope: NormalizedEnvelope?, peak: Boolean, fromMs: Long, toMs: Long): Double {
        if (envelope == null) return 1.0
        val from = fromMs.coerceAtLeast(0)
        return if (peak) envelope.peak(from, toMs) else envelope.level(from, toMs)
    }

    private fun grid(intervalMs: Int, endMs: Long): List<Int> =
        (0L until endMs step intervalMs.coerceAtLeast(1).toLong()).map { it.toInt() }

    private fun downbeatPhase(beatLevels: List<Double>, usable: Boolean): Int {
        if (!usable || beatLevels.size < BEATS_PER_BAR) return 0
        return (0 until BEATS_PER_BAR).maxBy { phase ->
            val levels = beatLevels.filterIndexed { index, _ -> index % BEATS_PER_BAR == phase }
            if (levels.isEmpty()) 0.0 else levels.average()
        }
    }

    private fun markSections(kinds: Array<KeyframeKind>, beatLevels: List<Double>, usable: Boolean) {
        if (!usable) return
        val window = SECTION_BARS * BEATS_PER_BAR
        if (beatLevels.size < window * 2) return
        var sectionStart = 0
        for (index in window until beatLevels.size - window) {
            if (kinds[index] != KeyframeKind.DOWNBEAT) continue
            if (index - sectionStart < window) continue
            val before = beatLevels.subList(index - window, index).average()
            val after = beatLevels.subList(index, index + window).average()
            val firstBar = beatLevels.subList(index, index + BEATS_PER_BAR).average()
            if (abs(after - before) > SECTION_THRESHOLD && abs(firstBar - before) > SECTION_THRESHOLD) {
                kinds[index] = KeyframeKind.SECTION
                sectionStart = index
            }
        }
    }
}
