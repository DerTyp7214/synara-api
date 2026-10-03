package dev.dertyp.audio

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

object FfmpegProbe {
    init {
        FFmpegLogCallback.set()
    }

    fun duration(file: File): Duration {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET)
        val grabber = FFmpegFrameGrabber(file.absolutePath)
        return try {
            grabber.start()
            val duration = grabber.lengthInTime.microseconds
            grabber.stop()
            duration
        } catch (_: Throwable) {
            Duration.ZERO
        } finally {
            grabber.release()
        }
    }

    fun sourceBitDepth(grabber: FFmpegFrameGrabber): Int {
        when (grabber.audioCodec) {
            avcodec.AV_CODEC_ID_PCM_U8, avcodec.AV_CODEC_ID_PCM_S8 -> return 8
            avcodec.AV_CODEC_ID_PCM_S16LE, avcodec.AV_CODEC_ID_PCM_S16BE -> return 16
            avcodec.AV_CODEC_ID_PCM_S24LE, avcodec.AV_CODEC_ID_PCM_S24BE -> return 24
            avcodec.AV_CODEC_ID_PCM_S32LE, avcodec.AV_CODEC_ID_PCM_S32BE -> return 32
        }
        return when (grabber.sampleFormat) {
            avutil.AV_SAMPLE_FMT_U8, avutil.AV_SAMPLE_FMT_U8P -> 8
            avutil.AV_SAMPLE_FMT_S16, avutil.AV_SAMPLE_FMT_S16P -> 16
            avutil.AV_SAMPLE_FMT_S32, avutil.AV_SAMPLE_FMT_S32P,
            avutil.AV_SAMPLE_FMT_FLT, avutil.AV_SAMPLE_FMT_FLTP -> 24

            else -> 16
        }
    }
}
