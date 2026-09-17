package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.IPodcastIndex
import dev.dertyp.plugins.PodcastIndexEntry
import dev.dertyp.services.podcast.PodcastHttp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

fun podcastIndexHttpClient(engine: HttpClientEngine = OkHttp.create()): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 15.seconds.inWholeMilliseconds
        connectTimeoutMillis = 10.seconds.inWholeMilliseconds
        socketTimeoutMillis = 15.seconds.inWholeMilliseconds
    }
    install(ContentEncoding) {
        gzip()
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, PodcastHttp.USER_AGENT)
    }
}

class PodcastIndexException(message: String, val status: Int? = null) : RuntimeException(message)

class PodcastIndexOrgIndex(
    private val credentials: PodcastIndexCredentialSource,
    private val client: HttpClient = podcastIndexHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
    private val baseUrl: String = BASE_URL,
) : IPodcastIndex {
    override val id: String = ID
    override val name: String = "Podcast Index"

    override suspend fun isConfigured(): Boolean = credentials.current() != null

    override suspend fun search(query: String, limit: Int): List<PodcastIndexEntry> {
        val creds = credentials.current() ?: return emptyList()
        val date = clock.instant().epochSecond
        val response = client.get("$baseUrl/search/byterm") {
            parameter("q", query)
            parameter("max", limit)
            parameter("fulltext", "")
            header(HEADER_AUTH_KEY, creds.apiKey)
            header(HEADER_AUTH_DATE, date.toString())
            header(HttpHeaders.Authorization, sha1Hex(creds.apiKey + creds.apiSecret + date))
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
