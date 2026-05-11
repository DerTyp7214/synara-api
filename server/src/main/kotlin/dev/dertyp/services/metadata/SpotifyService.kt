package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

class SpotifyService(
    environment: ApplicationEnvironment
) : MetadataService("Spotify", IMetadataService.MetadataType.spotify, environment) {
    override val tokenUrl = "https://accounts.spotify.com/api/token"
    override val clientIdConfigPath = "spotify.clientId"
    override val clientSecretConfigPath = "spotify.clientSecret"

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
        parameter("client_id", clientId)
        parameter("client_secret", clientSecret)
    }

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Artist> {
        val response = ApiClient.instance.get("https://api.spotify.com/v1/search") {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", query)
            parameter("type", "artist")
            parameter("limit", limit)
        }

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(30.seconds)
            return searchArtists(query, limit, priority)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.error("Searching artists for $query failed with status ${response.status}")
            return emptyList()
        }

        val searchResponse = response.body<SearchResponse>()

        return searchResponse.artists?.items?.map { artist ->
            IMetadataService.Artist(
                id = artist.id,
                name = artist.name,
                popularity = artist.popularity.toFloat(),
                url = artist.href,
                images = artist.images.map { image ->
                    IMetadataService.Image(
                        url = image.url,
                        width = image.width,
                        height = image.height,
                    )
                }
            )
        } ?: emptyList()
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean,
        priority: HttpClientPriority
    ): List<IMetadataService.Album> {
        val response = ApiClient.instance.get("https://api.spotify.com/v1/search") {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", query)
            parameter("type", "album")
            parameter("limit", limit)
        }

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(30.seconds)
            return searchAlbums(query, limit, includeTracks, priority)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.error("Searching albums for $query failed with status ${response.status}")
            return emptyList()
        }

        val searchResponse = response.body<SearchResponse>()

        return searchResponse.albums?.items?.map { album ->
            IMetadataService.Album(
                id = album.id,
                title = album.name,
                artists = album.artists.map { it.name },
                trackCount = album.totalTracks,
                images = album.images.map { image ->
                    IMetadataService.Image(
                        url = image.url,
                        width = image.width,
                        height = image.height,
                    )
                }
            )
        } ?: emptyList()
    }

    @Serializable
    data class SearchResponse(
        val artists: Artists? = null,
        val albums: Albums? = null
    )

    @Serializable
    data class Albums(
        val href: String,
        val limit: Int,
        val next: String?,
        val offset: Int,
        val previous: String?,
        val total: Int,
        val items: List<Album>
    )

    @Serializable
    data class Album(
        val id: String,
        val name: String,
        val artists: List<Artist>,
        val images: List<SpotifyImage>,
        @SerialName("total_tracks")
        val totalTracks: Int,
        val href: String
    )

    @Serializable
    data class Artists(
        val href: String,
        val limit: Int,
        val next: String?,
        val offset: Int,
        val previous: String?,
        val total: Int,
        val items: List<Artist>
    )

    @Serializable
    data class Artist(
        val id: String,
        val genres: List<String> = emptyList(),
        val href: String,
        val name: String,
        val popularity: Int = 0,
        val uri: String,
        val images: List<SpotifyImage> = emptyList(),
    )

    @Serializable
    data class SpotifyImage(
        val url: String,
        val width: Int,
        val height: Int,
    )
}
