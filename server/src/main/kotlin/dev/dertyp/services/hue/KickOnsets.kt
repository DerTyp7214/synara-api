package dev.dertyp.services.hue

import kotlin.math.max
import kotlin.math.roundToInt

class KickOnsets(levelsDb: List<Float>, private val hz: Int) {
    val usable: Boolean = levelsDb.isNotEmpty() && hz > 0
    private val raw: FloatArray
    private val onset: DoubleArray

    init {
        if (usable) {
            raw = FloatArray(levelsDb.size) { levelsDb[it] }
            val clamped = FloatArray(levelsDb.size) { max(levelsDb[it], HueLightScore.ONSET_NOISE_FLOOR_DB) }
            val lag = (HueLightScore.ONSET_LAG_MS * hz / 1000.0).roundToInt().coerceAtLeast(1)
            onset = DoubleArray(clamped.size)
            for (index in 1 until clamped.size) {
                var floor = Float.MAX_VALUE
                for (previous in (index - lag).coerceAtLeast(0) until index) {
                    if (clamped[previous] < floor) floor = clamped[previous]
                }
                onset[index] = max(0.0, (clamped[index] - floor).toDouble())
            }
        } else {
            raw = FloatArray(0)
            onset = DoubleArray(0)
        }
    }

    fun rise(fromMs: Long, toMs: Long): Double {
        if (!usable) return 0.0
        val start = frame(fromMs)
        val end = frame(toMs).coerceAtLeast(start)
        var value = 0.0
        for (index in start..end) if (onset[index] > value) value = onset[index]
        return value
    }

    fun peakDb(fromMs: Long, toMs: Long): Float {
        if (!usable) return HueLightScore.ONSET_NOISE_FLOOR_DB
        val start = frame(fromMs)
        val end = frame(toMs).coerceAtLeast(start)
        var value = raw[start]
        for (index in start + 1..end) if (raw[index] > value) value = raw[index]
        return value
    }

    private fun frame(ms: Long): Int = (ms * hz / 1000.0).roundToInt().coerceIn(0, raw.size - 1)
}
