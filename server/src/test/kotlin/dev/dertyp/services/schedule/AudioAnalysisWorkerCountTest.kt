package dev.dertyp.services.schedule

import dev.dertyp.config.ServerConfig
import dev.dertyp.services.AudioAnalysisService
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
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

class AudioAnalysisWorkerCountTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker reports the number of analyzed songs`() = runBlocking {
        val audioAnalysisService = mockk<AudioAnalysisService>()
        val songIds = List(7) { UUID.randomUUID() }

        coEvery { audioAnalysisService.getUnanalyzedSongIds() } returns songIds
        coEvery { audioAnalysisService.analyzeSong(any()) } returns Unit

        startKoin {
            modules(module {
                single { audioAnalysisService }
                single<ApplicationConfig> { MapApplicationConfig() }
                single { ServerConfig(get()) }
            })
        }

        val result = AudioAnalysisWorker().run()

        assertEquals(7, result["analyzedCount"])
    }

    @Test
    fun `worker reports zero when nothing is left to analyze`() = runBlocking {
        val audioAnalysisService = mockk<AudioAnalysisService>()
        coEvery { audioAnalysisService.getUnanalyzedSongIds() } returns emptyList()

        startKoin {
            modules(module {
                single { audioAnalysisService }
                single<ApplicationConfig> { MapApplicationConfig() }
                single { ServerConfig(get()) }
            })
        }

        val result = AudioAnalysisWorker().run()

        assertEquals(0, result["analyzedCount"])
    }
}
