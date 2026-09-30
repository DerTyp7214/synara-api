package dev.dertyp.services.schedule

import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

enum class ItemOutcome { SUCCEEDED, SKIPPED, FAILED }

data class ItemCounts(val processed: Int, val succeeded: Int, val skipped: Int, val failed: Int) {
    companion object {
        val NONE = ItemCounts(0, 0, 0, 0)
    }
}

abstract class ItemWorker<T>(name: String) : Worker(name) {
    protected abstract val baseThreads: Int
    protected abstract val resultKey: String
    protected abstract val emptyMessage: String
    protected open val progressEvery: Int = 1
    protected open val logEvery: Int? = null
    protected open val timeout: Duration? = null

    protected abstract suspend fun loadItems(): List<T>
    protected abstract suspend fun process(item: T): ItemOutcome
    protected abstract fun progressMessage(current: Int, total: Int): String
    protected open fun startMessage(total: Int): String? = null
    protected open fun results(counts: ItemCounts): Map<String, Any?> = mapOf(resultKey to counts.processed)

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val items = loadItems()
        if (items.isEmpty()) {
            logger.info(emptyMessage)
            return results(ItemCounts.NONE)
        }

        val threads = baseThreads
        startMessage(items.size)?.let { logger.info(it) }

        val processed = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)

        val work: suspend () -> Unit = {
            runParallel(
                items = items,
                baseThreadCount = threads,
                onItemProcessed = { current ->
                    processed.accumulateAndGet(current) { a, b -> maxOf(a, b) }
                    if (current % progressEvery == 0 || current == items.size) {
                        val message = progressMessage(current, items.size)
                        onProgress(current.toDouble() / items.size * 100.0, message)
                        logEvery?.let { if (current % it == 0) logger.info(message) }
                    }
                }
            ) { item ->
                when (process(item)) {
                    ItemOutcome.SUCCEEDED -> succeeded.incrementAndGet()
                    ItemOutcome.SKIPPED -> skipped.incrementAndGet()
                    ItemOutcome.FAILED -> failed.incrementAndGet()
                }
            }
        }

        val limit = timeout
        val timedOut = if (limit == null) {
            work()
            false
        } else {
            withTimeoutOrNull(limit) { work() } == null
        }

        val counts = ItemCounts(processed.get(), succeeded.get(), skipped.get(), failed.get())
        if (!timedOut) return results(counts)
        logger.warn("Timed out after $limit with ${counts.processed}/${items.size} items processed")
        return results(counts) + ("timedOut" to true)
    }
}
