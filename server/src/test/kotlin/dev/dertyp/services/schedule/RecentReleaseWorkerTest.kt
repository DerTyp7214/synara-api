package dev.dertyp.services.schedule

import dev.dertyp.services.ImageService
import dev.dertyp.services.ReleaseService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class RecentReleaseWorkerTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker should call fetchNewReleases`() = runBlocking {
        val releaseService = mockk<ReleaseService>()
        coEvery { releaseService.fetchNewReleases() } returns emptyMap()

        startKoin {
            modules(module {
                single { releaseService }
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        val worker = RecentReleaseWorker()
        worker.run()

        coVerify { releaseService.fetchNewReleases() }
    }
}
