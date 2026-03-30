package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.User
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.util.UUID

class AppleMusicService(
    environment: ApplicationEnvironment
) : MetadataService("Apple Music", Companion.MetadataType.appleMusic, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath = ""
    override val clientSecretConfigPath = ""

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {}

    override suspend fun getAccessToken(): IMetadataService.AccessTokenResponse {
        return IMetadataService.AccessTokenResponse("", "", 0)
    }

    override suspend fun searchArtists(query: String, limit: Int): List<IMetadataService.Artist> {
        val response = ApiClient.instance.get("https://itunes.apple.com/search") {
            parameter("term", query)
            parameter("entity", "musicArtist")
            parameter("limit", limit)
        }

        if (response.status != HttpStatusCode.OK) return emptyList()

        val body = response.bodyAsText().trim()
        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesArtist>>(body)
        return searchResponse.results.map { artist ->
            IMetadataService.Artist(
                id = artist.artistId.toString(),
                name = artist.artistName,
                popularity = 0f,
                url = artist.artistLinkUrl,
                images = emptyList()
            )
        }
    }

    override suspend fun search(query: String, limit: Int): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for Apple Music!")
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean
    ): List<IMetadataService.Album> {
        val response = ApiClient.instance.get("https://itunes.apple.com/search") {
            parameter("term", query)
            parameter("entity", if (includeTracks) "album,song" else "album")
            parameter("limit", limit)
        }

        if (response.status != HttpStatusCode.OK) return emptyList()

        val body = response.bodyAsText().trim()
        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body)
        return searchResponse.results
            .groupBy { it.collectionId }
            .map { (collectionId, results) ->
                val album = results.first { it.wrapperType == "collection" || it.wrapperType == "track" }
                val additionalTitles = results.mapNotNull { it.trackName }

                IMetadataService.Album(
                    id = collectionId.toString(),
                    title = album.collectionName,
                    artists = listOf(album.artistName),
                    trackCount = album.trackCount,
                    images = listOf(
                        IMetadataService.Image(
                            url = album.artworkUrl100.replace("100x100bb", "600x600bb"),
                            width = 600,
                            height = 600
                        )
                    ),
                    additionalTitles = additionalTitles
                )
            }
    }

    override suspend fun getAlbumIdByTrackId(trackId: String): String? = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getImageUrlByAlbumId(albumId: String): List<IMetadataService.Image> = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<IMetadataService.Image>> = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getImageUrlByImageId(imageId: UUID): String? = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getTrackById(trackId: String): IMetadataService.Track? = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getTracksByIds(trackIds: List<String>): List<IMetadataService.Track> = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun albumExistsById(albumId: String): Boolean = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getAlbumsByIds(albumIds: List<String>): List<IMetadataService.Album> = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getArtistsByIds(artistIds: List<String>): List<IMetadataService.Artist> = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getAlbumTracks(albumId: String): Flow<IMetadataService.Track> = throw NotImplementedError("Not implemented for Apple Music!")
    override suspend fun getArtistTracks(artistId: String): Flow<IMetadataService.Track> = throw NotImplementedError("Not implemented for Apple Music!")

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?
    ): Flow<IMetadataService.FlowPlaylist> = throw NotImplementedError("Not implemented for Apple Music!")

    @Serializable
    data class ITunesSearchResponse<T>(
        val resultCount: Int,
        val results: List<T>
    )

    @Serializable
    data class ITunesArtist(
        val wrapperType: String? = null,
        val artistId: Long,
        val artistName: String,
        val artistLinkUrl: String? = null
    )

    @Serializable
    data class ITunesAlbum(
        val wrapperType: String,
        val collectionId: Long,
        val artistName: String,
        val collectionName: String,
        val artworkUrl100: String,
        val trackCount: Int,
        val trackName: String? = null
    )
}
