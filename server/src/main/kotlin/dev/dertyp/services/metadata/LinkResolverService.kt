package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.tryGetString
import kotlinx.serialization.Serializable

class LinkResolverService(environment: ApplicationEnvironment) : Service() {
    private val baseUrl = "https://linkresolver.synara.audio"
    private val apiKey = environment.config.tryGetString("linkresolver.apiKey") ?: ""

    val enabled: Boolean get() = apiKey.isNotBlank()

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

        for (url in urls) {
            val resolved = resolve(url = url, priority = priority)
            if (resolved.isNotEmpty()) return resolved
        }

        return emptyList()
    }

    suspend fun resolvePlatformLinks(
        url: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> = resolve(url = url, priority = priority)

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

    @Serializable
    private data class LinkResolverResponse(val links: Map<String, String>)
}
