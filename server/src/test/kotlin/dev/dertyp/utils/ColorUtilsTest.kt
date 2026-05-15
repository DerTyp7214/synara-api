package dev.dertyp.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorUtilsTest {

    @Test
    fun `rgbToHsl should convert red correctly`() {
        val (h, s, l) = ColorUtils.rgbToHsl(255, 0, 0)
        assertEquals(0.0, h, 0.01)
        assertEquals(100.0, s, 0.01)
        assertEquals(50.0, l, 0.01)
    }

    @Test
    fun `rgbToHsl should convert green correctly`() {
        val (h, s, l) = ColorUtils.rgbToHsl(0, 255, 0)
        assertEquals(120.0, h, 0.01)
        assertEquals(100.0, s, 0.01)
        assertEquals(50.0, l, 0.01)
    }

    @Test
    fun `rgbToHsl should convert blue correctly`() {
        val (h, s, l) = ColorUtils.rgbToHsl(0, 0, 255)
        assertEquals(240.0, h, 0.01)
        assertEquals(100.0, s, 0.01)
        assertEquals(50.0, l, 0.01)
    }

    @Test
    fun `rgbToLab should convert white correctly`() {
        val (l, a, b) = ColorUtils.rgbToLab(255, 255, 255)
        assertEquals(100.0, l, 0.1)
        assertEquals(0.0, a, 0.1)
        assertEquals(0.0, b, 0.1)
    }

    @Test
    fun `rgbToLab should convert black correctly`() {
        val (l, a, b) = ColorUtils.rgbToLab(0, 0, 0)
        assertEquals(0.0, l, 0.1)
        assertEquals(0.0, a, 0.1)
        assertEquals(0.0, b, 0.1)
    }

    @Test
    fun `rgbToLab should convert red correctly`() {
        val (l, a, _) = ColorUtils.rgbToLab(255, 0, 0)
        assertTrue(l > 0)
        assertTrue(a > 0)
    }
}
