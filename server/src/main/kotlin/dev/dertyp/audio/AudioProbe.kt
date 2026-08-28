package dev.dertyp.audio

import dev.dertyp.AudioUtils
import dev.dertyp.data.AudioInfo
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FrameGrabber
import java.io.File

object AudioProbe {
    private val losslessCodecs = setOf("flac", "alac", "wavpack", "tta", "ape", "mlp", "truehd")

    fun probe(file: File): AudioInfo? {
        if (!file.isFile) return null
        return runCatching {
            FFmpegFrameGrabber(file.absolutePath).use { grabber ->
                grabber.sampleMode = FrameGrabber.SampleMode.RAW
                grabber.start()
                if (grabber.audioChannels <= 0) return null
                val codec = grabber.audioCodecName.lowercase()
                val lossless = codec in losslessCodecs || codec.startsWith("pcm_")
                AudioInfo(
                    codec = codec,
                    sampleRate = grabber.sampleRate,
                    bitsPerSample = if (lossless) AudioUtils.sourceBitDepth(grabber) else 0,
                    bitRate = grabber.audioBitrate.toLong(),
                    fileSize = file.length(),
                    channels = grabber.audioChannels,
                )
            }
        }.getOrNull()
    }

    fun probeChannels(file: File): Int = probe(file)?.channels ?: 0
}
