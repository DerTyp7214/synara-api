package dev.dertyp.core

import dev.dertyp.ApiClient
import dev.dertyp.services.Service
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

suspend inline fun <reified T> HttpClient.safeGet(url: String) = try {
    get(url).body<T>()
} catch (_: Throwable) {
    null
}

suspend inline fun <reified T> HttpClient.queuedGet(
    urlString: String,
    noinline block: suspend HttpRequestBuilder.() -> Unit = {}
) = ApiClient.queueInstance.enqueue(urlString, block).body<T>()

@OptIn(ExperimentalAtomicApi::class, FlowPreview::class, ExperimentalTime::class)
class HttpClientQueueService : Service() {
    private val _queue = mutableListOf<suspend () -> Unit>()
    private val queueMutex = Mutex()

    private val queueUpdateFlow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)

    private val stopped = AtomicBoolean(true)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            startService()
        }
    }

    suspend fun startService() {
        if (!stopped.compareAndSet(expectedValue = true, newValue = false)) return
        logger.info("Starting service")

        coroutineScope {
            launch {
                queueUpdateFlow
                    .onStart { emit(Unit) }
                    .debounce(100)
                    .takeWhile { !stopped.load() }
                    .collect {
                        execute { !stopped.load() }
                    }
            }
        }

        logger.info("Stopping service")
        stopped.store(true)
    }

    fun stopService() {
        stopped.store(true)
    }

    private suspend fun execute(isAlive: () -> Boolean) {
        while (isAlive()) {
            val task = queueMutex.withLock {
                if (_queue.isEmpty()) null else _queue.removeAt(0)
            }

            if (task == null) break

            delay(250)

            try {
                task()
            } catch (e: Exception) {
                logger.error("Error executing queued request", e)
            }
        }
    }

    suspend fun enqueue(
        urlString: String,
        block: suspend HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        val deferred = CompletableDeferred<HttpResponse>()
        val queuedAt = Clock.System.now()

        queueMutex.withLock {
            _queue.add {
                try {
                    val waitTime = Clock.System.now() - queuedAt

                    if (waitTime > 2.seconds)
                        logger.info("Request ($urlString) was ${waitTime.inWholeMilliseconds}ms in queue")

                    val response = ApiClient.instance.get(urlString) { block() }
                    deferred.complete(response)
                } catch (e: Exception) {
                    deferred.completeExceptionally(e)
                }
            }
        }

        queueUpdateFlow.tryEmit(Unit)

        return deferred.await()
    }
}