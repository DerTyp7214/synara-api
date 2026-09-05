package dev.dertyp.services.cover

import dev.dertyp.utils.ColorUtils
import java.awt.Color

object CoverTagDeriver {
    fun tags(context: CoverContext): Set<String> {
        val tags = LinkedHashSet<String>()
        context.genres.entries.sortedByDescending { it.value }.take(3).forEach { tags += it.key }
        context.moods.entries.maxByOrNull { it.value }?.let { tags += "mood:${it.key}" }
        context.energy?.let {
            tags += when {
                it < 0.35 -> "energy:low"
                it < 0.65 -> "energy:mid"
                else -> "energy:high"
            }
        }
        context.valence?.let {
            tags += when {
                it < 0.35 -> "valence:sad"
                it < 0.65 -> "valence:neutral"
                else -> "valence:happy"
            }
        }
        context.bpm?.let {
            tags += when {
                it < 95 -> "tempo:slow"
                it < 130 -> "tempo:mid"
                else -> "tempo:fast"
            }
        }
        if (context.explicitRatio > 0.5) tags += "explicit"
        if (context.palette.isNotEmpty()) {
            var lightness = 0.0
            var warm = 0
            for (argb in context.palette) {
                val color = Color(argb)
                val (hue, _, l) = ColorUtils.rgbToHsl(color.red, color.green, color.blue)
                lightness += l / 100.0
                if (hue < 70 || hue >= 300) warm++
            }
            tags += if (lightness / context.palette.size < 0.45) "palette:dark" else "palette:light"
            tags += if (warm * 2 >= context.palette.size) "palette:warm" else "palette:cool"
        }
        return tags
    }
}
