package dev.dertyp.utils

import kotlin.math.pow

object HueColor {
    data class Xy(val x: Double, val y: Double)

    data class Gamut(val red: Xy, val green: Xy, val blue: Xy)

    val GAMUT_A = Gamut(Xy(0.704, 0.296), Xy(0.2151, 0.7106), Xy(0.138, 0.08))
    val GAMUT_B = Gamut(Xy(0.675, 0.322), Xy(0.409, 0.518), Xy(0.167, 0.04))
    val GAMUT_C = Gamut(Xy(0.6915, 0.3083), Xy(0.17, 0.7), Xy(0.1532, 0.0475))
    val D65 = Xy(0.3127, 0.3290)

    fun argbToRgb(argb: Int): Triple<Int, Int, Int> =
        Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    fun rgbToXy(r: Int, g: Int, b: Int, gamut: Gamut = GAMUT_C): Xy {
        val red = expand(r / 255.0)
        val green = expand(g / 255.0)
        val blue = expand(b / 255.0)
        val x = red * 0.664511 + green * 0.154324 + blue * 0.162028
        val y = red * 0.283881 + green * 0.668433 + blue * 0.047685
        val z = red * 0.000088 + green * 0.072310 + blue * 0.986039
        val sum = x + y + z
        if (sum <= 0.0) return clampToGamut(D65, gamut)
        return clampToGamut(Xy(x / sum, y / sum), gamut)
    }

    fun argbToXy(argb: Int, gamut: Gamut = GAMUT_C): Xy {
        val (r, g, b) = argbToRgb(argb)
        return rgbToXy(r, g, b, gamut)
    }

    fun clampToGamut(point: Xy, gamut: Gamut): Xy {
        if (isInside(point, gamut)) return point
        val candidates = listOf(
            closestOnSegment(gamut.red, gamut.green, point),
            closestOnSegment(gamut.green, gamut.blue, point),
            closestOnSegment(gamut.blue, gamut.red, point),
        )
        return candidates.minBy { distanceSquared(it, point) }
    }

    fun isInside(p: Xy, gamut: Gamut): Boolean {
        val d1 = sign(p, gamut.red, gamut.green)
        val d2 = sign(p, gamut.green, gamut.blue)
        val d3 = sign(p, gamut.blue, gamut.red)
        val hasNegative = d1 < 0 || d2 < 0 || d3 < 0
        val hasPositive = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNegative && hasPositive)
    }

    private fun sign(p: Xy, a: Xy, b: Xy): Double =
        (p.x - b.x) * (a.y - b.y) - (a.x - b.x) * (p.y - b.y)

    private fun closestOnSegment(a: Xy, b: Xy, p: Xy): Xy {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lengthSquared = abx * abx + aby * aby
        if (lengthSquared == 0.0) return a
        val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lengthSquared).coerceIn(0.0, 1.0)
        return Xy(a.x + abx * t, a.y + aby * t)
    }

    private fun distanceSquared(a: Xy, b: Xy): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun expand(c: Double): Double =
        if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92
}
