package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.safeQueuedGet
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

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

    override suspend fun search(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Track> {
        val searchResponse = ApiClient.instance.safeQueuedGet<SearchResponse>("https://api.spotify.com/v1/search", priority) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", query)
            parameter("type", "track")
            parameter("limit", limit)
        }

        return searchResponse?.tracks?.items?.map { track ->
            IMetadataService.Track(
                id = track.id,
                title = track.name,
                artists = track.artists.map { it.name },
                duration = track.durationMs.milliseconds,
                images = track.album.images.map { image ->
                    IMetadataService.Image(
                        url = image.url,
                        width = image.width,
                        height = image.height,
                    )
                },
                isrc = track.externalIds?.isrc
            )
        } ?: emptyList()
    }

    override suspend fun getTrackByIsrc(
        isrc: String,
        priority: HttpClientPriority
    ): IMetadataService.Track? {
        val searchResponse = ApiClient.instance.safeQueuedGet<SearchResponse>("https://api.spotify.com/v1/search", priority) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", "isrc:$isrc")
            parameter("type", "track")
            parameter("limit", 1)
        }

        val track = searchResponse?.tracks?.items?.firstOrNull() ?: return null

        return IMetadataService.Track(
            id = track.id,
            title = track.name,
            artists = track.artists.map { it.name },
            duration = track.durationMs.milliseconds,
            images = track.album.images.map { image ->
                IMetadataService.Image(
                    url = image.url,
                    width = image.width,
                    height = image.height,
                )
            },
            isrc = track.externalIds?.isrc
        )
    }

    override suspend fun getAlbumByBarcode(
        barcode: String,
        priority: HttpClientPriority
    ): IMetadataService.Album? {
        val searchResponse = ApiClient.instance.safeQueuedGet<SearchResponse>("https://api.spotify.com/v1/search", priority) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", "upc:$barcode")
            parameter("type", "album")
            parameter("limit", 1)
        }

        val album = searchResponse?.albums?.items?.firstOrNull() ?: return null

        return IMetadataService.Album(
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
            },
            barcode = album.externalIds?.upc ?: album.externalIds?.ean
        )
    }

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Artist> {
        val searchResponse = ApiClient.instance.safeQueuedGet<SearchResponse>("https://api.spotify.com/v1/search", priority) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", query)
            parameter("type", "artist")
            parameter("limit", limit)
        }

        return searchResponse?.artists?.items?.map { artist ->
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
        val searchResponse = ApiClient.instance.safeQueuedGet<SearchResponse>("https://api.spotify.com/v1/search", priority) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", query)
            parameter("type", "album")
            parameter("limit", limit)
        }

        return searchResponse?.albums?.items?.map { album ->
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
                },
                barcode = album.externalIds?.upc ?: album.externalIds?.ean
            )
        } ?: emptyList()
    }

    @Serializable
    data class SearchResponse(
        val artists: Artists? = null,
        val albums: Albums? = null,
        val tracks: Tracks? = null
    )

    @Serializable
    data class Tracks(
        val href: String,
        val limit: Int,
        val next: String?,
        val offset: Int,
        val previous: String?,
        val total: Int,
        val items: List<Track>
    )

    @Serializable
    data class Track(
        val id: String,
        val name: String,
        val artists: List<Artist>,
        val album: Album,
        @SerialName("duration_ms")
        val durationMs: Int,
        val href: String,
        @SerialName("external_ids")
        val externalIds: ExternalIds? = null
    )

    @Serializable
    data class ExternalIds(
        val isrc: String? = null,
        val ean: String? = null,
        val upc: String? = null
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
        val href: String,
        @SerialName("external_ids")
        val externalIds: ExternalIds? = null
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
