package dev.dertyp.services

import dev.dertyp.db.RpcCallEventTable
import dev.dertyp.db.RpcCallStatsTable
import dev.dertyp.db.RpcCallTotalsTable
import dev.dertyp.dbQuery
import io.ktor.server.config.*
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

data class MetricsConfig(
    val enabled: Boolean = true,
    val flushIntervalSeconds: Long = 60,
    val eventLogRetentionHours: Long = 24,
    val maxBufferedEvents: Int = 100_000,
)

fun ApplicationConfig.toMetricsConfig(): MetricsConfig = MetricsConfig(
    enabled = propertyOrNull("metrics.enabled")?.getString()?.toBoolean() ?: true,
    flushIntervalSeconds = propertyOrNull("metrics.flushIntervalSeconds")?.getString()?.toLongOrNull() ?: 60,
    eventLogRetentionHours = propertyOrNull("metrics.eventLogRetentionHours")?.getString()?.toLongOrNull() ?: 24,
)

class RpcMetricsCollector(private val config: MetricsConfig) : Service() {
    val enabled: Boolean get() = config.enabled

    private data class BucketKey(
        val service: String,
        val method: String,
        val username: String,
        val bucketStart: Long,
    )

    private data class RecordedEvent(
        val service: String,
        val method: String,
        val username: String,
        val timestamp: Long,
    )

    private val counters = ConcurrentHashMap<BucketKey, LongAdder>()
    private val events = ConcurrentLinkedQueue<RecordedEvent>()
    private val bufferedEventCount = AtomicInteger(0)
    private val droppedEvents = AtomicLong(0)

    fun record(service: String, method: String, username: String) {
        if (!config.enabled) return

        val now = Instant.now()
        val bucketStart = now.truncatedTo(ChronoUnit.HOURS).toEpochMilli()
        counters.computeIfAbsent(BucketKey(service, method, username, bucketStart)) { LongAdder() }.increment()

        if (bufferedEventCount.get() < config.maxBufferedEvents) {
            events.add(RecordedEvent(service, method, username, now.toEpochMilli()))
            bufferedEventCount.incrementAndGet()
        } else {
            droppedEvents.incrementAndGet()
        }
    }

    suspend fun flush() {
        if (!config.enabled) return

        val currentBucket = Instant.now().truncatedTo(ChronoUnit.HOURS).toEpochMilli()
        val deltas = HashMap<BucketKey, Long>()
        val iterator = counters.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val delta = entry.value.sumThenReset()
            if (delta > 0) deltas[entry.key] = delta

            if (entry.key.bucketStart < currentBucket) iterator.remove()
        }

        val drainedEvents = ArrayList<RecordedEvent>()
        while (true) {
            val event = events.poll() ?: break
            bufferedEventCount.decrementAndGet()
            drainedEvents.add(event)
        }

        val dropped = droppedEvents.getAndSet(0)
        if (dropped > 0) {
            logger.warn("RPC metrics event buffer full: dropped $dropped event(s) since last flush (aggregate counts unaffected)")
        }

        if (deltas.isEmpty() && drainedEvents.isEmpty()) return

        dbQuery {
            for ((key, delta) in deltas) {
                RpcCallTotalsTable.upsert(
                    RpcCallTotalsTable.service, RpcCallTotalsTable.method, RpcCallTotalsTable.username,
                    onUpdate = { it[RpcCallTotalsTable.count] = RpcCallTotalsTable.count + delta },
                ) {
                    it[service] = key.service
                    it[method] = key.method
                    it[username] = key.username
                    it[count] = delta
                }
                RpcCallStatsTable.upsert(
                    RpcCallStatsTable.service, RpcCallStatsTable.method, RpcCallStatsTable.username, RpcCallStatsTable.bucketStart,
                    onUpdate = { it[RpcCallStatsTable.count] = RpcCallStatsTable.count + delta },
                ) {
                    it[service] = key.service
                    it[method] = key.method
                    it[username] = key.username
                    it[bucketStart] = key.bucketStart
                    it[count] = delta
                }
            }

            if (drainedEvents.isNotEmpty()) {
                RpcCallEventTable.batchInsert(drainedEvents) { event ->
                    this[RpcCallEventTable.service] = event.service
                    this[RpcCallEventTable.method] = event.method
                    this[RpcCallEventTable.username] = event.username
                    this[RpcCallEventTable.timestamp] = event.timestamp
                }
                val cutoff = Clock.System.now() - config.eventLogRetentionHours.hours
                RpcCallEventTable.deleteWhere { RpcCallEventTable.timestamp less cutoff.toEpochMilliseconds() }
            }
        }
    }

    suspend fun runFlushLoop() {
        if (!config.enabled) return
        while (true) {
            delay(config.flushIntervalSeconds.seconds)
            try {
                flush()
            } catch (e: Exception) {
                logger.error("RPC metrics flush failed", e)
            }
        }
    }
}
