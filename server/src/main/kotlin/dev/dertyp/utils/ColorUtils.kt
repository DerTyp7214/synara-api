package dev.dertyp.utils

import kotlin.math.pow

object ColorUtils {
    fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val l = (max + min) / 2.0
        var h: Double
        var s: Double

        if (max == min) {
            h = 0.0
            s = 0.0
        } else {
            val d = max - min
            s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
            h = when (max) {
                rf -> (gf - bf) / d + (if (gf < bf) 6.0 else 0.0)
                gf -> (bf - rf) / d + 2.0
                else -> (rf - gf) / d + 4.0
            }
            h /= 6.0
        }
        return Triple(h * 360.0, s * 100.0, l * 100.0)
    }

    fun rgbToLab(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        var rf = r / 255.0
        var gf = g / 255.0
        var bf = b / 255.0

        rf = if (rf > 0.04045) ((rf + 0.055) / 1.055).pow(2.4) else rf / 12.92
        gf = if (gf > 0.04045) ((gf + 0.055) / 1.055).pow(2.4) else gf / 12.92
        bf = if (bf > 0.04045) ((bf + 0.055) / 1.055).pow(2.4) else bf / 12.92

        rf *= 100.0
        gf *= 100.0
        bf *= 100.0

        val x = rf * 0.4124 + gf * 0.3576 + bf * 0.1805
        val y = rf * 0.2126 + gf * 0.7152 + bf * 0.0722
        val z = rf * 0.0193 + gf * 0.1192 + bf * 0.9505

        var xf = x / 95.047
        var yf = y / 100.000
        var zf = z / 108.883

        xf = if (xf > 0.008856) xf.pow(1.0 / 3.0) else (7.787 * xf) + (16.0 / 116.0)
        yf = if (yf > 0.008856) yf.pow(1.0 / 3.0) else (7.787 * yf) + (16.0 / 116.0)
        zf = if (zf > 0.008856) zf.pow(1.0 / 3.0) else (7.787 * zf) + (16.0 / 116.0)

        val labL = (116.0 * yf) - 16.0
        val labA = 500.0 * (xf - yf)
        val labB = 200.0 * (yf - zf)

        return Triple(labL, labA, labB)
    }
}
