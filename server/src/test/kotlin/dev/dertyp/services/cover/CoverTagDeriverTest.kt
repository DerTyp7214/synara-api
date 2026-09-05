package dev.dertyp.services.cover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoverTagDeriverTest {
    private fun context(
        genres: Map<String, Int> = emptyMap(),
        moods: Map<String, Int> = emptyMap(),
        energy: Double? = null,
        valence: Double? = null,
        bpm: Double? = null,
        explicitRatio: Double = 0.0,
        palette: List<Int> = emptyList(),
    ) = CoverContext("t", 1, emptyList(), palette, genres, moods, energy, valence, bpm, explicitRatio)

    @Test
    fun `top genres, mood and buckets are derived`() {
        val tags = CoverTagDeriver.tags(
            context(
                genres = mapOf("rock" to 5, "metal" to 3, "pop" to 1, "jazz" to 1),
                moods = mapOf("aggressive" to 4, "calm" to 1),
                energy = 0.9,
                valence = 0.2,
                bpm = 150.0,
                explicitRatio = 0.7,
            )
        )
        assertTrue(tags.containsAll(listOf("rock", "metal", "mood:aggressive", "energy:high", "valence:sad", "tempo:fast", "explicit")))
        assertFalse(tags.contains("pop") && tags.contains("jazz"))
        assertFalse(tags.any { it.startsWith("palette:") })
    }

    @Test
    fun `palette tags describe lightness and warmth`() {
        val dark = CoverTagDeriver.tags(context(palette = listOf(0xFF200A05.toInt(), 0xFF301010.toInt())))
        assertTrue(dark.contains("palette:dark"))
        assertTrue(dark.contains("palette:warm"))
        val light = CoverTagDeriver.tags(context(palette = listOf(0xFFCCE5FF.toInt(), 0xFFE0F0FF.toInt())))
        assertTrue(light.contains("palette:light"))
        assertTrue(light.contains("palette:cool"))
    }

    @Test
    fun `missing data yields no buckets`() {
        assertEquals(emptySet<String>(), CoverTagDeriver.tags(context()))
        assertEquals(setOf("energy:mid", "valence:neutral", "tempo:mid"), CoverTagDeriver.tags(context(energy = 0.5, valence = 0.5, bpm = 110.0)))
    }
}
