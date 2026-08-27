package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.pow
import kotlin.math.sqrt

class AudioStartAnalysisService : Service() {
    companion object {
        const val AUDIBLE_THRESHOLD_DBFS = -50.0
        const val WINDOW_MS = 10
        val audibleThresholdAmplitude: Double = 10.0.pow(AUDIBLE_THRESHOLD_DBFS / 20.0)
    }

    suspend fun getUnanalyzedSongIds(): List<PlatformUUID> = dbQuery {
        SongTable
            .select(SongTable.id)
            .where { SongTable.audioStartMs.isNull() }
            .map { it[SongTable.id].value }
    }

    suspend fun analyze(songId: PlatformUUID): Long? {
        val path = dbQuery {
            SongTable.select(SongTable.filePath)
                .where { SongTable.id eq songId }
                .singleOrNull()
                ?.get(SongTable.filePath)
        } ?: return null

        val file = File(path)
        if (!file.exists()) {
            logger.warn("Skipping audio start analysis, file not found: $path")
            return null
        }

        val audioStartMs = detectAudioStart(file)

        dbQuery {
            SongTable.update({ SongTable.id eq songId }) {
                it[SongTable.audioStartMs] = audioStartMs
            }
        }

        return audioStartMs
    }

    internal fun detectAudioStart(file: File): Long {
        val grabber = FFmpegFrameGrabber(file.absolutePath).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_FLT
            start()
        }
        try {
            val sampleRate = grabber.sampleRate
            val channels = grabber.audioChannels.coerceAtLeast(1)
            val windowSize = (sampleRate.toLong() * channels * WINDOW_MS / 1000).toInt().coerceAtLeast(1)
            val thresholdSquareSum = audibleThresholdAmplitude * audibleThresholdAmplitude * windowSize

            var windowSquareSum = 0.0
            var windowFill = 0
            var samplesConsumed = 0L

            var frame = grabber.grabFrame(true, false, true, false)
            while (frame != null) {
                val buffer = frame.samples?.firstOrNull() as? FloatBuffer
                if (buffer != null) {
                    val remaining = buffer.remaining()
                    for (i in 0 until remaining) {
                        val sample = buffer.get(buffer.position() + i).toDouble()
                        windowSquareSum += sample * sample
                        windowFill++
                        if (windowFill == windowSize) {
                            if (windowSquareSum >= thresholdSquareSum) {
                                val windowStartSample = (samplesConsumed + i + 1 - windowSize) / channels
                                return windowStartSample * 1000 / sampleRate
                            }
                            windowSquareSum = 0.0
                            windowFill = 0
                        }
                    }
                    samplesConsumed += remaining
                }
                frame = grabber.grabFrame(true, false, true, false)
            }

            if (windowFill > 0 && sqrt(windowSquareSum / windowFill) >= audibleThresholdAmplitude) {
                return (samplesConsumed - windowFill) / channels * 1000 / sampleRate
            }

            return grabber.lengthInTime / 1000
        } finally {
            grabber.stop()
            grabber.release()
        }
    }
}
