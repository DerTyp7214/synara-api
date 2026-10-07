package dev.dertyp.audio

import dev.dertyp.services.StorageService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.io.path.writeBytes

class AudioProbeTest {
    @BeforeEach
    fun setup() {
        startKoin {
            modules(module {
                single { mockk<StorageService>(relaxed = true) }
                singleOf(::Transcoder)
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

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
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
