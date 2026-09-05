package dev.dertyp.services.hue

import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.SongAudioData
import dev.dertyp.utils.ColorUtils
import dev.dertyp.utils.HueColor
import kotlin.math.abs
import kotlin.math.roundToInt

object HuePaletteMapper {
    data class Result(val commands: List<HueCommand>, val colors: List<Int>)

    private data class Candidate(val argb: Int, val hue: Double, val saturation: Double, val lightness: Double)

    val TEST_COLORS = listOf(0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFFFFCC00.toInt(), 0xFFAF52DE.toInt())

    fun map(
        palette: List<Int>,
        primary: Int?,
        audio: SongAudioData?,
        link: HueUserLink,
        gamuts: Map<String, HueColor.Gamut> = emptyMap(),
    ): Result {
        if (link.targets.isEmpty()) return Result(emptyList(), emptyList())
        val energy = audio?.energy ?: SongAudioData.DEFAULT_ENERGY
        val colors = pickColors(listOfNotNull(primary) + palette, energy, audio?.valence ?: SongAudioData.DEFAULT_VALENCE)
        val brightness = brightness(link.intensity, energy, audio?.loudness)
        val transition = transition(link, audio?.bpm)
        return assign(colors, link.targets, brightness, transition, gamuts)
    }

    fun test(targets: List<HueTarget>, gamuts: Map<String, HueColor.Gamut> = emptyMap()): Result =
        assign(TEST_COLORS, targets, 80, 300, gamuts)

    fun stop(link: HueUserLink): List<HueCommand> = when (link.onStop) {
        HueStopMode.OFF -> link.targets.map { HueCommand(it, LightUpdate(on = ClipOn(false), dynamics = ClipDynamics(link.transitionMs))) }
        HueStopMode.KEEP, HueStopMode.RESTORE -> emptyList()
    }

    internal fun pickColors(candidates: List<Int>, energy: Double, valence: Double): List<Int> {
        val parsed = candidates.distinct().map { argb ->
            val (r, g, b) = HueColor.argbToRgb(argb)
            val (h, s, l) = ColorUtils.rgbToHsl(r, g, b)
            Candidate(argb, h, s / 100.0, l / 100.0)
        }
        val minSaturation = if (energy < 0.35) 0.12 else 0.25
        var vivid = parsed.filter { it.saturation >= minSaturation && it.lightness in 0.15..0.85 }
        if (vivid.isEmpty()) vivid = listOfNotNull(parsed.maxByOrNull { it.saturation }?.takeIf { it.saturation >= 0.08 })
        if (vivid.isEmpty()) {
            val fallback = java.awt.Color.getHSBColor(if (valence >= 0.5) 30f / 360f else 220f / 360f, 0.6f, 0.6f).rgb
            return listOf(fallback)
        }
        val sorted = vivid.sortedByDescending { it.saturation * (1 - abs(it.lightness - 0.5)) }
        val result = ArrayList<Candidate>()
        for (candidate in sorted) {
            if (result.none { hueDistance(it.hue, candidate.hue) < 20 }) result += candidate
        }
        return result.map { it.argb }
    }

    internal fun brightness(intensity: HueIntensity, energy: Double, loudness: Double?): Int {
        val base = when (intensity) {
            HueIntensity.LOW -> 35.0
            HueIntensity.MEDIUM -> 60.0
            HueIntensity.HIGH -> 90.0
        }
        val loudnessFactor = loudness?.let { 0.8 + 0.2 * ((it + 30) / 25).coerceIn(0.0, 1.0) } ?: 1.0
        return (base * (0.6 + 0.4 * energy.coerceIn(0.0, 1.0)) * loudnessFactor).roundToInt().coerceIn(1, 100)
    }

    internal fun transition(link: HueUserLink, bpm: Double?): Int = when (link.transitionMode) {
        HueTransitionMode.FIXED -> link.transitionMs.coerceIn(0, 10_000)
        HueTransitionMode.BPM -> bpm?.takeIf { it > 0 }?.let { (60_000 / it).roundToInt().coerceIn(200, 1500) } ?: link.transitionMs.coerceIn(0, 10_000)
    }

    private fun assign(colors: List<Int>, targets: List<HueTarget>, brightness: Int, transition: Int, gamuts: Map<String, HueColor.Gamut>): Result {
        if (colors.isEmpty() || targets.isEmpty()) return Result(emptyList(), emptyList())
        val lights = targets.filter { it.type == HueTargetType.LIGHT }.sortedBy { it.name }
        val groups = targets.filter { it.type != HueTargetType.LIGHT }
        val commands = ArrayList<HueCommand>()
        val applied = ArrayList<Int>()
        groups.forEach { target ->
            commands += command(target, colors[0], brightness, transition, gamuts)
            applied += colors[0]
        }
        lights.forEachIndexed { index, target ->
            val color = colors[index % colors.size]
            commands += command(target, color, brightness, transition, gamuts)
            applied += color
        }
        return Result(commands, applied)
    }

    private fun command(target: HueTarget, argb: Int, brightness: Int, transition: Int, gamuts: Map<String, HueColor.Gamut>): HueCommand {
        val xy = HueColor.argbToXy(argb, gamuts[target.id] ?: HueColor.GAMUT_C)
        return HueCommand(
            target,
            LightUpdate(
                on = ClipOn(true),
                dimming = ClipDimming(brightness.toDouble()),
                color = ClipColorUpdate(ClipXy(xy.x, xy.y)),
                dynamics = ClipDynamics(transition),
            ),
        )
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360
        return if (diff > 180) 360 - diff else diff
    }
}
