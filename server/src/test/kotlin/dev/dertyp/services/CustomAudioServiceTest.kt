package dev.dertyp.services

import dev.dertyp.Indexer
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
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

    @Test
    fun `uploadCustomAudio should handle conversion for non-FLAC`() = runBlocking {
        val indexer = mockk<Indexer>(relaxed = true)
        val storageService = mockk<StorageService>()
        val customPath = File(tempDir.toFile(), "custom").apply { mkdirs() }
        every { storageService.customAudioPath } returns customPath.absolutePath
        justRun { storageService.invalidate(any()) }

        val service = spyk(CustomAudioService(indexer, storageService))

        every { service["isFlac"](any<File>()) } returns false
        every { service["convert"](any<File>(), any<File>()) } answers {
            val output = it.invocation.args[1] as File
            output.writeText("converted data")
        }

        val deferred = CompletableDeferred<Unit>()
        deferred.complete(Unit)
        coEvery { indexer.queue(any(), any(), any(), any(), any()) } returns deferred

        val fileData = "fake mp3 data".toByteArray()
        val uuid = service.uploadCustomAudio(fileData, "test.mp3", null)

        assertNotNull(uuid)
        val targetFile = File(customPath, "$uuid.flac")
        assertEquals("converted data", targetFile.readText())
    }
}
