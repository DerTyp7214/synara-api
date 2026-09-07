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

    @Test
    fun `bands round trip planar within one step`() {
        val bandA = FloatArray(50) { -60f + it * 0.5f }
        val bandB = FloatArray(50) { -30f + it * 0.2f }
        val bandC = FloatArray(50) { -5f - it * 0.1f }
        val bytes = AudioTimelineCodec.encodeBands(listOf(bandA, bandB, bandC), -70f, 0f)
        val decoded = AudioTimelineCodec.decodeBands(bytes, 3, -70f, 0f)
        assertEquals(3, decoded.size)
        val step = 70f / 255f
        listOf(bandA, bandB, bandC).forEachIndexed { index, band ->
            band.indices.forEach { i -> assertTrue(abs(band[i] - decoded[index][i]) <= step, "band $index index $i") }
        }
    }

    @Test
    fun `unequal band lengths are truncated`() {
        val bandA = FloatArray(5) { -60f + it }
        val bandB = FloatArray(3) { -40f + it }
        val bandC = FloatArray(4) { -20f + it }
        val bytes = AudioTimelineCodec.encodeBands(listOf(bandA, bandB, bandC), -70f, 0f)
        val decoded = AudioTimelineCodec.decodeBands(bytes, 3, -70f, 0f)
        assertEquals(3, decoded.size)
        decoded.forEach { assertEquals(3, it.size) }
        val step = 70f / 255f
        assertTrue((0 until 3).all { abs(bandA[it] - decoded[0][it]) <= step })
        assertTrue((0 until 3).all { abs(bandB[it] - decoded[1][it]) <= step })
        assertTrue((0 until 3).all { abs(bandC[it] - decoded[2][it]) <= step })
    }

    @Test
    fun `an invalid band count decodes to nothing`() {
        assertTrue(AudioTimelineCodec.decodeBands(ByteArray(9), 0, -70f, 0f).isEmpty())
        assertTrue(AudioTimelineCodec.decodeBands(ByteArray(7), 3, -70f, 0f).isEmpty())
    }
}
