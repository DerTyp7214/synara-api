package dev.dertyp.services.podcast

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds

class PodcastHttp {
    val feedClient: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60.seconds.inWholeMilliseconds
            connectTimeoutMillis = 20.seconds.inWholeMilliseconds
            socketTimeoutMillis = 30.seconds.inWholeMilliseconds
        }
        install(ContentEncoding) {
            gzip()
        }
        install(HttpRedirect) {
            allowHttpsDowngrade = true
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, FEED_ACCEPT)
        }
    }

    val mediaClient: HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            connectTimeoutMillis = 20.seconds.inWholeMilliseconds
            socketTimeoutMillis = 60.seconds.inWholeMilliseconds
        }
        install(HttpRedirect) {
            allowHttpsDowngrade = true
        }
        defaultRequest {
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
    }

    fun requirePublicHttpUrl(url: String): Url {
        val parsed = try {
            Url(url)
        } catch (e: Throwable) {
            throw IllegalArgumentException("Not a valid URL: $url", e)
        }

        val scheme = parsed.protocol.name.lowercase()
        require(scheme == "http" || scheme == "https") { "Only http and https URLs are supported, got $scheme" }

        val host = parsed.host
        require(host.isNotBlank()) { "URL without a host: $url" }

        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw IllegalArgumentException("Host cannot be resolved: $host", e)
        }
        require(addresses.isNotEmpty()) { "Host cannot be resolved: $host" }
        require(
            addresses.none {
                it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress ||
                    it.isAnyLocalAddress || it.isMulticastAddress
            }
        ) { "Host resolves to a non public address: $host" }

        return parsed
    }

    companion object {
        const val USER_AGENT = "Synara/1.0 (podcast)"
        private const val FEED_ACCEPT = "application/rss+xml, application/xml, text/xml, */*"
    }
}
