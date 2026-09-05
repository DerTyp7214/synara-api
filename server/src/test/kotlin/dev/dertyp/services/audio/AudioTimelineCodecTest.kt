package dev.dertyp.services.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class AudioTimelineCodecTest {
    @Test
    fun `beats round trip to the millisecond`() {
        val positions = listOf(0.512, 1.023, 1.534, 2.045, 61.0, 61.5)
        val bytes = AudioTimelineCodec.encodeBeats(positions)
        assertEquals(positions.size * 2, bytes.size)
        assertArrayEquals(intArrayOf(512, 1023, 1534, 2045, 61000, 61500), AudioTimelineCodec.decodeBeats(bytes))
    }

    @Test
    fun `beat gaps larger than the delta range are clamped and never go backwards`() {
        val decoded = AudioTimelineCodec.decodeBeats(AudioTimelineCodec.encodeBeats(listOf(1.0, 0.5, 100.0)))
        assertEquals(1000, decoded[0])
        assertEquals(1000, decoded[1])
        assertEquals(1000 + 65535, decoded[2])
    }

    @Test
    fun `envelope quantization error stays within one step`() {
        val values = FloatArray(200) { -70f + it * 0.35f }
        val bytes = AudioTimelineCodec.encodeEnvelope(values, -70f, 0f)
        val decoded = AudioTimelineCodec.decodeEnvelope(bytes, -70f, 0f)
        assertEquals(values.size, decoded.size)
        val step = 70f / 255f
        values.indices.forEach { assertTrue(abs(values[it] - decoded[it]) <= step, "index $it") }
    }

    @Test
    fun `envelope values outside the range are clamped`() {
        val decoded = AudioTimelineCodec.decodeEnvelope(AudioTimelineCodec.encodeEnvelope(floatArrayOf(-200f, 50f), -70f, 0f), -70f, 0f)
        assertEquals(-70f, decoded[0])
        assertEquals(0f, decoded[1])
    }
}
