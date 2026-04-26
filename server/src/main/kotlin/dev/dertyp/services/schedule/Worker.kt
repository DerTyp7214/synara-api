package dev.dertyp.services.schedule

import io.ktor.util.logging.KtorSimpleLogger
import org.koin.core.component.KoinComponent
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
abstract class Worker(val name: String) : KoinComponent {
    protected val logger = KtorSimpleLogger(name)
    private val isRunning = AtomicBoolean(false)

    protected abstract suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int>

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("$name is already running. Skipping this run.")
            return emptyMap()
        }

        return try {
            logger.info("Starting $name")
            onProgress(0.0, "Starting $name")
            val result = execute(onProgress)
            onProgress(100.0, "$name finished")
            logger.info("$name finished: $result")
            result
        } catch (e: Exception) {
            logger.error("Error in $name", e)
            mapOf("error" to 1)
        } finally {
            isRunning.store(false)
        }
    }
}
