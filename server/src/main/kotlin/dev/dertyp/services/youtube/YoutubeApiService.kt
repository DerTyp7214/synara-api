package dev.dertyp.services.youtube

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

@Serializable
data class YoutubePlaylistResponse(
    val items: List<YoutubePlaylistItem>? = null,
    val nextPageToken: String? = null,
    val pageInfo: YoutubePageInfo? = null
)

@Serializable
data class YoutubePlaylistItem(
    val snippet: YoutubeSnippet? = null,
    val contentDetails: YoutubeContentDetails? = null
)

@Serializable
data class YoutubeVideoListResponse(
    val items: List<YoutubeVideo>? = null
)

@Serializable
data class YoutubeVideo(
    val snippet: YoutubeSnippet? = null,
    val contentDetails: YoutubeContentDetails? = null
)

@Serializable
data class YoutubeSnippet(
    val publishedAt: String? = null,
    val channelId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val thumbnails: Map<String, YoutubeThumbnail>? = null,
    val channelTitle: String? = null,
    val tags: List<String>? = null,
    val categoryId: String? = null,
    val resourceId: YoutubeResourceId? = null
)

@Serializable
data class YoutubeResourceId(
    val videoId: String? = null
)

@Serializable
data class YoutubeContentDetails(
    val videoId: String? = null,
    val duration: String? = null
)

@Serializable
data class YoutubeThumbnail(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

@Serializable
data class YoutubePageInfo(
    val totalResults: Int? = null
)

@Serializable
data class YoutubePlaylistListResponse(
    val items: List<YoutubePlaylist>? = null
)

@Serializable
data class YoutubePlaylist(
    val snippet: YoutubeSnippet? = null
)

class YoutubeApiService(
    environment: ApplicationEnvironment
) : Service() {
    private val apiKey = environment.config.propertyOrNull("youtube.apiKey")?.getString()
    private val baseUrl = "https://www.googleapis.com/youtube/v3"

    val enabled: Boolean get() = !apiKey.isNullOrBlank()

    private suspend inline fun <reified T> retryableQueuedGet(
        url: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): T? {
        var retries = 0
        val maxRetries = 3
        while (retries < maxRetries) {
            try {
                val response: HttpResponse = ApiClient.queueInstance.enqueue(url, priority)
                if (response.status == HttpStatusCode.TooManyRequests) {
                    logger.warn("Rate limited by YouTube, retrying in 10s... ($retries/$maxRetries)")
                    delay(10.seconds)
                    retries++
                    continue
                }
                return if (response.status.isSuccess()) response.body<T>() else null
            } catch (e: Exception) {
                if (retries < maxRetries - 1) {
                    logger.warn("Error during YouTube request ($url): ${e.message}, retrying... ($retries/$maxRetries)")
                    delay(5.seconds)
                } else {
                    logger.error("Error during YouTube request after $maxRetries retries ($url): ${e.message}", e)
                }
                retries++
            }
        }
        return null
    }

    suspend fun getYoutubeMusicCover(videoId: String): String? {
        return try {
            val url = "https://music.youtube.com/watch?v=$videoId"
            val response = ApiClient.queueInstance.enqueue(url, HttpClientPriority.LOW)
            if (response.status != HttpStatusCode.OK) return null
            val html = response.bodyAsText()
            val regex = Regex("""<meta property="og:image" content="([^"]+)">""")
            regex.find(html)?.groupValues?.get(1)?.replace("=w120-h90-p", "=w1200-h1200")
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getVideoMetadata(videoId: String): Map<String, String>? {
        if (!enabled) return null
        val url = "$baseUrl/videos?part=snippet,contentDetails&id=$videoId&key=$apiKey"
        return try {
            val response = retryableQueuedGet<YoutubeVideoListResponse>(url, HttpClientPriority.HIGH)
            val item = response?.items?.firstOrNull() ?: return null
            val map = mutableMapOf<String, String>()
            map["id"] = videoId
            map["title"] = item.snippet?.title ?: ""
            map["uploader"] = item.snippet?.channelTitle ?: ""
            map["description"] = item.snippet?.description ?: ""
            
            val thumbnails = item.snippet?.thumbnails
            val bestThumbnail = thumbnails?.values?.find { it.width != null && it.width == it.height && it.width > 0 }
                ?: thumbnails?.get("maxres")
                ?: thumbnails?.get("standard")
                ?: thumbnails?.get("high")
                ?: thumbnails?.get("medium")
                ?: thumbnails?.get("default")
            
            bestThumbnail?.url?.let { map["thumbnail"] = it }
            bestThumbnail?.width?.let { map["width"] = it.toString() }
            bestThumbnail?.height?.let { map["height"] = it.toString() }

            if (bestThumbnail == null || bestThumbnail.width != bestThumbnail.height) {
                getYoutubeMusicCover(videoId)?.let { 
                    map["thumbnail"] = it
                    map.remove("width")
                    map.remove("height")
                }
            }
            
            map
        } catch (e: Exception) {
            logger.error("Failed to fetch youtube video metadata", e)
            null
        }
    }

    suspend fun getPlaylistItems(playlistId: String): List<YoutubePlaylistItem> {
        if (!enabled) return emptyList()
        
        val items = mutableListOf<YoutubePlaylistItem>()
        var nextToken: String? = null

        do {
            val url = "$baseUrl/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId&key=$apiKey" +
                    (nextToken?.let { "&pageToken=$it" } ?: "")
            
            val response = retryableQueuedGet<YoutubePlaylistResponse>(url, HttpClientPriority.HIGH)
            
            response?.items?.let { items.addAll(it) }
            nextToken = response?.nextPageToken
        } while (nextToken != null)

        return items
    }

    suspend fun getPlaylistMetadata(playlistId: String): YoutubePlaylist? {
        if (!enabled) return null
        val url = "$baseUrl/playlists?part=snippet&id=$playlistId&key=$apiKey"
        return retryableQueuedGet<YoutubePlaylistListResponse>(url, HttpClientPriority.HIGH)?.items?.firstOrNull()
    }
}
