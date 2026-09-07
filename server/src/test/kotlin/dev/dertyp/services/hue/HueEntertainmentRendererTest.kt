package dev.dertyp.services.hue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HueEntertainmentRendererTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun `the palette is handed out round robin`() {
        val renderer = HueEntertainmentRenderer(listOf(1, 2, 3), 0.5, 100)

        renderer.setPalette(listOf(red, green), 0, 0, 0)

        assertEquals(
            listOf(
                HueChannelColor(1, 65_535, 0, 0),
                HueChannelColor(2, 0, 65_535, 0),
                HueChannelColor(3, 65_535, 0, 0),
            ),
            renderer.tick(0),
        )
        assertEquals(listOf(red, green, red), renderer.currentColors())
    }

    @Test
    fun `a step rotates the palette across the channels`() {
        val renderer = HueEntertainmentRenderer(listOf(1, 2, 3), 0.5, 100)

        renderer.setPalette(listOf(red, green), 1, 0, 0)

        assertEquals(listOf(green, red, green), renderer.currentColors())
        assertEquals(listOf(65_535, 0, 65_535), renderer.tick(0).map { it.g })
    }

    @Test
    fun `an empty palette leaves the channels alone`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(red), 0, 0, 0)

        renderer.setPalette(emptyList(), 0, 100, 0)

        assertEquals(listOf(red), renderer.currentColors())
        assertEquals(listOf(HueChannelColor(1, 65_535, 0, 0)), renderer.tick(100))
    }

    @Test
    fun `a crossfade is linear between the previous and the new color`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(black), 0, 0, 0)

        renderer.setPalette(listOf(white), 0, 0, 1_000)

        assertEquals(0, renderer.tick(0).single().r)
        assertEquals(32_896, renderer.tick(500).single().r)
        assertEquals(65_535, renderer.tick(1_000).single().r)
        assertEquals(65_535, renderer.tick(5_000).single().r)
    }

    @Test
    fun `a fade started mid fade continues from the interpolated color`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(black), 0, 0, 0)
        renderer.setPalette(listOf(white), 0, 0, 1_000)

        renderer.setPalette(listOf(black), 0, 500, 1_000)

        assertEquals(32_896, renderer.tick(500).single().r)
        assertEquals(0, renderer.tick(1_500).single().r)
    }

    @Test
    fun `without a pulse the channels stay at the base brightness`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 50)
        renderer.setPalette(listOf(white), 0, 0, 0)

        assertEquals(32_896, renderer.tick(0).single().r)

        renderer.setBaseBrightness(100)

        assertEquals(65_535, renderer.tick(0).single().r)
    }

    @Test
    fun `a pulse attacks to the level factor and decays to the floor`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(white), 0, 0, 0)

        renderer.pulse(1.0, 0, 200)

        assertEquals(65_535, renderer.tick(40).single().r)
        assertEquals(49_087, renderer.tick(140).single().r)
        assertEquals(32_896, renderer.tick(240).single().r)
        assertEquals(32_896, renderer.tick(5_000).single().r)
    }

    @Test
    fun `a pulse decays to the floor passed with it`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(white), 0, 0, 0)

        renderer.pulse(1.0, 0, 200, 0.75)

        assertEquals(65_535, renderer.tick(40).single().r)
        assertEquals(57_311, renderer.tick(140).single().r)
        assertEquals(49_087, renderer.tick(240).single().r)
        assertEquals(49_087, renderer.tick(5_000).single().r)
    }

    @Test
    fun `a following pulse attacks from the current factor`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(white), 0, 0, 0)
        renderer.pulse(1.0, 0, 200)

        renderer.pulse(1.0, 1_000, 200)

        assertEquals(32_896, renderer.tick(1_000).single().r)
        assertEquals(49_087, renderer.tick(1_020).single().r)
        assertEquals(65_535, renderer.tick(1_040).single().r)
    }

    @Test
    fun `the decay is clamped to the supported range`() {
        val renderer = HueEntertainmentRenderer(listOf(1), 0.5, 100)
        renderer.setPalette(listOf(white), 0, 0, 0)

        renderer.pulse(1.0, 0, 0)

        assertEquals(32_896, renderer.tick(HueEntertainmentRenderer.ATTACK_MS + HueEntertainmentRenderer.MIN_DECAY_MS.toLong()).single().r)
    }
}
