package dev.dertyp.services.schedule

import dev.dertyp.config.ServerConfig
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

class WorkerAlreadyRunningException(val workerName: String) : Exception("$workerName is already running")

internal fun availableCores(): Int = Runtime.getRuntime().availableProcessors()

@OptIn(ExperimentalAtomicApi::class)
abstract class Worker(val name: String) : KoinComponent {
    protected val logger = KtorSimpleLogger(name)
    private val isRunning = AtomicBoolean(false)
    val active: Boolean
        get() = isRunning.load()

    protected val serverConfig by inject<ServerConfig>()

    protected val threadMultiplier: Double
        get() = serverConfig.workers.threadMultiplier

    protected val grantedThreads = MutableStateFlow(0)

    /**
     * Runs the block for each item in parallel, dynamically scaling the number of coroutines
     * based on global server load and other active workers.
     */
    protected suspend fun <T> runParallel(
        items: Iterable<T>,
        baseThreadCount: Int,
        onItemProcessed: suspend (Int) -> Unit = {},
        block: suspend (T) -> Unit
    ) = runParallel(
        items = flow { items.forEach { emit(it) } },
        baseThreadCount = baseThreadCount,
        onItemProcessed = onItemProcessed,
        block = block
    )

    /**
     * Runs the block for each item from the flow in parallel, dynamically scaling the number of coroutines.
     * Uses a buffered channel to avoid pre-fetching everything into memory.
     */
    @OptIn(DelicateCoroutinesApi::class)
    protected suspend fun <T> runParallel(
        items: Flow<T>,
        baseThreadCount: Int,
        workerName: String = name,
        onItemProcessed: suspend (Int) -> Unit = {},
        block: suspend (T) -> Unit
    ) = coroutineScope {
        val desired = (baseThreadCount * threadMultiplier).toInt().coerceAtLeast(1)
        val itemChannel = Channel<T>(Channel.BUFFERED)
        val processedCount = AtomicInteger(0)
        val localGrantedThreads = if (workerName == name) grantedThreads else MutableStateFlow(0)
        
        registerWorker(workerName, desired, localGrantedThreads)
        localGrantedThreads.first { it > 0 }
        
        try {
            val activeWorkerJobs = ConcurrentHashMap<Int, Job>()
            val outerScope = this

            val supervisor = launch {
                while (isActive) {
                    if (itemChannel.isClosedForReceive) break

                    val targetCount = localGrantedThreads.value
                    for (i in 0 until targetCount) {
                        if (activeWorkerJobs.containsKey(i)) continue

                        val job = outerScope.launch(start = CoroutineStart.LAZY) {
                            val thisJob = coroutineContext.job
                            try {
                                while (isActive) {
                                    if (i >= localGrantedThreads.value) break

                                    val result = itemChannel.tryReceive()
                                    val item = when {
                                        result.isSuccess -> result.getOrThrow()
                                        result.isClosed -> break
                                        else -> try {
                                            itemChannel.receive()
                                        } catch (_: ClosedReceiveChannelException) {
                                            break
                                        }
                                    }

                                    try {
                                        block(item)
                                    } catch (e: Throwable) {
                                        if (thisJob.isActive) onItemProcessed(processedCount.incrementAndGet())
                                        throw e
                                    }
                                    onItemProcessed(processedCount.incrementAndGet())
                                }
                            } finally {
                                activeWorkerJobs.remove(i, thisJob)
                            }
                        }

                        if (activeWorkerJobs.putIfAbsent(i, job) == null) {
                            job.start()
                        } else {
                            job.cancel()
                        }
                    }
                    delay(50.milliseconds)
                }
            }

            items.collect { itemChannel.send(it) }
            itemChannel.close()

            while (isActive) {
                if (activeWorkerJobs.isEmpty() && itemChannel.isClosedForReceive) break
                delay(10.milliseconds)
            }
            
            supervisor.cancel()
        } finally {
            unregisterWorker(workerName)
        }
    }

    protected abstract suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?>

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Any?> =
        exclusive(onSkip = { emptyMap() }) { perform(onProgress) }

    suspend fun <R> runExclusive(
        block: suspend (runWorker: suspend (onProgress: suspend (Double, String) -> Unit) -> Map<String, Any?>) -> R
    ): R = exclusive(onSkip = { throw WorkerAlreadyRunningException(name) }) {
        block { onProgress -> perform(onProgress) }
    }

    private suspend fun <R> exclusive(onSkip: () -> R, block: suspend () -> R): R {
        if (!isRunning.compareAndSet(expectedValue = false, newValue = true)) {
            logger.info("$name is already running. Skipping this run.")
            return onSkip()
        }

        return try {
            block()
        } finally {
            isRunning.store(false)
        }
    }

    private suspend fun perform(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return try {
            logger.info("Starting $name")
            onProgress(0.0, "Starting $name")
            val result = execute(onProgress)
            onProgress(100.0, "$name finished")
            logger.info("$name finished: $result")
            result
        } catch (e: Exception) {
            logger.error("Error in $name", e)
            throw e
        }
    }

    companion object {
        private val activeWorkers = ConcurrentHashMap<String, Pair<Int, MutableStateFlow<Int>>>()
        private val mutex = Mutex()

        fun isActive(name: String): Boolean = activeWorkers.containsKey(name)

        internal suspend fun registerWorker(name: String, desired: Int, flow: MutableStateFlow<Int>) {
            activeWorkers[name] = desired to flow
            recalculateAllocations()
        }

        internal suspend fun unregisterWorker(name: String) {
            activeWorkers.remove(name)
            recalculateAllocations()
        }

        private suspend fun recalculateAllocations() = mutex.withLock {
            val cores = availableCores()
            val leaveFree = when {
                cores <= 1 -> 0
                cores <= 4 -> 1
                else -> 2
            }
            val totalMaxSafe = (cores * 0.9).toInt()
                .coerceAtMost(cores - leaveFree)
                .coerceAtLeast(1)
            
            val totalDesired = activeWorkers.values.sumOf { it.first }
            
            if (totalDesired <= totalMaxSafe) {
                activeWorkers.forEach { (_, pair) -> pair.second.value = pair.first }
            } else {
                var remaining = totalMaxSafe
                val sortedWorkers = activeWorkers.toList().sortedBy { it.second.first }
                
                val minThreadsPerWorker = if (totalMaxSafe >= activeWorkers.size) 1 else 0

                sortedWorkers.forEach { (_, pair) ->
                    val desired = pair.first
                    val share = floor(totalMaxSafe * (desired.toDouble() / totalDesired)).toInt()
                        .coerceAtLeast(minThreadsPerWorker)
                    
                    val granted = share.coerceAtMost(remaining).coerceAtMost(desired)
                    pair.second.value = granted
                    remaining -= granted
                }
                
                while (remaining > 0) {
                    var anyAdded = false
                    for ((_, pair) in sortedWorkers) {
                        if (remaining > 0 && pair.second.value < pair.first) {
                            pair.second.value += 1
                            remaining -= 1
                            anyAdded = true
                        }
                    }
                    if (!anyAdded) break
                }
            }
        }
    }
}
