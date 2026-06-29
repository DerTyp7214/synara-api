package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.Url
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.tryGetString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class LinkResolverService(environment: ApplicationEnvironment) : Service() {
    private val baseUrl = "https://linkresolver.synara.audio"
    private val apiKey = environment.config.tryGetString("linkresolver.apiKey") ?: ""

    val enabled: Boolean get() = apiKey.isNotBlank()

    @Volatile
    private var supportedHosts: List<String> = DEFAULT_SUPPORTED_HOSTS

    @Volatile
    private var supportedFetchedAt: Instant? = null

    private val supportedRefreshMutex = Mutex()

    suspend fun refreshSupported() {
        if (!enabled) return
        supportedRefreshMutex.withLock {
            try {
                val response = ApiClient.queueInstance.enqueue("$baseUrl/supported", priority = HttpClientPriority.NORMAL) {
                    header("X-API-Key", apiKey)
                }
                if (response.status.value in 200..299) {
                    val supported = response.body<SupportedResponse>()
                    if (supported.urlHosts.isNotEmpty()) supportedHosts = supported.urlHosts
                    supportedFetchedAt = Clock.System.now()
                    logger.info("Refreshed LinkResolver supported hosts: ${supportedHosts.size}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to refresh LinkResolver supported inputs, keeping ${supportedHosts.size} cached host(s)", e)
            }
        }
    }

    private suspend fun ensureSupportedFresh() {
        if (!enabled) return
        val fetchedAt = supportedFetchedAt
        if (fetchedAt != null && Clock.System.now() - fetchedAt < SUPPORTED_TTL) return
        refreshSupported()
    }

    fun isSupportedUrl(url: String): Boolean {
        val host = try {
            Url(url).host.removePrefix("www.")
        } catch (_: Exception) {
            return false
        }
        if (host.isBlank()) return false
        return supportedHosts.any { hostMatches(it, host) }
    }

    suspend fun batchResolve(
        urls: Collection<String>,
        isrc: String? = null,
        upc: String? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> {
        if (isrc != null) {
            val resolved = resolve(isrc = isrc, priority = priority)
            if (resolved.isNotEmpty()) return resolved
        }

        if (upc != null) {
            val resolved = resolve(upc = upc, priority = priority)
            if (resolved.isNotEmpty()) return resolved
        }

        ensureSupportedFresh()
        for (url in urls) {
            if (!isSupportedUrl(url)) continue
            val resolved = resolve(url = url, priority = priority)
            if (resolved.isNotEmpty()) return resolved
        }

        return emptyList()
    }

    suspend fun resolvePlatformLinks(
        url: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> {
        ensureSupportedFresh()
        if (!isSupportedUrl(url)) return emptyList()
        return resolve(url = url, priority = priority)
    }

    private suspend fun resolve(
        url: String? = null,
        isrc: String? = null,
        upc: String? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> {
        if (url == null && isrc == null && upc == null) return emptyList()
        if (!enabled) return emptyList()

        return try {
            val response = ApiClient.queueInstance.enqueue("$baseUrl/resolve", priority = priority) {
                header("X-API-Key", apiKey)
                when {
                    url != null -> parameter("url", url)
                    isrc != null -> parameter("isrc", isrc)
                    upc != null -> parameter("upc", upc)
                }
            }
            if (response.status.value in 200..299) {
                response.body<LinkResolverResponse>().links.values.toList()
            } else emptyList()
        } catch (e: Exception) {
            val id = url ?: isrc ?: upc
            logger.error("Error resolving platform links via LinkResolver for $id", e)
            emptyList()
        }
    }

    private fun hostMatches(pattern: String, host: String): Boolean {
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(1)
            return host == suffix.removePrefix(".") || host.endsWith(suffix)
        }
        return pattern == host
    }

    @Serializable
    private data class LinkResolverResponse(val links: Map<String, String>)

    @Serializable
    private data class SupportedResponse(
        val urlHosts: List<String> = emptyList(),
        val isrc: Boolean = false,
        val upc: Boolean = false,
    )

    companion object {
        private val SUPPORTED_TTL: Duration = 24.hours

        private val DEFAULT_SUPPORTED_HOSTS = listOf(
            "open.spotify.com",
            "tidal.com",
            "listen.tidal.com",
            "*.music.apple.com",
            "deezer.com",
            "*.deezer.com",
            "shazam.com",
            "shz.am",
            "link.shazam.com",
        )
    }
}
