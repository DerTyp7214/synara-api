package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.data.User
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
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID
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

    override suspend fun searchArtists(query: String, limit: Int): List<IMetadataService.Artist> {
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

    override suspend fun search(query: String, limit: Int): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean
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
            return searchAlbums(query, limit, includeTracks)
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
                trackCount = album.total_tracks,
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

    override suspend fun getAlbumIdByTrackId(trackId: String): String? {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getImageUrlByAlbumId(albumId: String): List<IMetadataService.Image> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<IMetadataService.Image>> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getImageUrlByImageId(imageId: UUID): String? {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getTrackById(trackId: String): IMetadataService.Track? {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getTracksByIds(trackIds: List<String>): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun albumExistsById(albumId: String): Boolean {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getArtistsByIds(artistIds: List<String>): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getAlbumTracks(albumId: String): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override suspend fun getArtistTracks(artistId: String): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for spotify!")
    }

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?
    ): Flow<IMetadataService.FlowPlaylist> {
        throw NotImplementedError("Not implemented for spotify!")
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
        val total_tracks: Int,
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