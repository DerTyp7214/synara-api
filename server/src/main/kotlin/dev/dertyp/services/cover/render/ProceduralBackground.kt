package dev.dertyp.services.cover.render

import dev.dertyp.utils.ColorUtils
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

object ProceduralBackground {
    fun draw(g: Graphics2D, size: Int, palette: List<Int>, random: Random) {
        val colors = pickColors(palette, random)
        val angle = random.nextDouble() * Math.PI * 2
        val half = size / 2.0
        val dx = cos(angle) * half
        val dy = sin(angle) * half
        g.paint = GradientPaint(
            (half - dx).toFloat(), (half - dy).toFloat(), colors[0],
            (half + dx).toFloat(), (half + dy).toFloat(), colors[1],
        )
        g.fillRect(0, 0, size, size)

        val highlight = colors[2]
        val hx = (size * (0.2 + random.nextDouble() * 0.6)).toFloat()
        val hy = (size * (0.2 + random.nextDouble() * 0.6)).toFloat()
        g.paint = RadialGradientPaint(
            hx, hy, size * 0.6f,
            floatArrayOf(0f, 1f),
            arrayOf(Color(highlight.red, highlight.green, highlight.blue, 150), Color(highlight.red, highlight.green, highlight.blue, 0)),
        )
        g.fillRect(0, 0, size, size)

        val grainCount = size * size / 40
        repeat(grainCount) {
            val x = random.nextInt(size)
            val y = random.nextInt(size)
            val alpha = 8 + random.nextInt(14)
            g.color = if (random.nextBoolean()) Color(255, 255, 255, alpha) else Color(0, 0, 0, alpha)
            g.fillRect(x, y, 1, 1)
        }
    }

    private fun pickColors(palette: List<Int>, random: Random): List<Color> {
        val vivid = palette.map { Color(it) }.filter { color ->
            val (_, saturation, lightness) = ColorUtils.rgbToHsl(color.red, color.green, color.blue)
            saturation > 12.0 && lightness in 8.0..92.0
        }
        val base = if (vivid.isNotEmpty()) vivid else listOf(fromHue(random.nextDouble() * 360, 0.55, 0.42))
        val first = base[random.nextInt(base.size)]
        val second = base.firstOrNull { it != first } ?: shift(first, 40.0, random)
        val third = base.firstOrNull { it != first && it != second } ?: shift(second, -60.0, random)
        return listOf(darken(first, 0.75), darken(second, 0.6), third)
    }

    private fun shift(color: Color, hueDelta: Double, random: Random): Color {
        val (hue, saturation, lightness) = ColorUtils.rgbToHsl(color.red, color.green, color.blue)
        return fromHue((hue + hueDelta + random.nextDouble() * 20).mod(360.0), (saturation / 100.0 + 0.1).coerceIn(0.2, 1.0), (lightness / 100.0).coerceIn(0.25, 0.7))
    }

    private fun darken(color: Color, factor: Double): Color =
        Color((color.red * factor).toInt(), (color.green * factor).toInt(), (color.blue * factor).toInt())

    private fun fromHue(hue: Double, saturation: Double, lightness: Double): Color =
        Color.getHSBColor((hue / 360.0).toFloat(), saturation.toFloat(), (lightness * 1.2).coerceIn(0.0, 1.0).toFloat())
}
