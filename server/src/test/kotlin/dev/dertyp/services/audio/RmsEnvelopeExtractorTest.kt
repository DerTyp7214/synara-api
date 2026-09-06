package dev.dertyp.services.audio

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ShortBuffer
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RmsEnvelopeExtractorTest {
    @Test
    fun `envelope length follows the duration and loud regions are louder`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("tone.wav").toFile()
        writeWav(file, silenceMs = 1000, toneMs = 1000)

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        assertTrue(abs(envelope.rmsDb.size - 20) <= 1, "expected about 20 samples, got ${envelope.rmsDb.size}")
        val silent = envelope.rmsDb.take(8)
        val loud = envelope.rmsDb.drop(11).take(8)
        assertTrue(silent.all { it <= RmsEnvelopeExtractor.MIN_DB + 1f }, "silence should be at the floor: $silent")
        assertTrue(loud.all { it > -20f }, "tone should be loud: $loud")
    }

    @Test
    fun `a bass tone is loud and close to rms in the bass band`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("bass.wav").toFile()
        writeWav(file, silenceMs = 1000, toneMs = 1000, frequencyHz = 60.0)

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        assertEquals(envelope.rmsDb.size, envelope.bassDb.size)
        val silentBass = envelope.bassDb.take(8)
        val loudRms = envelope.rmsDb.drop(11).take(8)
        val loudBass = envelope.bassDb.drop(11).take(8)
        assertTrue(silentBass.all { it <= RmsEnvelopeExtractor.MIN_DB + 1f }, "silence should be at the floor: $silentBass")
        assertTrue(loudBass.all { it > -20f }, "bass tone should be loud: $loudBass")
        loudRms.zip(loudBass).forEach { (rms, bass) ->
            assertTrue(abs(rms - bass) <= 3f, "bass should track rms within 3 dB: rms=$rms bass=$bass")
        }
    }

    @Test
    fun `a mid tone is loud but suppressed in the bass band`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("mid.wav").toFile()
        writeWav(file, silenceMs = 1000, toneMs = 1000, frequencyHz = 440.0)

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        assertEquals(envelope.rmsDb.size, envelope.bassDb.size)
        val loudRms = envelope.rmsDb.drop(11).take(8)
        val loudBass = envelope.bassDb.drop(11).take(8)
        assertTrue(loudRms.all { it > -20f }, "tone should be loud: $loudRms")
        loudRms.zip(loudBass).forEach { (rms, bass) ->
            assertTrue(rms - bass >= 30f, "bass should be suppressed for a mid tone: rms=$rms bass=$bass")
        }
    }

    private fun writeWav(file: File, silenceMs: Int, toneMs: Int, sampleRate: Int = 44100, frequencyHz: Double = 440.0) {
        val recorder = FFmpegFrameRecorder(file.absolutePath, 1).apply {
            audioCodec = avcodec.AV_CODEC_ID_PCM_S16LE
            format = "wav"
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            this.sampleRate = sampleRate
            start()
        }
        val silenceSamples = sampleRate * silenceMs / 1000
        val toneSamples = sampleRate * toneMs / 1000
        val buffer = ShortBuffer.allocate(silenceSamples + toneSamples)
        repeat(silenceSamples) { buffer.put(0) }
        repeat(toneSamples) { i -> buffer.put((sin(2 * PI * frequencyHz * i / sampleRate) * 0.5 * Short.MAX_VALUE).toInt().toShort()) }
        recorder.recordSamples(sampleRate, 1, buffer.rewind())
        recorder.stop()
        recorder.release()
        assertTrue(file.length() > 0)
    }
}
