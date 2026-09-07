package dev.dertyp.services.hue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HueStreamFrameTest {
    private val configurationId = "01234567-89ab-cdef-0123-456789abcdef"

    @Test
    fun `a single channel frame carries the documented header`() {
        val frame = HueStreamFrame.encode(configurationId, 7, listOf(HueChannelColor(3, 0x1234, 0x00FF, 0xFFFF)))

        assertEquals(59, frame.size)
        assertEquals("HueStream", String(frame, 0, 9, Charsets.US_ASCII))
        assertEquals(0x02.toByte(), frame[9])
        assertEquals(0x00.toByte(), frame[10])
        assertEquals(7.toByte(), frame[11])
        assertEquals(0x00.toByte(), frame[12])
        assertEquals(0x00.toByte(), frame[13])
        assertEquals(HueStreamFrame.COLOR_SPACE_RGB.toByte(), frame[14])
        assertEquals(0x00.toByte(), frame[15])
        assertEquals(configurationId, String(frame, 16, 36, Charsets.US_ASCII))
        assertEquals(3.toByte(), frame[52])
        assertEquals(0x12.toByte(), frame[53])
        assertEquals(0x34.toByte(), frame[54])
        assertEquals(0x00.toByte(), frame[55])
        assertEquals(0xFF.toByte(), frame[56])
        assertEquals(0xFF.toByte(), frame[57])
        assertEquals(0xFF.toByte(), frame[58])
    }

    @Test
    fun `the color space and out of range values are clamped`() {
        val frame = HueStreamFrame.encode(configurationId, 0, listOf(HueChannelColor(0, -5, 70_000, 0)), HueStreamFrame.COLOR_SPACE_XY)

        assertEquals(HueStreamFrame.COLOR_SPACE_XY.toByte(), frame[14])
        assertEquals(0x00.toByte(), frame[53])
        assertEquals(0x00.toByte(), frame[54])
        assertEquals(0xFF.toByte(), frame[55])
        assertEquals(0xFF.toByte(), frame[56])
    }

    @Test
    fun `the sequence counter wraps after 256 frames`() {
        val encoder = HueStreamEncoder(configurationId)
        val channels = listOf(HueChannelColor(0, 0, 0, 0))

        val sequences = (0 until 258).map { encoder.next(channels)[11].toInt() and 0xFF }

        assertEquals((0..255).toList() + listOf(0, 1), sequences)
    }

    @Test
    fun `more than twenty channels are rejected`() {
        val channels = (0..20).map { HueChannelColor(it, 0, 0, 0) }

        assertThrows<IllegalArgumentException> { HueStreamFrame.encode(configurationId, 0, channels) }
    }

    @Test
    fun `a configuration id of another length is rejected`() {
        assertThrows<IllegalArgumentException> { HueStreamFrame.encode("too-short", 0, emptyList()) }
        assertThrows<IllegalArgumentException> { HueStreamFrame.encode(configurationId + "0", 0, emptyList()) }
    }

    @Test
    fun `eight bit values scale to the full sixteen bit range`() {
        assertEquals(0, HueStreamFrame.rgb8To16(0))
        assertEquals(65_535, HueStreamFrame.rgb8To16(255))
        assertEquals(65_535, HueStreamFrame.rgb8To16(400))
        assertEquals(0, HueStreamFrame.rgb8To16(-1))
        assertEquals(32_896, HueStreamFrame.rgb8To16(128))
    }
}
