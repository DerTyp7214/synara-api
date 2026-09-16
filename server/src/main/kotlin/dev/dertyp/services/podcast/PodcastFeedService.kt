package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastSource
import dev.dertyp.services.ImageService
import dev.dertyp.services.Service
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface FetchResult {
    data object NotModified : FetchResult

    data class Changed(
        val feed: ParsedFeed,
        val etag: String?,
        val lastModified: String?,
        val finalUrl: String
    ) : FetchResult
}

data class RefreshOutcome(
    val changed: Boolean,
    val inserted: Int,
    val updated: Int,
    val error: String?
)

class PodcastFeedService(
    private val podcastService: PodcastService,
    private val imageService: ImageService,
    private val http: PodcastHttp
) : Service() {
    private val refreshMutexes = ConcurrentHashMap<UUID, Mutex>()

    suspend fun fetchFeed(url: String, etag: String?, lastModified: String?): FetchResult {
        http.requirePublicHttpUrl(url)

        val response = http.feedClient.get(url) {
            if (!etag.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, etag)
            if (!lastModified.isNullOrBlank()) header(HttpHeaders.IfModifiedSince, lastModified)
        }

        if (response.status == HttpStatusCode.NotModified) return FetchResult.NotModified
        if (!response.status.isSuccess()) throw FeedFetchException("HTTP ${response.status.value}", response.status.value)

        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_FEED_BYTES) {
            throw FeedTooLargeException("Feed is larger than $MAX_FEED_BYTES bytes")
        }

        val bytes = readCapped(response.bodyAsChannel(), MAX_FEED_BYTES)
            ?: throw FeedTooLargeException("Feed is larger than $MAX_FEED_BYTES bytes")

        val feed = PodcastFeedParser.parse(ByteArrayInputStream(bytes))

        return FetchResult.Changed(
            feed = feed,
            etag = response.headers[HttpHeaders.ETag],
            lastModified = response.headers[HttpHeaders.LastModified],
            finalUrl = response.call.request.url.toString()
        )
    }

    suspend fun fetchArtwork(url: String, origin: String): UUID? {
        return try {
            http.requirePublicHttpUrl(url)
            val response = http.feedClient.get(url)
            if (!response.status.isSuccess()) return null

            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_ARTWORK_BYTES) return null

            val bytes = readCapped(response.bodyAsChannel(), MAX_ARTWORK_BYTES) ?: return null
            if (bytes.isEmpty()) return null

            imageService.createImage(bytes, origin)
        } catch (e: Exception) {
            logger.debug("Podcast artwork could not be fetched from $url: ${e.message}")
            null
        }
    }

    suspend fun refreshShow(showId: UUID, force: Boolean = false): RefreshOutcome {
        val mutex = refreshMutexes.computeIfAbsent(showId) { Mutex() }

        return mutex.withLock {
            val row = podcastService.showById(showId)
                ?: return@withLock RefreshOutcome(false, 0, 0, "unknown show")

            val feedUrl = row.feedUrl
            if (row.source != PodcastSource.FEED || feedUrl.isNullOrBlank()) {
                return@withLock RefreshOutcome(false, 0, 0, "not a feed show")
            }

            val lastFetchedAt = row.lastFetchedAt
            if (!force && lastFetchedAt != null && System.currentTimeMillis() - lastFetchedAt < MIN_REFRESH_INTERVAL_MS) {
                return@withLock RefreshOutcome(false, 0, 0, null)
            }

            try {
                when (val result = fetchFeed(feedUrl, row.etag, row.lastModified)) {
                    FetchResult.NotModified -> {
                        podcastService.recordFetchResult(showId, row.etag, row.lastModified, null)
                        RefreshOutcome(false, 0, 0, null)
                    }

                    is FetchResult.Changed -> {
                        val parsedShow = result.feed.show
                        val imageId = if (parsedShow.imageUrl != null && parsedShow.imageUrl != row.imageUrl) {
                            fetchArtwork(parsedShow.imageUrl, "podcast-show:$showId")
                        } else {
                            null
                        }

                        val newFeedUrl = result.feed.newFeedUrl
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && it != row.feedUrl }
                            ?.takeIf { runCatching { http.requirePublicHttpUrl(it) }.isSuccess }

                        podcastService.updateShowFromFeed(
                            showId = showId,
                            parsed = parsedShow,
                            imageId = imageId,
                            imageUrl = parsedShow.imageUrl,
                            newFeedUrl = newFeedUrl
                        )

                        val (inserted, updated) = podcastService.upsertEpisodes(showId, result.feed.episodes)
                        podcastService.recordFetchResult(showId, result.etag, result.lastModified, null)

                        RefreshOutcome(true, inserted, updated, null)
                    }
                }
            } catch (e: Exception) {
                val message = e.message ?: e::class.simpleName ?: "Feed refresh failed"
                logger.warn("Podcast feed refresh failed for $feedUrl: $message")
                runCatching { podcastService.recordFetchResult(showId, row.etag, row.lastModified, message) }
                RefreshOutcome(false, 0, 0, message)
            }
        }
    }

    suspend fun subscribe(userId: UUID, feedUrl: String): PodcastShow {
        val normalized = normalizeFeedUrl(feedUrl)
        val sourceKey = PodcastKeys.feedSourceKey(normalized)

        val existing = podcastService.findShowBySourceKey(sourceKey)
        if (existing != null) return podcastService.subscribeToShow(userId, existing.id)

        val result = try {
            fetchFeed(normalized, null, null)
        } catch (e: FeedFetchException) {
            throw IllegalArgumentException("Feed could not be fetched: ${e.message}", e)
        } catch (e: FeedParseException) {
            throw IllegalArgumentException("Feed could not be fetched: ${e.message}", e)
        } catch (e: FeedTooLargeException) {
            throw IllegalArgumentException("Feed could not be fetched: ${e.message}", e)
        }

        val changed = result as? FetchResult.Changed
            ?: throw IllegalArgumentException("Feed could not be fetched: no content")

        val parsedShow = changed.feed.show
        val imageId = parsedShow.imageUrl?.let { fetchArtwork(it, "podcast-feed:$sourceKey") }

        val showId = podcastService.createFeedShow(
            feedUrl = normalized,
            sourceKey = sourceKey,
            parsed = parsedShow,
            imageId = imageId,
            imageUrl = parsedShow.imageUrl
        )

        podcastService.upsertEpisodes(showId, changed.feed.episodes)
        podcastService.recordFetchResult(showId, changed.etag, changed.lastModified, null)

        return podcastService.subscribeToShow(userId, showId)
    }

    internal fun normalizeFeedUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "Feed URL must not be empty" }
        require(trimmed.length <= MAX_FEED_URL_LENGTH) { "Feed URL is too long" }

        val builder = try {
            URLBuilder(trimmed)
        } catch (e: Throwable) {
            throw IllegalArgumentException("Not a valid feed URL: $trimmed", e)
        }

        val scheme = builder.protocol.name.lowercase()
        require(scheme == "http" || scheme == "https") { "Only http and https feed URLs are supported" }

        builder.fragment = ""
        val normalized = builder.buildString()
        require(normalized.length <= MAX_FEED_URL_LENGTH) { "Feed URL is too long" }

        return normalized
    }

    private suspend fun readCapped(channel: ByteReadChannel, limit: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue

            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }

        return output.toByteArray()
    }

    companion object {
        const val MAX_FEED_BYTES = 20L * 1024 * 1024
        const val MAX_ARTWORK_BYTES = 10L * 1024 * 1024
        const val MIN_REFRESH_INTERVAL_MS = 60_000L
        private const val MAX_FEED_URL_LENGTH = 2048
        private const val BUFFER_SIZE = 64 * 1024
    }
}
