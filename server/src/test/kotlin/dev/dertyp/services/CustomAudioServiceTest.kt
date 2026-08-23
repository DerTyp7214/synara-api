package dev.dertyp.services

import dev.dertyp.AudioUtils
import dev.dertyp.Indexer
import dev.dertyp.audio.AudioConfig
import dev.dertyp.audio.LosslessFormat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CustomAudioServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `uploadCustomAudio should copy FLAC and index it`() = runBlocking {
        val indexer = mockk<Indexer>()
        val storageService = mockk<StorageService>()
        val customPath = File(tempDir.toFile(), "custom").apply { mkdirs() }
        every { storageService.customAudioPath } returns customPath.absolutePath
        justRun { storageService.invalidate(any()) }

        mockkConstructor(FFmpegFrameGrabber::class)
        every { anyConstructed<FFmpegFrameGrabber>().start() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().format } returns "flac"
        every { anyConstructed<FFmpegFrameGrabber>().stop() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().release() } just Runs

        val deferred = CompletableDeferred<Unit>()
        deferred.complete(Unit)
        
        coEvery { indexer.queue(any(), any(), any(), any(), any()) } returns deferred

        val service = CustomAudioService(indexer, storageService)
        val fileData = "fake flac data".toByteArray()
        val uuid = service.uploadCustomAudio(fileData, "test.flac", null)

        assertNotNull(uuid)
        val targetFile = File(customPath, "$uuid.flac")
        assertEquals(true, targetFile.exists())
        assertEquals("fake flac data", targetFile.readText())

        coVerify { indexer.queue(listOf(targetFile.toPath()), emptyList(), any(), any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(LosslessFormat::class)
    fun `uploadCustomAudio should convert non-lossless input to the configured format`(format: LosslessFormat) = runBlocking {
        val indexer = mockk<Indexer>(relaxed = true)
        val storageService = mockk<StorageService>()
        val customPath = File(tempDir.toFile(), "custom").apply { mkdirs() }
        every { storageService.customAudioPath } returns customPath.absolutePath
        justRun { storageService.invalidate(any()) }

        mockkConstructor(FFmpegFrameGrabber::class)
        every { anyConstructed<FFmpegFrameGrabber>().start() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().format } returns "mp3"
        every { anyConstructed<FFmpegFrameGrabber>().stop() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().release() } just Runs

        mockkObject(AudioUtils)
        coEvery { AudioUtils.convertLossless(any(), any(), format) } answers {
            secondArg<File>().writeText("converted data")
        }

        val deferred = CompletableDeferred<Unit>()
        deferred.complete(Unit)
        coEvery { indexer.queue(any(), any(), any(), any(), any()) } returns deferred

        val service = CustomAudioService(indexer, storageService, AudioConfig(losslessFormat = format))
        val uuid = service.uploadCustomAudio("fake mp3 data".toByteArray(), "test.mp3", null)

        assertNotNull(uuid)
        val targetFile = File(customPath, "$uuid.${format.extension}")
        assertEquals("converted data", targetFile.readText())
        coVerify { AudioUtils.convertLossless(any(), targetFile, format) }
    }

    @Test
    fun `uploadCustomAudio should copy WAV when WAV is configured`() = runBlocking {
        val indexer = mockk<Indexer>(relaxed = true)
        val storageService = mockk<StorageService>()
        val customPath = File(tempDir.toFile(), "custom").apply { mkdirs() }
        every { storageService.customAudioPath } returns customPath.absolutePath
        justRun { storageService.invalidate(any()) }

        mockkConstructor(FFmpegFrameGrabber::class)
        every { anyConstructed<FFmpegFrameGrabber>().start() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().format } returns "wav"
        every { anyConstructed<FFmpegFrameGrabber>().stop() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().release() } just Runs

        val deferred = CompletableDeferred<Unit>()
        deferred.complete(Unit)
        coEvery { indexer.queue(any(), any(), any(), any(), any()) } returns deferred

        val service = CustomAudioService(indexer, storageService, AudioConfig(losslessFormat = LosslessFormat.WAV))
        val uuid = service.uploadCustomAudio("fake wav data".toByteArray(), "test.wav", null)

        assertNotNull(uuid)
        assertEquals("fake wav data", File(customPath, "$uuid.wav").readText())
    }
}
