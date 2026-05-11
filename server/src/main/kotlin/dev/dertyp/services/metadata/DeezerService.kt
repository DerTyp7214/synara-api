package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.User
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

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
                    IMetadataService.Image(artist.picture_small, 56, 56),
                    IMetadataService.Image(artist.picture_medium, 250, 250),
                    IMetadataService.Image(artist.picture_big, 500, 500),
                    IMetadataService.Image(artist.picture_xl, 1000, 1000)
                )
            )
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean,
        priority: HttpClientPriority
    ): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getAlbumIdByTrackId(trackId: String, priority: HttpClientPriority): String? {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getImageUrlByAlbumId(albumId: String, priority: HttpClientPriority): List<IMetadataService.Image> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>, priority: HttpClientPriority): Map<String, List<IMetadataService.Image>> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getImageUrlByImageId(imageId: UUID, priority: HttpClientPriority): String? {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getTrackById(trackId: String, priority: HttpClientPriority): IMetadataService.Track? {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getTracksByIds(trackIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun albumExistsById(albumId: String, priority: HttpClientPriority): Boolean {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override suspend fun getArtistsByIds(artistIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override fun getAlbumTracks(albumId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override fun getArtistTracks(artistId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?,
        priority: HttpClientPriority
    ): Flow<IMetadataService.FlowPlaylist> {
        throw NotImplementedError("Not implemented for Deezer")
    }

    @Serializable
    data class SearchResponse(
        val data: List<Artist>
    )

    @Serializable
    data class Artist(
        val id: Long,
        val name: String,
        val link: String,
        val picture: String,
        val picture_small: String,
        val picture_medium: String,
        val picture_big: String,
        val picture_xl: String,
        val nb_album: Int? = null,
        val nb_fan: Int? = null,
        val radio: Boolean? = null,
        val tracklist: String? = null,
        val type: String
    )
}
