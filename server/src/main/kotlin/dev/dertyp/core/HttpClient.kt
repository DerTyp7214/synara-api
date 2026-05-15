package dev.dertyp.core

import dev.dertyp.ApiClient
import dev.dertyp.services.Service
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

enum class HttpClientPriority {
    HIGH, NORMAL, LOW
}

suspend inline fun <reified T> HttpClient.safeGet(url: String) = try {
    get(url).body<T>()
} catch (_: Throwable) {
    null
}

suspend inline fun <reified T> HttpClient.queuedGet(
    urlString: String,
    priority: HttpClientPriority = HttpClientPriority.NORMAL,
    noinline block: suspend HttpRequestBuilder.() -> Unit = {}
) = ApiClient.queueInstance.enqueue(urlString, priority, block).body<T>()

suspend inline fun <reified T> HttpClient.safeQueuedGet(
    urlString: String,
    priority: HttpClientPriority = HttpClientPriority.NORMAL,
    noinline block: suspend HttpRequestBuilder.() -> Unit = {}
) = try {
    val response = ApiClient.queueInstance.enqueue(urlString, priority, block)
    if (response.status.isSuccess()) response.body<T>() else null
} catch (_: Throwable) {
    null
}

@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class HttpClientQueueService : Service() {
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val hostQueues = ConcurrentHashMap<String, PriorityBlockingQueue<QueuedRequest>>()
    private val hostLastRequest = ConcurrentHashMap<String, Instant>()

    private val stopped = AtomicBoolean(true)

    private data class QueuedRequest(
        val priority: HttpClientPriority,
        val queuedAt: Instant,
        val urlString: String,
        val block: suspend HttpRequestBuilder.() -> Unit,
        val deferred: CompletableDeferred<HttpResponse>
    ) : Comparable<QueuedRequest> {
        override fun compareTo(other: QueuedRequest): Int {
            if (priority != other.priority) return priority.ordinal.compareTo(other.priority.ordinal)
            return queuedAt.compareTo(other.queuedAt)
        }
    }

    init {
        CoroutineScope(Dispatchers.IO).launch {
            startService()
        }
    }

    override suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        logger.info("Starting service")
    }

    override suspend fun stopService() {
        stopped.store(true)
        logger.info("Stopping service")
    }

    suspend fun enqueue(
        urlString: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
        block: suspend HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        val host = try {
            Url(urlString).host
        } catch (_: Exception) {
            urlString
        }

        val deferred = CompletableDeferred<HttpResponse>()
        val request = QueuedRequest(priority, Clock.System.now(), urlString, block, deferred)

        val queue = hostQueues.computeIfAbsent(host) { PriorityBlockingQueue() }
        queue.put(request)

        val lock = hostLocks.computeIfAbsent(host) { Mutex() }

        try {
            lock.withLock {
                if (stopped.load()) {
                    val next = queue.poll()
                    next?.deferred?.completeWith(Result.failure(CancellationException("Service is stopped")))
                    throw CancellationException("Service is stopped")
                }

                val next = queue.poll() ?: return@withLock

                val waitTime = Clock.System.now() - next.queuedAt

                if (waitTime > 2.seconds)
                    logger.info("Request (${next.urlString}) was $waitTime in queue")

                val last = hostLastRequest[host]
                val now = Clock.System.now()

                val delayTime = when {
                    host.contains("musicbrainz.org") -> 1.seconds
                    host.contains("api.song.link") -> 6.seconds
                    host.contains("theaudiodb.com") -> 2.seconds
                    host.contains("googleapis.com") || host.contains("youtube.com") -> 1.seconds
                    else -> 250.milliseconds
                }

                if (last != null) {
                    val diff = now - last
                    if (diff < delayTime) {
                        delay(delayTime - diff)
                    }
                }

                try {
                    val response = ApiClient.instance.get(next.urlString) {
                        next.block(this)
                    }
                    next.deferred.complete(response)
                } catch (e: Exception) {
                    logger.error("Error executing queued request for $host", e)
                    next.deferred.completeWith(Result.failure(e))
                } finally {
                    hostLastRequest[host] = Clock.System.now()
                }
            }
        } catch (e: CancellationException) {
            queue.remove(request)
            throw e
        }

        return request.deferred.await()
    }
}
