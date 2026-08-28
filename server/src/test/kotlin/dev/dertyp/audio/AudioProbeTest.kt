package dev.dertyp.audio

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeBytes

class AudioProbeTest {
    @Test
    fun `probes an eac3 file and its flac conversion`() = runBlocking {
        val tempDir = Files.createTempDirectory("audio-probe-test")
        try {
            val m4a = AtmosFixture.create(tempDir)
            val eac3 = AudioProbe.probe(m4a.toFile())!!
            assertEquals("eac3", eac3.codec)
            assertEquals(AtmosFixture.CHANNELS, eac3.channels)
            assertEquals(AtmosFixture.SAMPLE_RATE, eac3.sampleRate)
            assertEquals(0, eac3.bitsPerSample)
            assertEquals(768L, eac3.bitRate)
            assertEquals(m4a.toFile().length(), eac3.fileSize)

            val flac = AtmosProcessor(AudioConfig(LosslessFormat.FLAC)).process(m4a) {}!!
            val lossless = AudioProbe.probe(flac.toFile())!!
            assertEquals("flac", lossless.codec)
            assertEquals(6, lossless.channels)
            assertEquals(24, lossless.bitsPerSample)
            assertEquals(6, AudioProbe.probeChannels(flac.toFile()))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns null for missing or unreadable files`() {
        val tempDir = Files.createTempDirectory("audio-probe-test")
        try {
            assertNull(AudioProbe.probe(tempDir.resolve("missing.flac").toFile()))
            val garbage = tempDir.resolve("garbage.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
            assertNull(AudioProbe.probe(garbage.toFile()))
            assertEquals(0, AudioProbe.probeChannels(garbage.toFile()))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
