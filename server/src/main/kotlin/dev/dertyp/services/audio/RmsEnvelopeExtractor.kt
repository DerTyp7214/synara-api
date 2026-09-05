package dev.dertyp.services.audio

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.sqrt

object RmsEnvelopeExtractor {
    const val MIN_DB = -70f
    const val MAX_DB = 0f

    fun extract(file: File, hz: Int = 10): FloatArray {
        val grabber = FFmpegFrameGrabber(file.absolutePath).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            start()
        }
        try {
            val sampleRate = grabber.sampleRate.coerceAtLeast(1)
            val channels = grabber.audioChannels.coerceAtLeast(1)
            val windowSize = (sampleRate.toLong() * channels / hz).toInt().coerceAtLeast(1)
            val values = ArrayList<Float>()

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
                            values.add(toDb(squareSum, fill))
                            squareSum = 0.0
                            fill = 0
                        }
                    }
                }
                frame = grabber.grabFrame(true, false, true, false)
            }
            if (fill > 0) values.add(toDb(squareSum, fill))
            return values.toFloatArray()
        } finally {
            grabber.stop()
            grabber.release()
        }
    }

    private fun toDb(squareSum: Double, count: Int): Float {
        val rms = sqrt(squareSum / count)
        if (rms <= 0.0) return MIN_DB
        return (20.0 * log10(rms)).toFloat().coerceIn(MIN_DB, MAX_DB)
    }
}
