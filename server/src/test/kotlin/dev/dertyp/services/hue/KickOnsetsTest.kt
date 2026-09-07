package dev.dertyp.services.hue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KickOnsetsTest {
    private val hz = 50

    @Test
    fun `an onset is the rise above the quietest of the previous frames`() {
        val onsets = KickOnsets(listOf(-40f, -40f, -40f, -25f, -15f, -15f), hz)

        assertEquals(25.0, onsets.rise(80, 80), 0.01)
        assertEquals(15.0, onsets.rise(60, 60), 0.01)
        assertEquals(0.0, onsets.rise(0, 40), 0.01)
    }

    @Test
    fun `a level held for the whole lag stops producing onsets`() {
        val onsets = KickOnsets(listOf(-40f, -40f, -40f, -25f, -15f, -15f, -15f, -15f), hz)

        assertEquals(25.0, onsets.rise(80, 80), 0.01)
        assertEquals(25.0, onsets.rise(100, 100), 0.01)
        assertEquals(10.0, onsets.rise(120, 120), 0.01)
        assertEquals(0.0, onsets.rise(140, 140), 0.01)
    }

    @Test
    fun `silence below the noise floor does not create an onset`() {
        val onsets = KickOnsets(listOf(-70f, -70f, -70f, -55f), hz)

        assertEquals(5.0, onsets.rise(60, 60), 0.01)
    }

    @Test
    fun `windows are inclusive and clamped to the track`() {
        val levels = listOf(-40f, -40f, -40f, -25f, -15f, -15f)
        val onsets = KickOnsets(levels, hz)
        val global = levels.indices.maxOf { onsets.rise(it * 20L, it * 20L) }

        assertEquals(global, onsets.rise(-100, 100_000), 0.01)
        assertEquals(25.0, global, 0.01)
    }

    @Test
    fun `peakDb returns the loudest raw level in the window`() {
        val onsets = KickOnsets(listOf(-70f, -70f, -70f, -55f), hz)

        assertEquals(-55f, onsets.peakDb(-100, 100_000))
        assertEquals(-70f, onsets.peakDb(0, 40))
    }

    @Test
    fun `an empty band is not usable`() {
        val onsets = KickOnsets(emptyList(), hz)

        assertFalse(onsets.usable)
        assertEquals(0.0, onsets.rise(0, 10_000), 0.01)
    }
}
