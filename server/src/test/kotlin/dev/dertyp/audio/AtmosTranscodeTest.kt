package dev.dertyp.audio

import dev.dertyp.data.AudioFormat
import dev.dertyp.services.StorageService
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files

class AtmosTranscodeTest {
    private val transcoder = Transcoder(mockk<StorageService>(relaxed = true))

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `bitrate channel factor scales with channel pairs`() {
        assertEquals(1, Transcoder.bitrateChannelFactor(1))
        assertEquals(1, Transcoder.bitrateChannelFactor(2))
        assertEquals(2, Transcoder.bitrateChannelFactor(3))
        assertEquals(3, Transcoder.bitrateChannelFactor(6))
        assertEquals(4, Transcoder.bitrateChannelFactor(8))
    }

    @Test
    fun `aac and opus transcodes of a 5_1 source keep six channels`() = runBlocking {
        startKoin {
            modules(module {
                single { transcoder }
            })
        }
        val tempDir = Files.createTempDirectory("atmos-transcode-test")
        try {
            val m4a = AtmosFixture.create(tempDir)
            val flac = AtmosProcessor(AudioConfig(LosslessFormat.FLAC)).process(m4a) {}!!.toFile()

            val environment = mockk<ApplicationEnvironment>()
            every { environment.config } returns MapApplicationConfig(
                "audio.tracks" to tempDir.toString(),
                "audio.transcode" to tempDir.resolve("transcode").toString()
            )

            val aac = transcoder.transcodeAudio(environment, flac, 128, audioFormat = AudioFormat.AAC).file
            val opus = transcoder.transcodeAudio(environment, flac, 128, audioFormat = AudioFormat.OPUS).file

            assertEquals(Probe(6, "aac", 48000), probe(aac))
            assertEquals(Probe(6, "opus", 48000), probe(opus))

            val duration = AtmosFixture.SECONDS.toDouble()
            val aacKbps = aac.length() * 8 / duration / 1000
            val opusKbps = opus.length() * 8 / duration / 1000
            assertTrue(aacKbps in 250.0..520.0, "aac ~384 kbps expected, was $aacKbps")
            assertTrue(opusKbps in 250.0..520.0, "opus ~384 kbps expected, was $opusKbps")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    private data class Probe(val channels: Int, val codec: String, val sampleRate: Int)

    private fun probe(file: File): Probe = FFmpegFrameGrabber(file.absolutePath).use {
        it.start()
        Probe(it.audioChannels, it.audioCodecName, it.sampleRate)
    }
}
