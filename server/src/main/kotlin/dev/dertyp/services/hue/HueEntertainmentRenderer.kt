package dev.dertyp.services.hue

import dev.dertyp.utils.HueColor
import kotlin.math.roundToInt

class HueEntertainmentRenderer(
    private val channelIds: List<Int>,
    private val levelFloor: Double,
    initialBrightness: Int,
) {
    private val targets = IntArray(channelIds.size) { BLACK }
    private val from = Array(channelIds.size) { doubleArrayOf(0.0, 0.0, 0.0) }
    private val to = Array(channelIds.size) { doubleArrayOf(0.0, 0.0, 0.0) }
    private var fadeStartMs = 0L
    private var fadeMs = 0
    private var base = initialBrightness.coerceIn(0, 100)
    private var pulsed = false
    private var pulseStartMs = 0L
    private var pulseFrom = 1.0
    private var pulsePeak = 1.0
    private var pulseDecayMs = MIN_DECAY_MS

    fun setPalette(colors: List<Int>, step: Int, nowMs: Long, fadeMs: Int) {
        if (colors.isEmpty()) return
        val progress = fadeProgress(nowMs)
        channelIds.indices.forEach { index ->
            val current = from[index]
            val target = to[index]
            repeat(3) { component ->
                current[component] = current[component] + (target[component] - current[component]) * progress
            }
            val argb = colors[(index + step).mod(colors.size)]
            targets[index] = argb
            val (r, g, b) = HueColor.argbToRgb(argb)
            target[0] = r.toDouble()
            target[1] = g.toDouble()
            target[2] = b.toDouble()
        }
        fadeStartMs = nowMs
        this.fadeMs = fadeMs.coerceAtLeast(0)
    }

    fun setBaseBrightness(percent: Int) {
        base = percent.coerceIn(0, 100)
    }

    fun pulse(level: Double, nowMs: Long, decayMs: Int) {
        pulseFrom = pulseFactor(nowMs)
        pulsePeak = HuePaletteMapper.levelFactor(level, levelFloor)
        pulseDecayMs = decayMs.coerceIn(MIN_DECAY_MS, MAX_DECAY_MS)
        pulseStartMs = nowMs
        pulsed = true
    }

    fun tick(nowMs: Long): List<HueChannelColor> {
        val progress = fadeProgress(nowMs)
        val factor = pulseFactor(nowMs) * base / 100.0
        return channelIds.mapIndexed { index, channel ->
            val current = from[index]
            val target = to[index]
            HueChannelColor(
                channel = channel,
                r = level(current[0], target[0], progress, factor),
                g = level(current[1], target[1], progress, factor),
                b = level(current[2], target[2], progress, factor),
            )
        }
    }

    fun currentColors(): List<Int> = targets.toList()

    private fun level(start: Double, end: Double, progress: Double, factor: Double): Int =
        HueStreamFrame.rgb8To16(((start + (end - start) * progress) * factor).roundToInt())

    private fun fadeProgress(nowMs: Long): Double =
        if (fadeMs <= 0) 1.0 else ((nowMs - fadeStartMs).toDouble() / fadeMs).coerceIn(0.0, 1.0)

    private fun pulseFactor(nowMs: Long): Double {
        if (!pulsed) return 1.0
        val elapsed = (nowMs - pulseStartMs).toDouble()
        if (elapsed <= 0) return pulseFrom
        if (elapsed < ATTACK_MS) return pulseFrom + (pulsePeak - pulseFrom) * (elapsed / ATTACK_MS)
        val decayed = elapsed - ATTACK_MS
        if (decayed >= pulseDecayMs) return levelFloor
        return pulsePeak + (levelFloor - pulsePeak) * (decayed / pulseDecayMs)
    }

    companion object {
        const val ATTACK_MS = 40
        const val MIN_DECAY_MS = 120
        const val MAX_DECAY_MS = 2_000
        private val BLACK = 0xFF000000.toInt()
    }
}
