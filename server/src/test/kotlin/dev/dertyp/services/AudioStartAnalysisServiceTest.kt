package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AnimatedImageTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.stopKoin
import java.io.File
import java.nio.ShortBuffer
import java.nio.file.Path
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioStartAnalysisServiceTest {

    private val service = AudioStartAnalysisService()

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
        stopKoin()
    }

    @Test
    fun `detects audio start after leading silence`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("silence_then_tone.wav").toFile()
        writeWav(file, silenceMs = 500, toneMs = 500)

        val start = service.detectAudioStart(file)
        assertTrue(abs(start - 500) <= 15, "expected ~500ms, got $start")
    }

    @Test
    fun `returns zero when audio starts immediately`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("tone.wav").toFile()
        writeWav(file, silenceMs = 0, toneMs = 300)

        assertEquals(0, service.detectAudioStart(file))
    }

    @Test
    fun `returns duration for fully silent audio`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("silent.wav").toFile()
        writeWav(file, silenceMs = 800, toneMs = 0)

        val start = service.detectAudioStart(file)
        assertTrue(abs(start - 800) <= 15, "expected ~800ms, got $start")
    }

    @Test
    fun `ignores noise below the audible threshold`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("noise_then_tone.wav").toFile()
        writeWav(file, silenceMs = 400, toneMs = 400, noiseAmplitude = 0.0003)

        val start = service.detectAudioStart(file)
        assertTrue(abs(start - 400) <= 15, "expected ~400ms, got $start")
    }

    @Test
    fun `analyze persists the detected offset and unanalyzed query excludes it`(@TempDir tempDir: Path) = runBlocking {
        val db = TestDatabase.connect(DbDialect.SQLITE, "audio_start_test")
        transaction(db) { SchemaUtils.create(ArtistTable, ImageTable, AnimatedImageTable, AlbumTable, SongTable, SongVariantTable) }

        val file = tempDir.resolve("song.wav").toFile()
        writeWav(file, silenceMs = 200, toneMs = 200)
        val albumId = transaction(db) { AlbumTable.insert { it[name] = "Album" } get AlbumTable.id }
        val songId = UUID.randomUUID()
        val missingId = UUID.randomUUID()
        transaction(db) {
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[filePath] = file.absolutePath
            }
            SongTable.insert {
                it[id] = missingId
                it[title] = "Missing"
                it[SongTable.albumId] = albumId
                it[filePath] = tempDir.resolve("missing.wav").toString()
            }
        }

        assertEquals(setOf(songId, missingId), service.getUnanalyzedSongIds().toSet())

        val detected = service.analyze(songId)!!
        assertTrue(abs(detected - 200) <= 15, "expected ~200ms, got $detected")
        assertNull(service.analyze(missingId))

        val stored = transaction(db) { SongTable.select(SongTable.audioStartMs).where { SongTable.id eq songId }.single()[SongTable.audioStartMs] }
        assertEquals(detected, stored)
        assertEquals(listOf(missingId), service.getUnanalyzedSongIds())
    }

    private fun writeWav(file: File, silenceMs: Int, toneMs: Int, sampleRate: Int = 44100, noiseAmplitude: Double = 0.0) {
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
        val random = java.util.Random(42)
        repeat(silenceSamples) {
            buffer.put((random.nextGaussian() * noiseAmplitude * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort())
        }
        repeat(toneSamples) { i ->
            buffer.put((sin(2 * PI * 440 * i / sampleRate) * 0.5 * Short.MAX_VALUE).toInt().toShort())
        }
        recorder.recordSamples(sampleRate, 1, buffer.rewind())
        recorder.stop()
        recorder.release()
        assertTrue(file.length() > 0)
    }
}
