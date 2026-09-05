package dev.dertyp.services.hue

import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.SongAudioData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class HuePaletteMapperTest {
    private val lights = listOf(
        HueTarget(HueTargetType.LIGHT, "l1", "Desk"),
        HueTarget(HueTargetType.LIGHT, "l2", "Shelf"),
        HueTarget(HueTargetType.LIGHT, "l3", "Window"),
    )
    private val room = HueTarget(HueTargetType.ROOM, "r1", "Living", groupedLightId = "g1")
    private fun link(targets: List<HueTarget> = lights, intensity: HueIntensity = HueIntensity.MEDIUM, mode: HueTransitionMode = HueTransitionMode.FIXED, ms: Int = 400, onStop: HueStopMode = HueStopMode.KEEP) =
        HueUserLink(UUID.randomUUID(), true, targets, intensity, mode, ms, onStop)

    private val red = 0xFFE01020.toInt()
    private val blue = 0xFF1030E0.toInt()
    private val grey = 0xFF808080.toInt()
    private val nearBlack = 0xFF101010.toInt()

    @Test
    fun `vivid colors are kept, greys dropped, hues deduplicated`() {
        val colors = HuePaletteMapper.pickColors(listOf(grey, red, 0xFFE81828.toInt(), blue, nearBlack), energy = 0.7, valence = 0.5)
        assertEquals(listOf(red, blue).toSet(), colors.toSet())
    }

    @Test
    fun `grey palette falls back to a valence hue`() {
        val happy = HuePaletteMapper.pickColors(listOf(grey, nearBlack), energy = 0.5, valence = 0.9).single()
        val sad = HuePaletteMapper.pickColors(listOf(grey, nearBlack), energy = 0.5, valence = 0.1).single()
        assertFalse(happy == sad)
        assertTrue(((happy shr 16) and 0xFF) > (happy and 0xFF), "happy fallback is warm")
        assertTrue((sad and 0xFF) > ((sad shr 16) and 0xFF), "sad fallback is cool")
    }

    @Test
    fun `low energy relaxes the saturation threshold`() {
        val muted = 0xFF8A7A70.toInt()
        assertTrue(HuePaletteMapper.pickColors(listOf(muted), energy = 0.2, valence = 0.5).contains(muted))
        assertFalse(HuePaletteMapper.pickColors(listOf(muted, red), energy = 0.8, valence = 0.5).contains(muted))
    }

    @Test
    fun `colors rotate across lights and rooms use the first color via grouped light`() {
        val result = HuePaletteMapper.map(listOf(blue), red, null, link(lights + room))
        assertEquals(4, result.commands.size)
        val grouped = result.commands.single { it.grouped }
        assertEquals("g1", grouped.resourceId)
        val lightColors = result.colors.drop(1)
        assertEquals(listOf(red, blue, red), lightColors)
        assertEquals(red, result.colors.first())
        assertTrue(result.commands.all { it.update.on?.on == true && it.update.color != null })
    }

    @Test
    fun `brightness follows intensity, energy and loudness within bounds`() {
        assertEquals(60, HuePaletteMapper.brightness(HueIntensity.MEDIUM, 1.0, null))
        assertEquals(36, HuePaletteMapper.brightness(HueIntensity.MEDIUM, 0.0, null))
        assertEquals(90, HuePaletteMapper.brightness(HueIntensity.HIGH, 1.0, -5.0))
        assertEquals(72, HuePaletteMapper.brightness(HueIntensity.HIGH, 1.0, -40.0))
        assertTrue(HuePaletteMapper.brightness(HueIntensity.LOW, 0.0, -60.0) >= 1)
    }

    @Test
    fun `transition uses fixed ms or clamps the beat duration`() {
        assertEquals(400, HuePaletteMapper.transition(link(ms = 400), 120.0))
        assertEquals(500, HuePaletteMapper.transition(link(mode = HueTransitionMode.BPM), 120.0))
        assertEquals(1500, HuePaletteMapper.transition(link(mode = HueTransitionMode.BPM), 20.0))
        assertEquals(200, HuePaletteMapper.transition(link(mode = HueTransitionMode.BPM), 400.0))
        assertEquals(400, HuePaletteMapper.transition(link(mode = HueTransitionMode.BPM), null))
    }

    @Test
    fun `stop mode off turns targets off and keep sends nothing`() {
        assertTrue(HuePaletteMapper.stop(link(onStop = HueStopMode.KEEP)).isEmpty())
        val off = HuePaletteMapper.stop(link(onStop = HueStopMode.OFF))
        assertEquals(3, off.size)
        assertTrue(off.all { it.update.on?.on == false })
    }

    @Test
    fun `frames rotate the palette across lights per step`() {
        val palette = listOf(red, blue, grey)
        val step0 = HuePaletteMapper.frame(palette, lights, 0, 50, 1000).colors
        val step1 = HuePaletteMapper.frame(palette, lights, 1, 50, 1000).colors
        val step3 = HuePaletteMapper.frame(palette, lights, 3, 50, 1000).colors
        assertEquals(listOf(red, blue, grey), step0)
        assertEquals(listOf(blue, grey, red), step1)
        assertEquals(step0, step3)
        assertTrue(HuePaletteMapper.frame(palette, lights, 1, 50, 1000).commands.all { it.update.dynamics?.duration == 1000 })
        assertTrue(HuePaletteMapper.frame(emptyList(), lights, 1, 50, 1000).commands.isEmpty())
        assertEquals(listOf(red, blue), HuePaletteMapper.map(listOf(blue), red, null, link()).palette)
    }

    @Test
    fun `the level factor follows loudness between the quiet and loud percentiles`() {
        val envelope = NormalizedEnvelope(List(100) { if (it < 50) -60f else -10f }, 10)
        assertTrue(envelope.usable)
        assertEquals(0.55, HuePaletteMapper.levelFactor(envelope.level(0, 1000)), 0.01)
        assertEquals(1.0, HuePaletteMapper.levelFactor(envelope.level(6000, 7000)), 0.01)
        assertEquals(1.0, HuePaletteMapper.levelFactor(envelope.level(50_000, 51_000)), 0.01)

        val empty = NormalizedEnvelope(emptyList(), 10)
        assertFalse(empty.usable)
        assertEquals(1.0, HuePaletteMapper.levelFactor(empty.level(0, 1000)))
        val flat = NormalizedEnvelope(List(20) { -30f }, 10)
        assertFalse(flat.usable)
        assertEquals(1.0, HuePaletteMapper.levelFactor(flat.level(0, 500)))
        assertEquals(1.0, HuePaletteMapper.levelFactor(NormalizedEnvelope(List(20) { -30f }, 0).level(0, 500)))
    }

    @Test
    fun `beat and bar lengths derive from bpm within bounds`() {
        assertEquals(500, HuePaletteMapper.beatMs(120.0))
        assertEquals(1000, HuePaletteMapper.beatMs(60.0))
        assertNull(HuePaletteMapper.beatMs(null))
        assertNull(HuePaletteMapper.beatMs(0.0))
        assertNull(HuePaletteMapper.beatMs(-120.0))
        assertEquals(2000L, HuePaletteMapper.barMs(120.0))
        assertEquals(4000L, HuePaletteMapper.barMs(60.0))
        assertEquals(10_000L, HuePaletteMapper.barMs(10.0))
        assertEquals(2_000L, HuePaletteMapper.barMs(400.0))
        assertEquals(6_000L, HuePaletteMapper.barMs(null))
    }

    @Test
    fun `audio data modulates brightness`() {
        val loud = HuePaletteMapper.map(listOf(red), null, SongAudioData(energy = 1.0, loudness = -5.0), link())
        val quiet = HuePaletteMapper.map(listOf(red), null, SongAudioData(energy = 0.1, loudness = -30.0), link())
        assertTrue(loud.commands.first().update.dimming!!.brightness > quiet.commands.first().update.dimming!!.brightness)
    }
}
