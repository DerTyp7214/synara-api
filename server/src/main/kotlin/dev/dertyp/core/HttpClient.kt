package dev.dertyp.core

import dev.dertyp.ApiClient
import dev.dertyp.services.Service
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

suspend inline fun <reified T> HttpClient.safeGet(url: String) = try {
    get(url).body<T>()
} catch (_: Throwable) {
    null
}

suspend inline fun <reified T> HttpClient.queuedGet(
    urlString: String,
    noinline block: suspend HttpRequestBuilder.() -> Unit = {}
) = ApiClient.queueInstance.enqueue(urlString, block).body<T>()

@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
class HttpClientQueueService : Service() {
    private val hostLocks = ConcurrentHashMap<String, Mutex>()
    private val hostLastRequest = ConcurrentHashMap<String, Instant>()

    private val stopped = AtomicBoolean(true)

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
        block: suspend HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        val queuedAt = Clock.System.now()
        val host = try {
            Url(urlString).host
        } catch (_: Exception) {
            urlString
        }

        val lock = hostLocks.computeIfAbsent(host) { Mutex() }

        return lock.withLock {
            if (stopped.load()) throw CancellationException("Service is stopped")

            val waitTime = Clock.System.now() - queuedAt

            if (waitTime > 2.seconds)
                logger.info("Request ($urlString) was ${waitTime.inWholeMilliseconds}ms in queue")

            val last = hostLastRequest[host]
            val now = Clock.System.now()

            val delayTime = when {
                host.contains("musicbrainz.org") -> 1.seconds
                host.contains("api.song.link") -> 6.seconds
                else -> 250.milliseconds
            }

            if (last != null) {
                val diff = now - last
                if (diff < delayTime) {
                    delay(delayTime - diff)
                }
            }

            try {
                val response = ApiClient.instance.get(urlString) { block() }
                hostLastRequest[host] = Clock.System.now()
                response
            } catch (e: Exception) {
                logger.error("Error executing queued request for $host", e)
                throw e
            }
        }
    }
}
