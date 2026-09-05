package dev.dertyp.services.hue

import dev.dertyp.data.SongAudioTimeline
import kotlin.math.abs
import kotlin.math.roundToInt

enum class KeyframeKind { BEAT, DOWNBEAT, SECTION }

data class Keyframe(val index: Int, val atMs: Int, val kind: KeyframeKind, val level: Double)

data class LightScore(val keyframes: List<Keyframe>, val beatMs: Int?, val downbeatPhase: Int = 0) {
    val beatsPerSecond: Double get() = beatMs?.takeIf { it > 0 }?.let { 1000.0 / it } ?: 0.0

    fun nextIndexAfter(positionMs: Long, fromIndex: Int, eligible: (Keyframe) -> Boolean): Int {
        val from = fromIndex.coerceIn(0, keyframes.size)
        val insertion = -(keyframes.binarySearch(fromIndex = from) { if (it.atMs <= positionMs) -1 else 1 } + 1)
        return (insertion until keyframes.size).firstOrNull { eligible(keyframes[it]) } ?: -1
    }
}

class NormalizedEnvelope(private val envelopeDb: List<Float>, private val hz: Int) {
    private val low: Float
    private val high: Float
    val usable: Boolean

    init {
        if (envelopeDb.isEmpty() || hz <= 0) {
            low = 0f
            high = 0f
            usable = false
        } else {
            val sorted = envelopeDb.sorted()
            low = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.size - 1)]
            high = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
            usable = high - low > 1f
        }
    }

    fun level(fromMs: Long, toMs: Long): Double {
        if (!usable) return 1.0
        val start = (fromMs * hz / 1000).toInt().coerceIn(0, envelopeDb.size - 1)
        val end = (toMs * hz / 1000).toInt().coerceIn(start + 1, envelopeDb.size)
        val average = envelopeDb.subList(start, end).average()
        return ((average - low) / (high - low)).coerceIn(0.0, 1.0)
    }
}

object HueLightScore {
    private const val BEATS_PER_BAR = 4
    private const val BEAT_WINDOW_MS = 100L
    private const val SECTION_BARS = 8
    private const val SECTION_THRESHOLD = 0.15

    fun build(timeline: SongAudioTimeline?, bpm: Double?, durationMs: Long, fallbackIntervalMs: Long): LightScore {
        val envelope = timeline?.let { NormalizedEnvelope(it.envelopeDb, it.envelopeHz) }
        val beats = timeline?.beatsMs?.takeIf { it.isNotEmpty() }
            ?: HuePaletteMapper.beatMs(bpm)?.takeIf { durationMs > 0 }?.let { grid(it, durationMs) }
        if (beats == null) {
            val end = if (durationMs > 0) durationMs else fallbackIntervalMs * 64
            return LightScore(grid(fallbackIntervalMs.toInt(), end).mapIndexed { index, at -> Keyframe(index, at, KeyframeKind.DOWNBEAT, 1.0) }, null)
        }
        val beatMs = if (beats.size > 1) ((beats.last() - beats.first()).toDouble() / (beats.size - 1)).roundToInt() else null
        val beatLevels = beats.map { envelope?.level(it - BEAT_WINDOW_MS, it + BEAT_WINDOW_MS) ?: 1.0 }
        val phase = downbeatPhase(beatLevels, envelope?.usable == true)
        val kinds = Array(beats.size) { index -> if ((index - phase).mod(BEATS_PER_BAR) == 0) KeyframeKind.DOWNBEAT else KeyframeKind.BEAT }
        markSections(kinds, beatLevels, envelope?.usable == true)
        val keyframes = beats.mapIndexed { index, at ->
            val nextAt = beats.getOrNull(index + 1)?.toLong() ?: (if (durationMs > at) durationMs else at + (beatMs ?: 500).toLong())
            Keyframe(index, at, kinds[index], envelope?.level(at.toLong(), nextAt) ?: 1.0)
        }
        return LightScore(keyframes, beatMs, phase)
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
