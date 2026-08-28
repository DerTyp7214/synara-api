package dev.dertyp.audio

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.nio.ShortBuffer
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object AtmosFixture {
    const val CHANNELS = 6
    const val SAMPLE_RATE = 48000
    const val SECONDS = 2

    fun create(dir: Path, name: String = "fixture.m4a"): Path {
        val output = dir.resolve(name)
        val recorder = FFmpegFrameRecorder(output.toString(), CHANNELS).apply {
            imageWidth = 0
            imageHeight = 0
            videoCodec = avcodec.AV_CODEC_ID_NONE
            format = "mp4"
            audioCodec = avcodec.AV_CODEC_ID_EAC3
            sampleFormat = avutil.AV_SAMPLE_FMT_FLTP
            sampleRate = SAMPLE_RATE
            audioBitrate = 768_000
            frameRate = 1.0
            setMetadata("title", "Fixture")
            start()
        }
        val random = Random(42)
        val frameSamples = 1536
        val buffer = ShortBuffer.allocate(frameSamples * CHANNELS)
        val frame = Frame().apply {
            sampleRate = SAMPLE_RATE
            audioChannels = CHANNELS
            samples = arrayOf(buffer)
        }
        var t = 0
        repeat(SAMPLE_RATE * SECONDS / frameSamples) {
            buffer.clear()
            repeat(frameSamples) {
                val tone = sin(2 * PI * 440 * t / SAMPLE_RATE)
                repeat(CHANNELS) { channel ->
                    buffer.put(((tone * 0.3 + random.nextDouble(-0.2, 0.2)) * Short.MAX_VALUE * (1 - channel * 0.1)).toInt().toShort())
                }
                t++
            }
            buffer.flip()
            recorder.record(frame)
        }
        recorder.stop()
        recorder.release()
        return output
    }
}
