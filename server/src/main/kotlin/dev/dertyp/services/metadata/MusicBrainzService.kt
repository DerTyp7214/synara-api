package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.cleanTitle
import dev.dertyp.data.Album
import dev.dertyp.data.BaseSong
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MusicBrainzSearchResponse(
    val recordings: List<MusicBrainzRecording>? = null
)

@Serializable
data class MusicBrainzReleaseSearchResponse(
    val releases: List<MusicBrainzRelease>? = null
)

@Serializable
data class MusicBrainzRecording(
    val id: String,
    val title: String? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MusicBrainzArtistCredit>? = null,
    val releases: List<MusicBrainzRelease>? = null,
    val length: Long? = null
)

@Serializable
data class MusicBrainzArtistCredit(
    val name: String? = null,
    val joinphrase: String? = null,
    val artist: MusicBrainzArtist? = null
)

@Serializable
data class MusicBrainzArtist(
    val id: String,
    val name: String? = null
)

@Serializable
data class MusicBrainzRelease(
    val id: String,
    val title: String? = null
)

class MusicBrainzService : Service() {
    private val mbBaseUrl = "https://musicbrainz.org/ws/2"

    private suspend inline fun <reified T> retryableGet(
        urlString: String,
        noinline block: suspend HttpRequestBuilder.() -> Unit = {}
    ): T? {
        var retries = 0
        while (retries < 10) {
            try {
                val response: HttpResponse = ApiClient.queueInstance.enqueue(urlString, block)
                if (response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.TooManyRequests) {
                    logger.warn("Rate limited by MusicBrainz, retrying in 1s... ($retries/10)")
                    delay(1000)
                    retries++
                    continue
                }
                return response.body<T>()
            } catch (e: Exception) {
                logger.error("Error during MusicBrainz request: ${e.message}", e)
                retries++
                delay(1000)
            }
        }
        return null
    }

    suspend fun searchMb(song: BaseSong): MusicBrainzRecording? {
        val queryParts = mutableListOf<String>()
        queryParts.add("recording:\"${song.title.cleanTitle()}\"")
        song.artists.forEach { queryParts.add("artist:\"${it.name}\"") }

        song.album?.name?.takeIf { it != song.title }?.let {
            queryParts.add("release:\"${it.cleanTitle()}\"")
        }

        song.album?.artists?.forEach { artist ->
            queryParts.add("artistname:\"${artist.name}\"")
        }

        val query = queryParts.joinToString(" AND ")

        return try {
            val searchResponse = retryableGet<MusicBrainzSearchResponse>("$mbBaseUrl/recording") {
                parameter("query", query)
                parameter("limit", 1)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            searchResponse?.recordings?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Failed to search MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchAlbumMb(album: Album): MusicBrainzRelease? {
        val queryParts = mutableListOf<String>()
        queryParts.add("release:\"${album.name.cleanTitle()}\"")
        album.artists.forEach { queryParts.add("artist:\"${it.name}\"") }

        val query = queryParts.joinToString(" AND ")

        return try {
            val response = retryableGet<MusicBrainzReleaseSearchResponse>("$mbBaseUrl/release") {
                parameter("query", query)
                parameter("limit", 1)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.releases?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Error searching MusicBrainz for $query", e)
            null
        }
    }
}
