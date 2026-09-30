package dev.dertyp.services.podcast

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.gzipEncoding
import dev.dertyp.core.timeouts
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PodcastHttp : KoinComponent {
    private val httpClientFactory by inject<HttpClientFactory>()

    val feedClient: HttpClient
        get() = httpClientFactory.shared(HttpClientFactory.PODCAST_FEED, OkHttp) {
            timeouts(request = 60.seconds, connect = 20.seconds, socket = 30.seconds)
            gzipEncoding()
            install(HttpRedirect) {
                allowHttpsDowngrade = true
            }
            defaultRequest {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, FEED_ACCEPT)
            }
        }

    val mediaClient: HttpClient
        get() = httpClientFactory.shared(HttpClientFactory.PODCAST_MEDIA, OkHttp) {
            timeouts(request = Duration.INFINITE, connect = 20.seconds, socket = 60.seconds)
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
        } catch (e: Exception) {
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
