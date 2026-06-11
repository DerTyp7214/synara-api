package dev.dertyp.services.schedule

import dev.dertyp.services.AlbumService
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.IMetadataService.Feature
import dev.dertyp.services.metadata.IMetadataService.MetadataType
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.ApplicationConfig
import io.mockk.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class IsrcProviderEnrichmentWorkerTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkObject(MetadataService)
        unmockkObject(MetadataType)
    }

    @Test
    fun `worker should finish with zero results if no items to enrich`() = runBlocking {
        val albumService = mockk<AlbumService>()
        val songService = mockk<SongService>()
        val recentReleaseWorker = mockk<RecentReleaseWorker>()
        val environment = mockk<ApplicationEnvironment>(relaxed = true)
        val config = mockk<ApplicationConfig>(relaxed = true)
        val metadataService = mockk<MetadataService>()

        mockkObject(MetadataService)
        mockkObject(MetadataType)

        val testType = MetadataType("test")
        every { MetadataType.all() } returns listOf(testType)
        every { MetadataService.getMetadataService(testType, any()) } returns metadataService

        coEvery { recentReleaseWorker.active } returns false
        coEvery { songService.songIdsForIsrcEnrichment(any()) } returns emptyFlow()
        coEvery { albumService.albumIdsForBarcodeEnrichment(any()) } returns emptyFlow()
        
        every { metadataService.supportedFeatures } returns setOf(Feature.GET_TRACK_BY_ISRC)

        startKoin {
            modules(module {
                single { albumService }
                single { songService }
                single { recentReleaseWorker }
                single { environment }
                single { config }
            })
        }

        val worker = IsrcProviderEnrichmentWorker()
        val result = worker.run()

        assertEquals(0, result["totalFound"])
    }
}
