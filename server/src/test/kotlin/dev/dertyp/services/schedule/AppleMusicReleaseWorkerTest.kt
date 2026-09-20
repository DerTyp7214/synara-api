package dev.dertyp.services.schedule

import dev.dertyp.services.ImageService
import dev.dertyp.services.release.AppleMusicReleaseService
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

class AppleMusicReleaseWorkerTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `worker should call fetchFollowedArtistReleases`() = runBlocking {
        val appleMusicReleaseService = mockk<AppleMusicReleaseService>()
        coEvery { appleMusicReleaseService.fetchFollowedArtistReleases(any()) } returns emptyMap()

        startKoin {
            modules(module {
                single { appleMusicReleaseService }
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        val worker = AppleMusicReleaseWorker()
        worker.run()

        coVerify { appleMusicReleaseService.fetchFollowedArtistReleases(any()) }
    }
}
