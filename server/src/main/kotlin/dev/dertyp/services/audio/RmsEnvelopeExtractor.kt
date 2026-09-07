package dev.dertyp.services.audio

import dev.dertyp.data.AudioBand
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

object RmsEnvelopeExtractor {
    const val MIN_DB = -70f
    const val MAX_DB = 0f
    const val BAND_HZ = 50
    const val WINDOW_SEC = 0.08
    private const val HANN_POWER_GAIN = 0.375

    data class Envelopes(val rmsDb: FloatArray, val bands: List<FloatArray>, val bandHz: Int)

    fun extract(file: File, hz: Int = 10, bandHz: Int = BAND_HZ): Envelopes {
        val grabber = FFmpegFrameGrabber(file.absolutePath).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            start()
        }
        try {
            val sampleRate = grabber.sampleRate.coerceAtLeast(1)
            val channels = grabber.audioChannels.coerceAtLeast(1)
            val windowSize = (sampleRate.toLong() * channels / hz).toInt().coerceAtLeast(1)
            val rms = ArrayList<Float>()
            val analyzer = BandAnalyzer(sampleRate, channels, bandHz)

            var squareSum = 0.0
            var fill = 0

            var frame = grabber.grabFrame(true, false, true, false)
            while (frame != null) {
                val buffer = frame.samples?.firstOrNull() as? FloatBuffer
                if (buffer != null) {
                    val remaining = buffer.remaining()
                    for (i in 0 until remaining) {
                        val sample = buffer.get(buffer.position() + i).toDouble()
                        squareSum += sample * sample
                        fill++
                        if (fill == windowSize) {
                            rms.add(toDb(squareSum, fill))
                            squareSum = 0.0
                            fill = 0
                        }
                        analyzer.push(sample)
                    }
                }
                frame = grabber.grabFrame(true, false, true, false)
            }
            if (fill > 0) rms.add(toDb(squareSum, fill))
            analyzer.flush()
            return Envelopes(
                rmsDb = rms.toFloatArray(),
                bands = analyzer.values.map { it.toFloatArray() },
                bandHz = analyzer.bandHz
            )
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    fun bassEnvelope(bands: List<FloatArray>, bandHz: Int, hz: Int): FloatArray {
        if (bands.size < 2) return FloatArray(0)
        val sub = bands[AudioBand.SUB.ordinal]
        val kick = bands[AudioBand.KICK.ordinal]
        val frames = minOf(sub.size, kick.size)
        if (frames == 0) return FloatArray(0)
        val group = (bandHz / hz.coerceAtLeast(1)).coerceAtLeast(1)
        val result = FloatArray(ceil(frames.toDouble() / group).toInt())
        for (i in result.indices) {
            val start = i * group
            val end = minOf(start + group, frames)
            var sum = 0.0
            for (f in start until end) {
                sum += 10.0.pow(sub[f] / 10.0) + 10.0.pow(kick[f] / 10.0)
            }
            result[i] = powerToDb(sum / (end - start))
        }
        return result
    }

    private fun toDb(squareSum: Double, count: Int): Float = rmsToDb(sqrt(squareSum / count))

    private fun rmsToDb(rms: Double): Float {
        if (rms <= 0.0) return MIN_DB
        return (20.0 * log10(rms)).toFloat().coerceIn(MIN_DB, MAX_DB)
    }

    private fun powerToDb(power: Double): Float {
        if (power <= 0.0) return MIN_DB
        return (10.0 * log10(power)).toFloat().coerceIn(MIN_DB, MAX_DB)
    }

    private class BandAnalyzer(sampleRate: Int, private val channels: Int, bandHz: Int) {
        val bandHz = bandHz.coerceAtLeast(1)
        val values: List<ArrayList<Float>> = AudioBand.entries.map { ArrayList<Float>() }
        private val fftSize = Fft.nextPowerOfTwo((sampleRate * WINDOW_SEC).roundToInt())
        private val half = fftSize / 2
        private val hop = (sampleRate / this.bandHz).coerceAtLeast(1)
        private val ring = DoubleArray(fftSize)
        private val re = DoubleArray(fftSize)
        private val im = DoubleArray(fftSize)
        private val hann = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / fftSize) }
        private val lowBins = IntArray(AudioBand.entries.size)
        private val highBins = IntArray(AudioBand.entries.size)
        private var channelFill = 0
        private var channelSum = 0.0
        private var monoCount = 0L

        init {
            for (band in AudioBand.entries) {
                val low = ceil(band.lowHz.toDouble() * fftSize / sampleRate).toInt().coerceAtLeast(1)
                val high = floor(band.highHz.toDouble() * fftSize / sampleRate).toInt().coerceIn(low, fftSize / 2)
                lowBins[band.ordinal] = low
                highBins[band.ordinal] = high
            }
        }

        fun push(sample: Double) {
            channelSum += sample
            if (++channelFill < channels) return
            pushMono(channelSum / channels)
            channelSum = 0.0
            channelFill = 0
        }

        fun flush() {
            repeat(half) { pushMono(0.0) }
        }

        private fun pushMono(sample: Double) {
            ring[(monoCount % fftSize).toInt()] = sample
            monoCount++
            if (monoCount >= half && (monoCount - half) % hop == 0L) analyze()
        }

        private fun analyze() {
            val head = (monoCount % fftSize).toInt()
            for (i in 0 until fftSize) {
                re[i] = ring[(head + i) % fftSize] * hann[i]
                im[i] = 0.0
            }
            Fft.transform(re, im)
            for (band in AudioBand.entries) {
                var power = 0.0
                for (k in lowBins[band.ordinal]..highBins[band.ordinal]) power += re[k] * re[k] + im[k] * im[k]
                val meanSquare = 2 * power / (fftSize.toDouble() * fftSize * HANN_POWER_GAIN)
                values[band.ordinal].add(powerToDb(meanSquare))
            }
        }
    }
}
