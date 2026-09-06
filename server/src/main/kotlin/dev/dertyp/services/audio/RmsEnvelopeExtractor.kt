package dev.dertyp.services.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt

object RmsEnvelopeExtractor {
    const val MIN_DB = -70f
    const val MAX_DB = 0f
    const val BASS_LOW_HZ = 30.0
    const val BASS_HIGH_HZ = 150.0
    private const val HANN_POWER_GAIN = 0.375

    data class Envelopes(val rmsDb: FloatArray, val bassDb: FloatArray)

    fun extract(file: File, hz: Int = 10): Envelopes {
        val grabber = FFmpegFrameGrabber(file.absolutePath).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            start()
        }
        try {
            val sampleRate = grabber.sampleRate.coerceAtLeast(1)
            val channels = grabber.audioChannels.coerceAtLeast(1)
            val windowSize = (sampleRate.toLong() * channels / hz).toInt().coerceAtLeast(1)
            val rms = ArrayList<Float>()
            val bass = BassAnalyzer(sampleRate, channels, (sampleRate / hz).coerceAtLeast(1))

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
                        bass.push(sample)
                    }
                }
                frame = grabber.grabFrame(true, false, true, false)
            }
            if (fill > 0) rms.add(toDb(squareSum, fill))
            bass.flush()
            return Envelopes(rms.toFloatArray(), bass.values.toFloatArray())
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    private fun toDb(squareSum: Double, count: Int): Float = rmsToDb(sqrt(squareSum / count))

    private fun rmsToDb(rms: Double): Float {
        if (rms <= 0.0) return MIN_DB
        return (20.0 * log10(rms)).toFloat().coerceIn(MIN_DB, MAX_DB)
    }

    private class BassAnalyzer(sampleRate: Int, private val channels: Int, private val windowSize: Int) {
        val values = ArrayList<Float>()
        private val fftSize = Fft.nextPowerOfTwo(windowSize)
        private val re = DoubleArray(fftSize)
        private val im = DoubleArray(fftSize)
        private val hann = DoubleArray(windowSize) { 0.5 - 0.5 * cos(2 * PI * it / windowSize) }
        private val window = DoubleArray(windowSize)
        private val lowBin = (BASS_LOW_HZ * fftSize / sampleRate).toInt().coerceAtLeast(1)
        private val highBin = (BASS_HIGH_HZ * fftSize / sampleRate).toInt().coerceIn(lowBin, fftSize / 2)
        private var channelFill = 0
        private var channelSum = 0.0
        private var fill = 0

        fun push(sample: Double) {
            channelSum += sample
            if (++channelFill < channels) return
            window[fill++] = channelSum / channels
            channelSum = 0.0
            channelFill = 0
            if (fill == windowSize) analyze()
        }

        fun flush() {
            if (fill > 0) analyze()
        }

        private fun analyze() {
            val count = fill
            for (i in 0 until fftSize) {
                re[i] = if (i < count) window[i] * hann[i] else 0.0
                im[i] = 0.0
            }
            Fft.transform(re, im)
            var power = 0.0
            for (k in lowBin..highBin) power += re[k] * re[k] + im[k] * im[k]
            val meanSquare = 2 * power / (fftSize.toDouble() * count * HANN_POWER_GAIN)
            values.add(rmsToDb(sqrt(meanSquare)))
            fill = 0
        }
    }
}
