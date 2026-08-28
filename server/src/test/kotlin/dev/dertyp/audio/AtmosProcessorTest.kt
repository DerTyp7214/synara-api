package dev.dertyp.audio

import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

class AtmosProcessorTest {
    private val processor = AtmosProcessor(AudioConfig(LosslessFormat.FLAC))

    @Test
    fun `isAtmos is false for non-m4a paths and unreadable m4a files`() {
        val tempDir = Files.createTempDirectory("atmos-test")
        try {
            assertFalse(processor.isAtmos(tempDir.resolve("track.flac")))
            val garbage = tempDir.resolve("garbage.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
            assertFalse(processor.isAtmos(garbage))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `process leaves the source untouched when conversion fails`() = runBlocking {
        val tempDir = Files.createTempDirectory("atmos-test")
        try {
            val garbage = tempDir.resolve("garbage.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
            val lines = mutableListOf<String>()

            assertNull(processor.process(garbage) { lines.add(it) })

            assertTrue(garbage.exists())
            assertFalse(tempDir.resolve("garbage.flac").exists())
            assertFalse(tempDir.resolve("garbage.atmos.m4a").exists())
            assertTrue(lines.single().startsWith("Dolby Atmos conversion failed"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `process converts an eac3 download to a 5_1 flac and keeps the atmos variant`() = runBlocking {
        val tempDir = Files.createTempDirectory("atmos-test")
        try {
            val m4a = AtmosFixture.create(tempDir, "549904229.m4a")
            assertTrue(processor.isAtmos(m4a))

            val flac = processor.process(m4a) {}

            assertEquals(tempDir.resolve("549904229.flac"), flac)
            assertTrue(flac!!.exists())
            assertFalse(m4a.exists())
            assertTrue(tempDir.resolve("549904229.atmos.m4a").exists())

            val header = AudioFileIO.read(flac.toFile()).audioHeader
            assertEquals("6", header.channels)
            assertEquals(AtmosFixture.SAMPLE_RATE, header.sampleRateAsNumber)
            assertEquals(24, header.bitsPerSample)
            assertEquals(AtmosFixture.SECONDS, header.trackLength)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
