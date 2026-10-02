package dev.dertyp.services.podcast.index

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.gzipEncoding
import dev.dertyp.core.jsonContent
import dev.dertyp.core.timeouts
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.plugins.IPodcastIndex
import dev.dertyp.plugins.PodcastIndexEntry
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.podcast.PodcastHttp
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

fun HttpClientConfig<*>.podcastIndexConfig() {
    jsonContent(
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    )
    timeouts(request = 15.seconds, connect = 10.seconds, socket = 15.seconds)
    gzipEncoding()
    defaultRequest {
        header(HttpHeaders.UserAgent, PodcastHttp.USER_AGENT)
    }
}

fun HttpClientFactory.podcastIndexClient(): HttpClient =
    shared(HttpClientFactory.PODCAST_INDEX, OkHttp) { podcastIndexConfig() }

class PodcastIndexException(message: String, val status: Int? = null) : RuntimeException(message)

class PodcastIndexOrgIndex : IPodcastIndex, KoinComponent {
    private val httpClientFactory by inject<HttpClientFactory>()
    private val credentialProvider by inject<CredentialProvider>()

    override val id: String = ID
    override val name: String = "Podcast Index"

    override suspend fun isConfigured(): Boolean = credentialProvider.isAvailable(CredentialNames.PODCAST_INDEX_API)

    override suspend fun search(query: String, limit: Int): List<PodcastIndexEntry> {
        val creds = credentialProvider.resolve(CredentialNames.PODCAST_INDEX_API) as? ResolvedCredential.ApiKeyPair
            ?: return emptyList()
        val date = Clock.System.now().epochSeconds
        val response = httpClientFactory.podcastIndexClient().get("$BASE_URL/search/byterm") {
            parameter("q", query)
            parameter("max", limit)
            parameter("fulltext", "")
            header(HEADER_AUTH_KEY, creds.key)
            header(HEADER_AUTH_DATE, date.toString())
            header(HttpHeaders.Authorization, sha1Hex(creds.key + creds.secret + date))
        }
        if (!response.status.isSuccess()) {
            throw PodcastIndexException("Podcast Index answered ${response.status.value}", response.status.value)
        }
        val payload = response.body<PodcastIndexSearchResponse>()
        if (payload.status != "true") {
            throw PodcastIndexException(payload.description.ifBlank { "Podcast Index reported an error" })
        }
        return payload.feeds.mapNotNull(::toEntry)
    }

    private fun toEntry(feed: PodcastIndexFeed): PodcastIndexEntry? {
        val feedUrl = feed.url.trim()
        val title = feed.title.trim()
        if (feedUrl.isBlank() || title.isBlank()) return null
        return PodcastIndexEntry(
            feedUrl = feedUrl,
            title = title,
            description = feed.description.orEmpty(),
            author = feed.author.blankToNull() ?: feed.ownerName.blankToNull(),
            imageUrl = feed.artwork.blankToNull() ?: feed.image.blankToNull(),
            link = feed.link.blankToNull(),
            language = feed.language.blankToNull(),
            episodeCount = feed.episodeCount,
            lastPublishedAt = feed.newestItemPubdate?.takeIf { it > 0 }?.times(1000),
            explicit = feed.explicit,
            categories = feed.categoryNames,
        )
    }

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun sha1Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            builder.append(HEX_DIGITS[unsigned shr 4])
            builder.append(HEX_DIGITS[unsigned and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        const val ID = "podcastindex"
        const val BASE_URL = "https://api.podcastindex.org/api/1.0"

        private const val HEADER_AUTH_KEY = "X-Auth-Key"
        private const val HEADER_AUTH_DATE = "X-Auth-Date"
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
