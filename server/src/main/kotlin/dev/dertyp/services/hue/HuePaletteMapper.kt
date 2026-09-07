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

data class LightProfile(val gamut: HueColor.Gamut? = null, val gradientPoints: Int? = null)

object HuePaletteMapper {
    data class Result(val commands: List<HueCommand>, val colors: List<Int>, val palette: List<Int> = emptyList())

    private data class Candidate(val argb: Int, val hue: Double, val saturation: Double, val lightness: Double)

    private const val BEATS_PER_BAR = 4
    private const val DEFAULT_BAR_MS = 6_000L

    val TEST_COLORS = listOf(0xFFFF3B30.toInt(), 0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFFFFCC00.toInt(), 0xFFAF52DE.toInt())

    fun map(
        palette: List<Int>,
        primary: Int?,
        audio: SongAudioData?,
        link: HueUserLink,
        targets: List<HueTarget> = link.targets,
        profiles: Map<String, LightProfile> = emptyMap(),
    ): Result {
        if (targets.isEmpty()) return Result(emptyList(), emptyList())
        val energy = audio?.energy ?: SongAudioData.DEFAULT_ENERGY
        val colors = pickColors(listOfNotNull(primary) + palette, energy, audio?.valence ?: SongAudioData.DEFAULT_VALENCE)
        val brightness = brightness(link.intensity, energy, audio?.loudness)
        val transition = transition(link, audio?.bpm)
        return assign(colors, targets, brightness, transition, profiles)
    }

    fun test(targets: List<HueTarget>, profiles: Map<String, LightProfile> = emptyMap()): Result =
        assign(TEST_COLORS, targets, 80, 300, profiles)

    fun frame(
        colors: List<Int>,
        targets: List<HueTarget>,
        step: Int,
        brightness: Int,
        transitionMs: Int,
        profiles: Map<String, LightProfile> = emptyMap(),
    ): Result {
        if (colors.isEmpty()) return Result(emptyList(), emptyList())
        val offset = ((step % colors.size) + colors.size) % colors.size
        val rotated = colors.drop(offset) + colors.take(offset)
        return assign(rotated, targets, brightness, transitionMs, profiles)
    }

    fun levelFactor(level: Double, floor: Double = 0.55): Double = floor + (1 - floor) * level.coerceIn(0.0, 1.0)

    fun beatMs(bpm: Double?): Int? = bpm?.takeIf { it > 0 }?.let { (60_000 / it).roundToInt() }?.takeIf { it > 0 }

    fun barMs(bpm: Double?): Long =
        beatMs(bpm)?.let { (it.toLong() * BEATS_PER_BAR).coerceIn(2_000, 10_000) } ?: DEFAULT_BAR_MS

    fun stop(link: HueUserLink): List<HueCommand> = when (link.onStop) {
        HueStopMode.OFF -> link.targets
            .filter { it.type == HueTargetType.LIGHT || it.type == HueTargetType.ROOM || it.type == HueTargetType.ZONE }
            .map { HueLightCommand(it, LightUpdate(on = ClipOn(false), dynamics = ClipDynamics(link.transitionMs))) }
        HueStopMode.SCENE -> link.stopScenes.map { HueSceneCommand(it.id, SceneRecallUpdate(ClipSceneRecall(duration = link.transitionMs))) }
        HueStopMode.KEEP -> emptyList()
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

    private fun assign(colors: List<Int>, targets: List<HueTarget>, brightness: Int, transition: Int, profiles: Map<String, LightProfile>): Result {
        if (colors.isEmpty() || targets.isEmpty()) return Result(emptyList(), emptyList())
        val lights = targets.filter { it.type == HueTargetType.LIGHT }.sortedBy { it.name }
        val groups = targets.filter { it.type == HueTargetType.ROOM || it.type == HueTargetType.ZONE }
        val commands = ArrayList<HueCommand>()
        val applied = ArrayList<Int>()
        groups.forEach { target ->
            commands += command(target, colors, 0, 1, brightness, transition, profiles)
            applied += colors[0]
        }
        lights.forEachIndexed { index, target ->
            val points = profiles[target.id]?.gradientPoints?.coerceAtMost(colors.size)?.takeIf { it >= 2 } ?: 1
            commands += command(target, colors, index, points, brightness, transition, profiles)
            applied += colors[index % colors.size]
        }
        return Result(commands, applied, colors)
    }

    private fun command(
        target: HueTarget,
        colors: List<Int>,
        index: Int,
        points: Int,
        brightness: Int,
        transition: Int,
        profiles: Map<String, LightProfile>,
    ): HueCommand {
        val gamut = profiles[target.id]?.gamut ?: HueColor.GAMUT_C
        val gradient = if (points < 2) null else ClipGradientUpdate(
            List(points) { offset -> ClipGradientPointUpdate(colorUpdate(colors[(index + offset) % colors.size], gamut)) },
        )
        return HueLightCommand(
            target,
            LightUpdate(
                on = ClipOn(true),
                dimming = ClipDimming(brightness.toDouble()),
                color = if (gradient == null) colorUpdate(colors[index % colors.size], gamut) else null,
                gradient = gradient,
                dynamics = ClipDynamics(transition),
            ),
        )
    }

    private fun colorUpdate(argb: Int, gamut: HueColor.Gamut): ClipColorUpdate {
        val xy = HueColor.argbToXy(argb, gamut)
        return ClipColorUpdate(ClipXy(xy.x, xy.y))
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360
        return if (diff > 180) 360 - diff else diff
    }
}
