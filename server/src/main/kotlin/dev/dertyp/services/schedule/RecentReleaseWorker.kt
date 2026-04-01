package dev.dertyp.services.schedule

import dev.dertyp.services.ReleaseService
import io.ktor.util.logging.KtorSimpleLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class RecentReleaseWorker : KoinComponent {
    private val logger = KtorSimpleLogger("RecentReleaseWorker")
    private val releaseService by inject<ReleaseService>()
    private val isRunning = AtomicBoolean(false)

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("RecentReleaseWorker is already running. Skipping this run.")
            return emptyMap()
        }

        return try {
            logger.info("Starting RecentReleaseWorker")
            val results = releaseService.fetchNewReleases(onProgress)
            logger.info("RecentReleaseWorker finished")
            results
        } catch (e: Exception) {
            logger.error("Error in RecentReleaseWorker: ${e.message}", e)
            emptyMap()
        } finally {
            isRunning.store(false)
        }
    }
}
