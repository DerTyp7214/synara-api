package dev.dertyp.services.schedule

import dev.dertyp.services.AudioAnalysisService
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class AudioTimelineBackfillWorkerTest : KoinTest {

    private fun setup(audioAnalysisService: AudioAnalysisService) {
        startKoin {
            modules(module {
                single { audioAnalysisService }
                single<ApplicationConfig> { MapApplicationConfig() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker drains every missing and stale id without a limit`() = runBlocking {
        val audioAnalysisService = mockk<AudioAnalysisService>()
        val songIds = listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val staleIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        coEvery { audioAnalysisService.getSongIdsMissingTimeline(null) } returns songIds
        coEvery { audioAnalysisService.getSongIdsWithStaleTimeline(null) } returns staleIds
        coEvery { audioAnalysisService.analyzeSong(any()) } returns Unit
        coEvery { audioAnalysisService.refreshEnvelopes(any()) } returns Unit

        setup(audioAnalysisService)

        val worker = AudioTimelineBackfillWorker()
        val result = worker.run()

        coVerify { audioAnalysisService.getSongIdsMissingTimeline(null) }
        coVerify { audioAnalysisService.getSongIdsWithStaleTimeline(null) }
        songIds.forEach { coVerify { audioAnalysisService.analyzeSong(it) } }
        staleIds.forEach { coVerify { audioAnalysisService.refreshEnvelopes(it) } }

        assertEquals(songIds.size, result["analyzedCount"])
        assertEquals(staleIds.size, result["refreshedCount"])
        assertEquals(0, result["skippedCount"])
    }

    @Test
    fun `worker skips remaining ids once the run budget has passed`() = runBlocking {
        val audioAnalysisService = mockk<AudioAnalysisService>()
        val songIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val staleIds = listOf(UUID.randomUUID())

        coEvery { audioAnalysisService.getSongIdsMissingTimeline(null) } returns songIds
        coEvery { audioAnalysisService.getSongIdsWithStaleTimeline(null) } returns staleIds
        coEvery { audioAnalysisService.analyzeSong(any()) } returns Unit
        coEvery { audioAnalysisService.refreshEnvelopes(any()) } returns Unit

        setup(audioAnalysisService)

        val worker = AudioTimelineBackfillWorker()
        var callCount = 0
        worker.clock = {
            callCount++
            if (callCount == 1) 0L else Long.MAX_VALUE
        }

        val result = worker.run()

        coVerify(exactly = 0) { audioAnalysisService.analyzeSong(any()) }
        coVerify(exactly = 0) { audioAnalysisService.refreshEnvelopes(any()) }

        assertEquals(0, result["analyzedCount"])
        assertEquals(0, result["refreshedCount"])
        assertEquals(songIds.size + staleIds.size, result["skippedCount"])
    }
}
