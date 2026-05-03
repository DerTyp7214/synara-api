package dev.dertyp.services.schedule

import io.ktor.server.config.ApplicationConfig
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.floor
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalAtomicApi::class)
abstract class Worker(val name: String) : KoinComponent {
    protected val logger = KtorSimpleLogger(name)
    private val isRunning = AtomicBoolean(false)

    private val config by inject<ApplicationConfig>()

    protected val threadMultiplier: Double
        get() = config.propertyOrNull("workers.threadMultiplier")?.getString()?.toDoubleOrNull() ?: 1.0

    protected val grantedThreads = MutableStateFlow(0)

    /**
     * Runs the block for each item in parallel, dynamically scaling the number of coroutines
     * based on global server load and other active workers.
     */
    @OptIn(DelicateCoroutinesApi::class)
    protected suspend fun <T> runParallel(
        items: Iterable<T>,
        baseThreadCount: Int,
        onItemProcessed: suspend (Int) -> Unit = {},
        block: suspend (T) -> Unit
    ) = coroutineScope {
        val desired = (baseThreadCount * threadMultiplier).toInt().coerceAtLeast(1)
        val itemChannel = Channel<T>(Channel.UNLIMITED)
        val processedCount = AtomicInteger(0)
        
        registerWorker(name, desired, grantedThreads)
        grantedThreads.first { it > 0 }
        
        try {
            val activeWorkerJobs = ConcurrentHashMap<Int, Job>()
            val outerScope = this

            val supervisor = launch {
                grantedThreads.collectLatest { targetCount ->
                    for (i in 0 until targetCount) {
                        if (!activeWorkerJobs.containsKey(i)) {
                            activeWorkerJobs[i] = outerScope.launch {
                                try {
                                    for (item in itemChannel) {
                                        if (i >= grantedThreads.value) {
                                            if (!itemChannel.isClosedForSend) {
                                                try {
                                                    itemChannel.send(item)
                                                } catch (_: Exception) {}
                                            }
                                            break
                                        }
                                        try {
                                            block(item)
                                        } finally {
                                            onItemProcessed(processedCount.incrementAndGet())
                                        }
                                    }
                                } finally {
                                    activeWorkerJobs.remove(i)
                                }
                            }
                        }
                    }
                }
            }

            while (activeWorkerJobs.isEmpty() && items.any()) {
                yield()
            }

            items.forEach { itemChannel.send(it) }
            itemChannel.close()

            while (activeWorkerJobs.isNotEmpty()) {
                delay(10.milliseconds)
            }
            
            supervisor.cancel()
        } finally {
            unregisterWorker(name)
        }
    }

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

    companion object {
        private val activeWorkers = ConcurrentHashMap<String, Pair<Int, MutableStateFlow<Int>>>()
        private val mutex = Mutex()
        internal var overridenProcessorCount: Int? = null

        internal fun resetActiveWorkers() {
            activeWorkers.clear()
            overridenProcessorCount = null
        }

        private suspend fun registerWorker(name: String, desired: Int, flow: MutableStateFlow<Int>) {
            activeWorkers[name] = desired to flow
            recalculateAllocations()
        }

        private suspend fun unregisterWorker(name: String) {
            activeWorkers.remove(name)
            recalculateAllocations()
        }

        private suspend fun recalculateAllocations() = mutex.withLock {
            val cores = overridenProcessorCount ?: Runtime.getRuntime().availableProcessors()
            val leaveFree = if (cores > 4) 2 else 1
            val totalMaxSafe = (cores * 0.9).toInt().coerceAtMost(cores - leaveFree).coerceAtLeast(1)
            
            val totalDesired = activeWorkers.values.sumOf { it.first }
            
            if (totalDesired <= totalMaxSafe) {
                activeWorkers.forEach { (_, pair) -> pair.second.value = pair.first }
            } else {
                var remaining = totalMaxSafe
                val sortedWorkers = activeWorkers.toList().sortedBy { it.second.first }
                
                sortedWorkers.forEach { (_, pair) ->
                    val desired = pair.first
                    val share = floor(totalMaxSafe * (desired.toDouble() / totalDesired)).toInt().coerceAtLeast(1)
                    
                    val granted = share.coerceAtMost(remaining).coerceAtMost(desired)
                    pair.second.value = granted
                    remaining -= granted
                }
                
                if (remaining > 0) {
                    sortedWorkers.reversed().forEach { (_, pair) ->
                        if (remaining > 0 && pair.second.value < pair.first) {
                            pair.second.value += 1
                            remaining -= 1
                        }
                    }
                }
            }
        }
    }
}
