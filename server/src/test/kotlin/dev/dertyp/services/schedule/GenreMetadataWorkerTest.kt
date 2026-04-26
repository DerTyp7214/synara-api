package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.services.MetadataFetchingService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class GenreMetadataWorkerTest : KoinTest {

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "genre_metadata_worker_test")
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `worker should call fetchAllGenresWithMbId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val metadataFetchingService = mockk<MetadataFetchingService>()
        coEvery { metadataFetchingService.fetchAllGenresWithMbId(any()) } returns emptyMap()

        startKoin {
            modules(module {
                single { metadataFetchingService }
            })
        }

        val worker = GenreMetadataWorker()
        worker.run()

        coVerify { metadataFetchingService.fetchAllGenresWithMbId(any()) }
    }
}
