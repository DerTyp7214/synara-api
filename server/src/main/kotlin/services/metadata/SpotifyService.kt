package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.data.User
import dev.dertyp.services.metadata.MetadataService.Artist
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.time.Duration.Companion.seconds

class SpotifyService(
    environment: ApplicationEnvironment
) : MetadataService("Spotify", Companion.MetadataType.spotify, environment) {
    override val tokenUrl = "https://accounts.spotify.com/api/token"
    override val clientIdConfigPath = "spotify.clientId"
    override val clientSecretConfigPath = "spotify.clientSecret"

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
        parameter("client_id", clientId)
        parameter("client_secret", clientSecret)
    }

    override suspend fun searchArtists(query: String, limit: Int): List<MetadataService.Artist> {
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
            return searchArtists(query, limit)
        }

        val searchResponse = response.body<SearchResponse>()

        return searchResponse.artists.items.map { artist ->
            Artist(
                id = artist.id,
                name = artist.name,
                popularity = artist.popularity.toFloat(),
                url = artist.href,
                images = artist.images.map { image ->
                    Image(
                        url = image.url,
                        width = image.width,
                        height = image.height,
                    )
                }
            )
        }
    }

    override suspend fun getAlbumIdByTrackId(trackId: String): String? {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getImageUrlByAlbumId(albumId: String): List<Image> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<Image>> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getImageUrlByImageId(imageId: UUID): String? {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getTrackById(trackId: String): Track? {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getTracksByIds(trackIds: List<String>): List<Track> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>): List<Album> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getArtistsByIds(artistIds: List<String>): List<MetadataService.Artist> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?
    ): Flow<FlowPlaylist> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    @Serializable
    data class SearchResponse(
        val artists: Artists,
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
        val genres: List<String>,
        val href: String,
        val name: String,
        val popularity: Int,
        val uri: String,
        val images: List<SpotifyImage>,
    )

    @Serializable
    data class SpotifyImage(
        val url: String,
        val width: Int,
        val height: Int,
    )
}