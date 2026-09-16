package dev.dertyp.services.podcast

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class PodcastMediaProbeTest {
    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("podcast_probe").toFile()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeSilentWav(file: File): File {
        val format = AudioFormat(44100f, 16, 1, true, false)
        val stream = AudioInputStream(ByteArrayInputStream(ByteArray(44100 * 2)), format, 44100L)
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
        return file
    }

    @Test
    fun `probe reads duration and format from a generated wav`() {
        val file = writeSilentWav(File(tempDir, "one-second.wav"))

        val probe = PodcastMediaProbe.probe(file)

        assertEquals("wav", probe.format)
        val duration = probe.durationMs
        assertNotNull(duration)
        assertTrue(duration!! in 900L..1100L, "expected about 1000 ms but got $duration")
    }

    @Test
    fun `probe of a non audio file returns empty values without throwing`() {
        val file = File(tempDir, "notes").apply { writeText("this is not audio at all") }

        val probe = PodcastMediaProbe.probe(file)

        assertNull(probe.durationMs)
        assertNull(probe.format)
        assertNull(probe.title)
        assertNull(probe.artwork)
        assertNull(probe.lyrics)
    }

    @Test
    fun `probe of a non audio file keeps the extension as format`() {
        val file = File(tempDir, "readme.txt").apply { writeText("still not audio") }

        val probe = PodcastMediaProbe.probe(file)

        assertEquals("txt", probe.format)
        assertNull(probe.durationMs)
    }

    @Test
    fun `transcriptTypeOf detects webvtt`() {
        assertEquals("text/vtt", PodcastMediaProbe.transcriptTypeOf("WEBVTT\n\n00:00.000 --> 00:01.000\nhello"))
        assertEquals("text/vtt", PodcastMediaProbe.transcriptTypeOf("\n  WEBVTT\n\ncue"))
    }

    @Test
    fun `transcriptTypeOf detects srt cues`() {
        val srt = "1\n00:00:01,000 --> 00:00:04,000\nhello there\n"
        assertEquals("application/srt", PodcastMediaProbe.transcriptTypeOf(srt))
    }

    @Test
    fun `transcriptTypeOf falls back to plain text`() {
        assertEquals("text/plain", PodcastMediaProbe.transcriptTypeOf("just a transcript without cues"))
        assertEquals("text/plain", PodcastMediaProbe.transcriptTypeOf(""))
    }
}
