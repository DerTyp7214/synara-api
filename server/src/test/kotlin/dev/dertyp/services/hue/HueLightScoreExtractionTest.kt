package dev.dertyp.services.hue

import dev.dertyp.data.AudioBand
import dev.dertyp.data.SongAudioBand
import dev.dertyp.data.SongAudioTimeline
import dev.dertyp.services.audio.AudioTimelineCodec
import dev.dertyp.services.audio.RmsEnvelopeExtractor
import dev.dertyp.services.audio.highHz
import dev.dertyp.services.audio.lowHz
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
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HueLightScoreExtractionTest {
    private val sampleRate = 44100
    private val burstMs = 60
    private val fadeMs = 5
    private val beats = (500..7500 step 500).toList()

    @Test
    fun `an extracted kick track lights every beat on the onset`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("groove.wav").toFile()
        writeWav(file, durationMs = 8000) { i -> tone(40.0, 0.3, i) + burst(i) }

        val envelopes = RmsEnvelopeExtractor.extract(file, hz = 10)
        val timeline = timeline(envelopes)
        val score = HueLightScore.build(timeline, null, 8_000, 500, LevelSource.BASS)

        assertEquals(beats.size, score.keyframes.size)
        assertEquals(beats, score.keyframes.map { it.atMs })
        score.keyframes.forEach { assertTrue(it.level >= 0.8, "beat ${it.index} level ${it.level}") }

        val floors = score.keyframes.drop(2).map { it.floor }
        assertTrue(floors.max() - floors.min() <= 0.05, "floors should be steady: min=${floors.min()} max=${floors.max()}")

        val kick = KickOnsets(timeline.bands[AudioBand.KICK.ordinal].levelsDb, timeline.bandHz)
        val frameMs = 1000 / timeline.bandHz
        for (beat in beats) {
            val frames = ((beat - 200) / frameMs)..((beat + 200) / frameMs)
            val peak = frames.maxBy { kick.rise(it.toLong() * frameMs, it.toLong() * frameMs) } * frameMs
            assertTrue(peak in (beat - 40)..(beat + 60), "beat $beat peaked at $peak ms")
        }
    }

    private fun timeline(envelopes: RmsEnvelopeExtractor.Envelopes): SongAudioTimeline {
        val min = RmsEnvelopeExtractor.MIN_DB
        val max = RmsEnvelopeExtractor.MAX_DB
        val bands = AudioTimelineCodec.decodeBands(
            AudioTimelineCodec.encodeBands(envelopes.bands, min, max),
            AudioBand.entries.size,
            min,
            max,
        )
        val rms = AudioTimelineCodec.decodeEnvelope(AudioTimelineCodec.encodeEnvelope(envelopes.rmsDb, min, max), min, max)
        return SongAudioTimeline(
            songId = UUID.randomUUID(),
            beatsMs = beats,
            envelopeHz = 10,
            envelopeDb = rms.toList(),
            bandHz = envelopes.bandHz,
            bands = AudioBand.entries.map { SongAudioBand(it, it.lowHz, it.highHz, bands[it.ordinal].toList()) },
        )
    }

    private fun tone(frequencyHz: Double, amplitude: Double, index: Int): Double =
        amplitude * sin(2 * PI * frequencyHz * index / sampleRate)

    private fun burst(index: Int): Double {
        val length = sampleRate * burstMs / 1000
        val fade = sampleRate * fadeMs / 1000
        for (centre in beats) {
            val offset = index - (sampleRate * centre / 1000 - length / 2)
            if (offset < 0 || offset >= length) continue
            val gain = when {
                offset < fade -> 0.5 - 0.5 * cos(PI * offset / fade)
                offset >= length - fade -> 0.5 - 0.5 * cos(PI * (length - offset) / fade)
                else -> 1.0
            }
            return gain * tone(90.0, 0.5, offset)
        }
        return 0.0
    }

    private fun writeWav(file: File, durationMs: Int, sample: (Int) -> Double) {
        val recorder = FFmpegFrameRecorder(file.absolutePath, 1).apply {
            audioCodec = avcodec.AV_CODEC_ID_PCM_S16LE
            format = "wav"
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            this.sampleRate = this@HueLightScoreExtractionTest.sampleRate
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
