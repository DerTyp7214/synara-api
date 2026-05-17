package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.http.Url
import kotlinx.serialization.Serializable

class OdesliService : Service() {
    private val supportedDomains = setOf(
        "spotify.com", "apple.com", "itunes.apple.com", "youtube.com", "youtu.be",
        "amazon.com", "amazon.de", "amazon.co.uk", "amazon.co.jp", "deezer.com",
        "tidal.com", "bandcamp.com", "soundcloud.com", "pandora.com", "napster.com",
        "yandex.ru", "yandex.com", "audius.co", "audiomack.com", "anghami.com",
        "boomplay.com", "beatport.com", "spinrilla.com", "tiktok.com"
    )

    fun canResolve(url: String): Boolean {
        val host = try {
            Url(url).host.lowercase()
        } catch (_: Exception) {
            return false
        }
        return supportedDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    suspend fun batchResolve(
        urls: Collection<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> {
        for (url in urls) {
            if (canResolve(url)) {
                val resolved = resolvePlatformLinks(url, priority)
                if (resolved.isNotEmpty()) return resolved
            }
        }
        return emptyList()
    }

    suspend fun resolvePlatformLinks(
        platformUrl: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> {
        return try {
            val response = ApiClient.queueInstance.enqueue("https://api.song.link/v1-alpha.1/links", priority = priority) {
                parameter("url", platformUrl)
                parameter("userCountry", "US")
            }
            if (response.status.value in 200..299) {
                val body = response.body<OdesliResponse>()
                body.linksByPlatform.values.map { it.url }
            } else emptyList()
        } catch (e: Exception) {
            logger.error("Error resolving platform links via Odesli for $platformUrl", e)
            emptyList()
        }
    }

    @Serializable
    private data class OdesliResponse(
        val linksByPlatform: Map<String, OdesliPlatformLink>
    )

    @Serializable
    private data class OdesliPlatformLink(
        val url: String
    )
}
