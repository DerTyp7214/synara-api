package dev.dertyp.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class HueColorTest {
    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 0.02) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    @Test
    fun `primaries map close to the gamut corners`() {
        val red = HueColor.rgbToXy(255, 0, 0)
        assertClose(HueColor.GAMUT_C.red.x, red.x)
        assertClose(HueColor.GAMUT_C.red.y, red.y)
        val green = HueColor.rgbToXy(0, 255, 0)
        assertClose(HueColor.GAMUT_C.green.x, green.x, 0.05)
        assertClose(HueColor.GAMUT_C.green.y, green.y, 0.05)
        val blue = HueColor.rgbToXy(0, 0, 255)
        assertClose(HueColor.GAMUT_C.blue.x, blue.x, 0.05)
        assertClose(HueColor.GAMUT_C.blue.y, blue.y, 0.05)
    }

    @Test
    fun `white maps to d65 and black falls back to d65`() {
        val white = HueColor.rgbToXy(255, 255, 255)
        assertClose(HueColor.D65.x, white.x)
        assertClose(HueColor.D65.y, white.y)
        assertEquals(HueColor.D65, HueColor.rgbToXy(0, 0, 0))
    }

    @Test
    fun `points outside the gamut are clamped onto its edge`() {
        val outside = HueColor.Xy(0.05, 0.9)
        val clamped = HueColor.clampToGamut(outside, HueColor.GAMUT_C)
        assertTrue(HueColor.isInside(clamped, HueColor.GAMUT_C) || abs(clamped.x - outside.x) > 0.0)
        assertTrue(clamped != outside)
        val inside = HueColor.Xy(0.3, 0.3)
        assertEquals(inside, HueColor.clampToGamut(inside, HueColor.GAMUT_C))
    }

    @Test
    fun `argb unpacking`() {
        assertEquals(Triple(0x12, 0x34, 0x56), HueColor.argbToRgb(0xFF123456.toInt()))
    }
}
