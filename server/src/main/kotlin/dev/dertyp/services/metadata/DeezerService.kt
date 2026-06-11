package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

class DeezerService(
    environment: ApplicationEnvironment
) : MetadataService("Deezer", IMetadataService.MetadataType.deezer, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath = ""
    override val clientSecretConfigPath = ""

    private val baseUrl = "https://api.deezer.com"

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
    }

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Artist> {
        val response = ApiClient.instance.get("$baseUrl/search/artist") {
            parameter("q", query)
            parameter("limit", limit)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.error("Searching artists on Deezer for $query failed with status ${response.status}")
            return emptyList()
        }

        val searchResponse = response.body<SearchResponse>()

        return searchResponse.data.map { artist ->
            IMetadataService.Artist(
                id = artist.id.toString(),
                name = artist.name,
                popularity = 0f,
                url = artist.link,
                images = listOfNotNull(
                    IMetadataService.Image(artist.pictureSmall, 56, 56),
                    IMetadataService.Image(artist.pictureMedium, 250, 250),
                    IMetadataService.Image(artist.pictureBig, 500, 500),
                    IMetadataService.Image(artist.pictureXl, 1000, 1000)
                )
            )
        }
    }

    override suspend fun getTrackByIsrc(
        isrc: String,
        priority: HttpClientPriority
    ): IMetadataService.Track? {
        val response = ApiClient.instance.get("$baseUrl/track/isrc:$isrc")
        if (response.status != HttpStatusCode.OK) return null

        val track = response.body<Track>()
        return IMetadataService.Track(
            id = track.id.toString(),
            title = track.title,
            artists = listOf(track.artist.name),
            duration = track.duration.toLong().milliseconds,
            images = listOfNotNull(
                track.album?.coverSmall?.let { IMetadataService.Image(it, 56, 56) },
                track.album?.coverMedium?.let { IMetadataService.Image(it, 250, 250) },
                track.album?.coverBig?.let { IMetadataService.Image(it, 500, 500) },
                track.album?.coverXl?.let { IMetadataService.Image(it, 1000, 1000) }
            ),
            isrc = track.isrc,
            albumId = track.album?.id?.toString(),
            albumTitle = track.album?.title
        )
    }

    override suspend fun getAlbumByBarcode(
        barcode: String,
        priority: HttpClientPriority
    ): IMetadataService.Album? {
        val response = ApiClient.instance.get("$baseUrl/album/upc:$barcode")
        if (response.status != HttpStatusCode.OK) return null

        val album = response.body<Album>()
        return IMetadataService.Album(
            id = album.id.toString(),
            title = album.title,
            artists = listOf(album.artist.name),
            trackCount = album.nbTracks ?: 0,
            images = listOfNotNull(
                IMetadataService.Image(album.coverSmall, 56, 56),
                IMetadataService.Image(album.coverMedium, 250, 250),
                IMetadataService.Image(album.coverBig, 500, 500),
                IMetadataService.Image(album.coverXl, 1000, 1000)
            ),
            barcode = album.upc
        )
    }

    @Serializable
    data class SearchResponse(
        val data: List<Artist>
    )

    @Serializable
    data class Artist(
        val id: Long,
        val name: String,
        val link: String? = null,
        val picture: String? = null,
        @SerialName("picture_small") val pictureSmall: String = "",
        @SerialName("picture_medium") val pictureMedium: String = "",
        @SerialName("picture_big") val pictureBig: String = "",
        @SerialName("picture_xl") val pictureXl: String = "",
        @SerialName("nb_album") val nbAlbum: Int? = null,
        @SerialName("nb_fan") val nbFan: Int? = null,
        val radio: Boolean? = null,
        val tracklist: String? = null,
        val type: String? = null
    )

    @Serializable
    data class Track(
        val id: Long,
        val title: String,
        val duration: Int,
        val isrc: String? = null,
        val artist: Artist,
        val album: Album? = null
    )

    @Serializable
    data class Album(
        val id: Long,
        val title: String,
        val upc: String? = null,
        val cover: String? = null,
        @SerialName("cover_small") val coverSmall: String = "",
        @SerialName("cover_medium") val coverMedium: String = "",
        @SerialName("cover_big") val coverBig: String = "",
        @SerialName("cover_xl") val coverXl: String = "",
        @SerialName("nb_tracks") val nbTracks: Int? = null,
        val artist: Artist
    )
}
