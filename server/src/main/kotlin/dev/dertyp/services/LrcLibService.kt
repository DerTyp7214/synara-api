package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.safeQueuedGet
import dev.dertyp.serializers.AppJson
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.lyrics.providers.HttpFetch
import dev.dertyp.services.lyrics.providers.LyricsHttp
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

class LrcLibService : ILrcLibService, Service() {
    private val http by inject<LyricsHttp>()

    override suspend fun getLyrics(artist: String, title: String, album: String?, duration: Long?): LrcLibResponse? {
        return try {
            ApiClient.queueInstance.safeQueuedGet<LrcLibResponse>("$BASE_URL/get") {
                header("User-Agent", USER_AGENT)
                parameter("artist_name", artist)
                parameter("track_name", title)
                if (album != null) parameter("album_name", album)
                if (duration != null) parameter("duration", duration / 1000.0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fetch lyrics from LrcLib: ${e.message}")
            null
        }
    }

    suspend fun search(
        artist: String,
        title: String,
        album: String? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
    ): List<LrcLibResponse>? {
        val response = http.get("$BASE_URL/search", SEARCH_TIMEOUT, priority) {
            parameter("artist_name", artist)
            parameter("track_name", title)
            if (album != null) parameter("album_name", album)
        }
        return when (response) {
            is HttpFetch.Body -> AppJson.decodeFromString<List<LrcLibResponse>>(response.text())
            HttpFetch.Missing -> null
            is HttpFetch.Failed -> throw response.cause
        }
    }

    companion object {
        const val BASE_URL = "https://lrclib.net/api"
        private val SEARCH_TIMEOUT = 4.seconds
        val USER_AGENT = "Synara/${BuildConfig.VERSION} (https://github.com/dertyp7214/synara_api)"
    }
}
