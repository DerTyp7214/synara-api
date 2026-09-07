package dev.dertyp.services.audio

import dev.dertyp.data.AudioBand
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
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class RmsEnvelopeExtractorTest {
    private val burstMs = 60
    private val fadeMs = 5

    @Test
    fun `envelope length follows the duration and loud regions are louder`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("tone.wav").toFile()
        writeWav(file, durationMs = 2000) { i -> if (i < 44100) 0.0 else tone(440.0, 0.5, i - 44100, 44100) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        assertTrue(abs(envelope.rmsDb.size - 20) <= 1, "expected about 20 samples, got ${envelope.rmsDb.size}")
        val silent = envelope.rmsDb.take(8)
        val loud = envelope.rmsDb.drop(11).take(8)
        assertTrue(silent.all { it <= RmsEnvelopeExtractor.MIN_DB + 1f }, "silence should be at the floor: $silent")
        assertTrue(loud.all { it > -20f }, "tone should be loud: $loud")
    }

    @Test
    fun `a bass tone is loud and close to rms in the sub and kick bands`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("bass.wav").toFile()
        writeWav(file, durationMs = 2000) { i -> if (i < 44100) 0.0 else tone(60.0, 0.5, i - 44100, 44100) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)
        val sub = envelope.bands[AudioBand.SUB.ordinal]
        val kick = envelope.bands[AudioBand.KICK.ordinal]

        for (frame in bandFrames(150, 850)) {
            assertTrue(
                sub[frame] <= RmsEnvelopeExtractor.MIN_DB + 1f && kick[frame] <= RmsEnvelopeExtractor.MIN_DB + 1f,
                "silence should be at the floor: sub=${sub[frame]} kick=${kick[frame]}"
            )
        }
        for (frame in bandFrames(1150, 1850)) {
            val bass = powerSum(sub[frame], kick[frame])
            val rms = envelope.rmsDb[frame / 5]
            assertTrue(bass > -20f, "bass tone should be loud: $bass")
            assertTrue(abs(rms - bass) <= 3f, "sub plus kick should track rms within 3 dB: rms=$rms bass=$bass")
        }
    }

    @Test
    fun `a mid tone is loud but suppressed in the low bands`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("mid.wav").toFile()
        writeWav(file, durationMs = 2000) { i -> if (i < 44100) 0.0 else tone(440.0, 0.5, i - 44100, 44100) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        for (frame in bandFrames(1150, 1850)) {
            val rms = envelope.rmsDb[frame / 5]
            assertTrue(rms > -20f, "tone should be loud: $rms")
            for (band in listOf(AudioBand.SUB, AudioBand.KICK, AudioBand.LOW_MID)) {
                val level = envelope.bands[band.ordinal][frame]
                assertTrue(rms - level >= 30f, "$band should be suppressed for a mid tone: rms=$rms level=$level")
            }
        }
    }

    @Test
    fun `band frames run at 50 Hz`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("rate.wav").toFile()
        writeWav(file, durationMs = 3000) { i -> tone(440.0, 0.5, i, 44100) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        assertEquals(50, envelope.bandHz)
        assertEquals(AudioBand.entries.size, envelope.bands.size)
        assertEquals(1, envelope.bands.map { it.size }.distinct().size, envelope.bands.map { it.size }.toString())
        val size = envelope.bands.first().size
        assertTrue(abs(size - 151) <= 2, "expected about 151 band frames, got $size")
    }

    @Test
    fun `a 48 kHz file produces the same band rate`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("rate48.wav").toFile()
        writeWav(file, durationMs = 3000, sampleRate = 48000) { i -> tone(440.0, 0.5, i, 48000) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        assertEquals(50, envelope.bandHz)
        assertEquals(1, envelope.bands.map { it.size }.distinct().size, envelope.bands.map { it.size }.toString())
        val size = envelope.bands.first().size
        assertTrue(abs(size - 151) <= 2, "expected about 151 band frames, got $size")
    }

    @Test
    fun `a sustained sub tone with kick bursts gives a flat sub band and kick onsets on the bursts`(
        @TempDir tempDir: Path,
    ) {
        val file = tempDir.resolve("kicks.wav").toFile()
        val centres = (500..3500 step 500).toList()
        writeWav(file, durationMs = 4000) { i -> tone(40.0, 0.3, i, 44100) + burst(centres, i, 44100) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)
        val sub = envelope.bands[AudioBand.SUB.ordinal]
        val kick = envelope.bands[AudioBand.KICK.ordinal]

        val steady = bandFrames(200, 3800).map { sub[it] }.sorted()
        val median = steady[steady.size / 2]
        assertTrue(
            steady.all { abs(it - median) <= 1.5f },
            "sub band should stay flat around $median: min=${steady.first()} max=${steady.last()}"
        )
        for (centre in centres) {
            val hit = kick[frameAt(centre)]
            val quiet = kick[frameAt(centre + 250)]
            assertTrue(hit - quiet >= 15f, "burst at $centre should stand out: hit=$hit quiet=$quiet")
        }
        val bass = RmsEnvelopeExtractor.bassEnvelope(envelope.bands, envelope.bandHz, 10)
        assertTrue(abs(bass.size - 40) <= 2, "expected about 40 bass samples, got ${bass.size}")
    }

    @Test
    fun `a mid tone lands in the mid band`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("kilohertz.wav").toFile()
        writeWav(file, durationMs = 2000) { i -> tone(1000.0, 0.5, i, 44100) }

        val envelope = RmsEnvelopeExtractor.extract(file, hz = 10)

        for (frame in bandFrames(150, 1850)) {
            val rms = envelope.rmsDb[frame / 5]
            val mid = envelope.bands[AudioBand.MID.ordinal][frame]
            assertTrue(abs(rms - mid) <= 3f, "mid should track rms within 3 dB: rms=$rms mid=$mid")
            for (band in listOf(AudioBand.SUB, AudioBand.KICK, AudioBand.HIGH)) {
                val level = envelope.bands[band.ordinal][frame]
                assertTrue(rms - level >= 30f, "$band should be suppressed for a 1 kHz tone: rms=$rms level=$level")
            }
        }
    }

    private fun frameAt(ms: Int): Int = ms * RmsEnvelopeExtractor.BAND_HZ / 1000

    private fun bandFrames(fromMs: Int, toMs: Int): IntRange = frameAt(fromMs)..frameAt(toMs)

    private fun powerSum(first: Float, second: Float): Float =
        (10.0 * log10(10.0.pow(first / 10.0) + 10.0.pow(second / 10.0))).toFloat()

    private fun tone(frequencyHz: Double, amplitude: Double, index: Int, sampleRate: Int): Double =
        amplitude * sin(2 * PI * frequencyHz * index / sampleRate)

    private fun burst(centresMs: List<Int>, index: Int, sampleRate: Int): Double {
        val length = sampleRate * burstMs / 1000
        val fade = sampleRate * fadeMs / 1000
        for (centre in centresMs) {
            val offset = index - (sampleRate * centre / 1000 - length / 2)
            if (offset < 0 || offset >= length) continue
            val gain = when {
                offset < fade -> 0.5 - 0.5 * cos(PI * offset / fade)
                offset >= length - fade -> 0.5 - 0.5 * cos(PI * (length - offset) / fade)
                else -> 1.0
            }
            return gain * tone(90.0, 0.5, offset, sampleRate)
        }
        return 0.0
    }

    private fun writeWav(file: File, durationMs: Int, sampleRate: Int = 44100, sample: (Int) -> Double) {
        val recorder = FFmpegFrameRecorder(file.absolutePath, 1).apply {
            audioCodec = avcodec.AV_CODEC_ID_PCM_S16LE
            format = "wav"
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            this.sampleRate = sampleRate
            start()
        }
        val count = sampleRate * durationMs / 1000
        val buffer = ShortBuffer.allocate(count)
        repeat(count) { i -> buffer.put((sample(i).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()) }
        recorder.recordSamples(sampleRate, 1, buffer.rewind())
        recorder.stop()
        recorder.release()
        assertTrue(file.length() > 0)
    }
}
