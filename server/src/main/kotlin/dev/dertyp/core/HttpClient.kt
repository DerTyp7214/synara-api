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

suspend inline fun <reified T> HttpClient.safeGet(url: String): T? = try {
    val response = get(url)
    if (response.status.isSuccess()) response.body<T>() else null
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

suspend fun HttpClient.safeGetImage(url: String): ByteArray? =
    safeGet<ByteArray>(url)?.takeIf { it.isImage() }

suspend inline fun <reified T> HttpClientQueueService.queuedGet(
    urlString: String,
    priority: HttpClientPriority = HttpClientPriority.NORMAL,
    noinline block: suspend HttpRequestBuilder.() -> Unit = {}
): T = enqueue(urlString, priority, block).body<T>()

suspend inline fun <reified T> HttpClientQueueService.safeQueuedGet(
    urlString: String,
    priority: HttpClientPriority = HttpClientPriority.NORMAL,
    noinline block: suspend HttpRequestBuilder.() -> Unit = {}
): T? = try {
    val response = enqueue(urlString, priority, block)
    if (response.status.isSuccess()) response.body<T>() else null
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

suspend fun HttpClientQueueService.safeQueuedGetImage(
    urlString: String,
    priority: HttpClientPriority = HttpClientPriority.NORMAL,
    block: suspend HttpRequestBuilder.() -> Unit = {}
): ByteArray? = safeQueuedGet<ByteArray>(urlString, priority, block)?.takeIf { it.isImage() }

@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class HttpClientQueueService : Service() {
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val hostQueues = ConcurrentHashMap<String, PriorityBlockingQueue<QueuedRequest>>()
    private val hostLastRequest = ConcurrentHashMap<String, Instant>()

    private data class RateLimitState(
        val limit: Int,
        val remaining: Int,
        val resetIn: Int,
        val updatedAt: Instant
    )

    private val hostRateLimits = ConcurrentHashMap<String, RateLimitState>()

    private val stopped = AtomicBoolean(false)

    private fun updateRateLimit(host: String, response: HttpResponse) {
        val limit = response.headers["X-RateLimit-Limit"]?.toIntOrNull()
            ?: response.headers["X-Rate-Limit-Limit"]?.toIntOrNull()
            ?: response.headers["RateLimit-Limit"]?.toIntOrNull()

        val remaining = response.headers["X-RateLimit-Remaining"]?.toIntOrNull()
            ?: response.headers["X-Rate-Limit-Remaining"]?.toIntOrNull()
            ?: response.headers["RateLimit-Remaining"]?.toIntOrNull()

        val resetIn = response.headers["X-RateLimit-Reset-In"]?.toIntOrNull()
            ?: response.headers["X-Rate-Limit-Reset"]?.toIntOrNull()
            ?: response.headers["RateLimit-Reset"]?.toIntOrNull()

        if (limit != null && remaining != null && resetIn != null) {
            hostRateLimits[host] = RateLimitState(limit, remaining, resetIn, Clock.System.now())
        } else if (response.status.value == 429) {
            val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                ?: response.headers["X-Retry-After"]?.toIntOrNull()
            if (retryAfter != null) {
                hostRateLimits[host] = RateLimitState(limit ?: 0, 0, retryAfter, Clock.System.now())
            }
        }
    }

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

    override suspend fun startService() {
        stopped.store(false)
        logger.info("Starting service")
    }

    override suspend fun stopService() {
        stopped.store(true)
        logger.info("Stopping service")
        super.stopService()
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
                    next?.deferred?.completeWith(Result.failure(IllegalStateException(STOPPED_MESSAGE)))
                    throw IllegalStateException(STOPPED_MESSAGE)
                }

                val next = queue.poll() ?: return@withLock

                val waitTime = Clock.System.now() - next.queuedAt

                if (waitTime > 2.seconds)
                    logger.info("Request (${next.urlString}) was $waitTime in queue")

                val last = hostLastRequest[host]
                val now = Clock.System.now()

                var delayTime = when {
                    host.contains("musicbrainz.org") -> 1.seconds
                    host.contains("linkresolver.synara.audio") -> 10.milliseconds
                    host.contains("theaudiodb.com") -> 500.milliseconds
                    host.contains("googleapis.com") || host.contains("youtube.com") -> 1.seconds
                    host.contains("listenbrainz.org") -> 10.milliseconds
                    host.contains("deezer.com") -> 100.milliseconds
                    host.contains("spotify.com") -> 100.milliseconds
                    host.contains("apple.com") -> 100.milliseconds
                    host.contains("api.acoustid.org") -> 340.milliseconds
                    else -> 250.milliseconds
                }

                hostRateLimits[host]?.let { rl ->
                    val passed = Clock.System.now() - rl.updatedAt
                    if (rl.remaining <= 0) {
                        val wait = rl.resetIn.seconds - passed
                        if (wait > 0.seconds) {
                            delayTime = maxOf(delayTime, wait)
                        }
                    } else {
                        val pace = (rl.resetIn.toDouble() / rl.remaining).seconds
                        delayTime = maxOf(delayTime, pace)
                    }
                }

                if (last != null) {
                    val diff = now - last
                    if (diff < delayTime) {
                        delay(delayTime - diff)
                    }
                }

                try {
                    val client = ApiClient.instance
                    var response = client.get(next.urlString) {
                        next.block(this)
                    }
                    updateRateLimit(host, response)

                    var attempts = 1
                    while (response.status.value == 429 && attempts < 3) {
                        val retryAfter =
                            response.headers["Retry-After"]?.toIntOrNull()?.seconds ?: (delayTime + 500.milliseconds)
                        logger.warn("Rate limit exceeded for $host (attempt $attempts), waiting $retryAfter before retry")
                        delay(retryAfter)
                        response = client.get(next.urlString) {
                            next.block(this)
                        }
                        updateRateLimit(host, response)
                        attempts++
                    }

                    next.deferred.complete(response)
                } catch (e: CancellationException) {
                    next.deferred.completeWith(Result.failure(e))
                    throw e
                } catch (e: Exception) {
                    logger.error("Error executing queued request for $host", e)
                    next.deferred.completeWith(Result.failure(e))
                } finally {
                    hostLastRequest[host] = Clock.System.now()
                }
            }
        } catch (e: Exception) {
            queue.remove(request)
            throw e
        }

        return request.deferred.await()
    }

    companion object {
        private const val STOPPED_MESSAGE = "HTTP queue stopped"
    }
}
