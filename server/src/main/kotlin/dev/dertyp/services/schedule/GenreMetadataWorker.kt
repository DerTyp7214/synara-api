package dev.dertyp.services.schedule

import dev.dertyp.services.MetadataFetchingService
import io.ktor.util.logging.KtorSimpleLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class GenreMetadataWorker : KoinComponent {
    private val logger = KtorSimpleLogger("GenreMetadataWorker")
    private val metadataFetchingService by inject<MetadataFetchingService>()

    private val isRunning = AtomicBoolean(false)

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("GenreMetadataWorker is already running. Skipping this run.")
            return emptyMap()
        }

        return try {
            logger.info("Starting GenreMetadataWorker")
            onProgress(0.0, "Starting GenreMetadataWorker")
            val results = metadataFetchingService.fetchAllGenresWithMbId { p, m ->
                onProgress(p, m)
            }
            logger.info("GenreMetadataWorker finished. Results: $results")
            results
        } catch (e: Exception) {
            logger.error("Error in GenreMetadataWorker", e)
            emptyMap()
        } finally {
            isRunning.store(false)
        }
    }
}
