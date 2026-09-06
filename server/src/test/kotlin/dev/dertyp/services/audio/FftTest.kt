package dev.dertyp.services.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class FftTest {
    @Test
    fun `a pure sine at bin k has all its energy in that bin`() {
        val n = 1024
        val k = 37
        val re = DoubleArray(n) { sin(2 * PI * k * it / n) }
        val im = DoubleArray(n)

        Fft.transform(re, im)

        val expected = n / 2.0
        val magnitudeAtK = magnitude(re, im, k)
        assertTrue(
            relativeError(magnitudeAtK, expected) <= 1e-6,
            "expected |X_$k| ~= $expected, got $magnitudeAtK",
        )
        for (j in 0..n / 2) {
            if (j == k) continue
            val magnitudeAtJ = magnitude(re, im, j)
            assertTrue(
                magnitudeAtJ <= expected * 1e-6,
                "expected |X_$j| ~= 0, got $magnitudeAtJ",
            )
        }
    }

    @Test
    fun `parseval's theorem holds for random input`() {
        val n = 256
        val random = Random(42)
        val re = DoubleArray(n) { random.nextDouble(-1.0, 1.0) }
        val im = DoubleArray(n)
        val timeEnergy = re.sumOf { it * it }

        Fft.transform(re, im)

        val freqEnergy = re.indices.sumOf { re[it] * re[it] + im[it] * im[it] }
        assertTrue(
            relativeError(timeEnergy * n, freqEnergy) <= 1e-6,
            "expected sum|x|^2 * n ~= sum|X|^2, got ${timeEnergy * n} vs $freqEnergy",
        )
    }

    @Test
    fun `transform rejects a non power of two size`() {
        assertThrows(IllegalArgumentException::class.java) {
            Fft.transform(DoubleArray(10), DoubleArray(10))
        }
    }

    @Test
    fun `nextPowerOfTwo rounds up to the nearest power of two`() {
        assertEquals(8192, Fft.nextPowerOfTwo(4410))
        assertEquals(1, Fft.nextPowerOfTwo(1))
        assertEquals(1024, Fft.nextPowerOfTwo(1024))
    }

    private fun magnitude(re: DoubleArray, im: DoubleArray, index: Int): Double =
        sqrt(re[index] * re[index] + im[index] * im[index])

    private fun relativeError(actual: Double, expected: Double): Double =
        abs(actual - expected) / expected
}
