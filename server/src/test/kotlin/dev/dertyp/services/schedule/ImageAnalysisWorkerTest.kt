package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.ImageMetadataTable
import dev.dertyp.db.ImageTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ImageService
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class ImageAnalysisWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "image_analysis_worker_test")
        dbQuery {
            SchemaUtils.create(ImageTable, ImageMetadataTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should analyze unanalyzed images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val imageService = mockk<ImageService>()
        val imageId = UUID.randomUUID()
        
        coEvery { imageService.getUnanalyzedImageIds() } returns listOf(imageId)
        coEvery { imageService.analyzeImage(imageId) } returns Unit

        startKoin {
            modules(module {
                single { imageService }
                single<ApplicationConfig> { MapApplicationConfig() }
            })
        }

        val worker = ImageAnalysisWorker()
        worker.run()

        coVerify { imageService.getUnanalyzedImageIds() }
        coVerify { imageService.analyzeImage(imageId) }
    }
}
