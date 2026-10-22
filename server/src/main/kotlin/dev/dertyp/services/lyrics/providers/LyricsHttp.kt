package dev.dertyp.services.lyrics.providers

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.services.LrcLibService
import dev.dertyp.services.Service
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import org.koin.core.component.inject
import kotlin.math.abs
import kotlin.time.Duration

sealed interface HttpFetch {
    class Body(val bytes: ByteArray) : HttpFetch {
        fun text(): String = bytes.decodeToString().removePrefix(BOM)
    }

    data object Missing : HttpFetch

    class Failed(val cause: Throwable) : HttpFetch
}

class LyricsHttpException(message: String) : Exception(message)

const val BOM = "﻿"

inline fun HttpFetch.toResult(onBody: (HttpFetch.Body) -> ProviderResult): ProviderResult = when (this) {
    is HttpFetch.Missing -> ProviderResult.Miss
    is HttpFetch.Failed -> ProviderResult.Error(cause)
    is HttpFetch.Body -> try {
        onBody(this)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProviderResult.Error(e)
    }
}

fun <T> List<T>.closestByDuration(targetSec: Int?, durationSec: (T) -> Int?): T? {
    if (targetSec == null) return firstOrNull()
    return minByOrNull { item -> durationSec(item)?.let { abs(it - targetSec) } ?: Int.MAX_VALUE }
}

class LyricsHttp : Service() {
    private val queue by inject<HttpClientQueueService>()

    suspend fun get(
        url: String,
        timeout: Duration,
        priority: HttpClientPriority,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpFetch = fetch(url, HttpMethod.Get, timeout, priority, block)

    suspend fun postJson(
        url: String,
        body: JsonElement,
        timeout: Duration,
        priority: HttpClientPriority,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpFetch = fetch(url, HttpMethod.Post, timeout, priority) {
        setBody(TextContent(body.toString(), ContentType.Application.Json))
        block()
    }

    private suspend fun fetch(
        url: String,
        method: HttpMethod,
        timeout: Duration,
        priority: HttpClientPriority,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpFetch {
        var current = url
        var redirects = 0
        while (true) {
            val host = Url(current).host
            try {
                val response = queue.enqueueRequest(current, priority, method, followRedirects = false) {
                    header(HttpHeaders.UserAgent, LrcLibService.USER_AGENT)
                    timeout { requestTimeoutMillis = timeout.inWholeMilliseconds }
                    block()
                }
                val status = response.status.value
                if (status in 300..399) {
                    val location = response.headers[HttpHeaders.Location]
                        ?: return HttpFetch.Failed(LyricsHttpException("Redirect without a location from $host"))
                    val next = URLBuilder(Url(current)).takeFrom(location).build()
                    if (next.host != host) {
                        return HttpFetch.Failed(LyricsHttpException("Refused redirect from $host to ${next.host}"))
                    }
                    if (++redirects > MAX_REDIRECTS) {
                        return HttpFetch.Failed(LyricsHttpException("Too many redirects for $url"))
                    }
                    current = next.toString()
                    continue
                }
                if (status in MISS_STATUSES) return HttpFetch.Missing
                if (!response.status.isSuccess()) {
                    return HttpFetch.Failed(LyricsHttpException("HTTP $status from $host"))
                }
                if ((response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BODY_BYTES) {
                    return HttpFetch.Failed(LyricsHttpException("Response from $host exceeds $MAX_BODY_BYTES bytes"))
                }
                val bytes = response.bodyAsBytes()
                if (bytes.size > MAX_BODY_BYTES) {
                    return HttpFetch.Failed(LyricsHttpException("Response from $host exceeds $MAX_BODY_BYTES bytes"))
                }
                return HttpFetch.Body(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return HttpFetch.Failed(e)
            }
        }
    }

    companion object {
        const val MAX_BODY_BYTES = 512 * 1024
        const val MAX_REDIRECTS = 3
        val MISS_STATUSES = setOf(401, 403, 404, 410)
    }
}
