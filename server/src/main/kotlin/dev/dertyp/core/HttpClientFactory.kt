package dev.dertyp.core

import dev.dertyp.services.Service
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import kotlinx.coroutines.job
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HttpClientFactory : Service() {
    private val sharedClients = ConcurrentHashMap<String, HttpClient>()
    private val openClients = ConcurrentHashMap.newKeySet<HttpClient>()

    val api: HttpClient get() = shared(API, OkHttp) { apiDefaults() }

    fun <T : HttpClientEngineConfig> shared(
        name: String,
        engineFactory: HttpClientEngineFactory<T>,
        engineConfig: T.() -> Unit = {},
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = sharedClients.computeIfAbsent(name) { create(engineFactory, engineConfig, configure) }

    fun sharedWithDefaultEngine(
        name: String,
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = sharedClients.computeIfAbsent(name) { track(HttpClient(configure)) }

    fun <T : HttpClientEngineConfig> create(
        engineFactory: HttpClientEngineFactory<T>,
        engineConfig: T.() -> Unit = {},
        configure: HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = track(
        HttpClient(engineFactory) {
            engine(engineConfig)
            configure()
        }
    )

    private fun track(client: HttpClient): HttpClient {
        openClients += client
        client.coroutineContext.job.invokeOnCompletion { openClients -= client }
        return client
    }

    override suspend fun stopService() {
        sharedClients.clear()
        openClients.toList().forEach { client ->
            try {
                client.close()
            } catch (e: Exception) {
                logger.warn("Failed to close HTTP client: ${e.message}")
            }
        }
        openClients.clear()
        super.stopService()
    }

    companion object {
        const val API = "api"
        const val LISTEN_BACKUP = "listen-backup"
        const val MIRROR = "mirror"
        const val REVERSE_PROXY = "reverse-proxy"
        const val PODCAST_FEED = "podcast-feed"
        const val PODCAST_MEDIA = "podcast-media"
        const val PODCAST_INDEX = "podcast-index"
        const val CREDENTIAL_SERVER = "credential-server"
        const val CREDENTIAL_SERVER_ADMIN = "credential-server-admin"
    }
}

fun HttpClientConfig<*>.timeouts(request: Duration, connect: Duration, socket: Duration) {
    install(HttpTimeout) {
        requestTimeoutMillis = if (request.isInfinite()) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else request.inWholeMilliseconds
        connectTimeoutMillis = connect.inWholeMilliseconds
        socketTimeoutMillis = socket.inWholeMilliseconds
    }
}

fun HttpClientConfig<*>.userAgent(agent: String) {
    install(UserAgent) { this.agent = agent }
}

fun HttpClientConfig<*>.jsonContent(json: Json = ApplicationScope.json) {
    install(ContentNegotiation) { json(json) }
}

fun HttpClientConfig<*>.gzipEncoding() {
    install(ContentEncoding) { gzip() }
}

@OptIn(ExperimentalSerializationApi::class)
fun HttpClientConfig<*>.apiDefaults() {
    install(ContentNegotiation) {
        json(ApplicationScope.json)
        protobuf()
    }
    timeouts(request = 1.minutes, connect = 30.seconds, socket = 30.seconds)
    gzipEncoding()
}
