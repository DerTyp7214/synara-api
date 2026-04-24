package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.User
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class TheAudioDBService(
    private val environment: ApplicationEnvironment
) : MetadataService("TheAudioDB", IMetadataService.MetadataType.theAudioDB, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath: String = "theaudiodb.apiKey"
    override val clientSecretConfigPath: String = ""

    private val apiKey by lazy { environment.config.propertyOrNull(clientIdConfigPath)?.getString() ?: "123" }

    private val baseUrl = "https://www.theaudiodb.com/api/v1/json"

    @Serializable
    data class ArtistResponse(val artists: List<Artist>? = null)

    @Serializable
    data class Artist(
        val idArtist: String,
        val strArtist: String,
        @SerialName("strBiography") val biography: String? = null,
        @SerialName("strStyle") val style: String? = null,
        @SerialName("strGenre") val genre: String? = null,
        @SerialName("strArtistThumb") val artistThumb: String? = null,
        @SerialName("strArtistLogo") val artistLogo: String? = null,
        @SerialName("strArtistCutout") val artistCutout: String? = null,
        @SerialName("strArtistClearart") val artistClearart: String? = null,
        @SerialName("strArtistWideThumb") val artistWideThumb: String? = null,
        @SerialName("strArtistFanart") val artistFanart: String? = null,
        @SerialName("strArtistFanart2") val artistFanart2: String? = null,
        @SerialName("strArtistFanart3") val artistFanart3: String? = null,
        @SerialName("strArtistFanart4") val artistFanart4: String? = null,
        @SerialName("strArtistBanner") val artistBanner: String? = null
    )

    @Serializable
    data class AlbumResponse(val album: List<Album>? = null)

    @Serializable
    data class Album(
        val idAlbum: String,
        val idArtist: String,
        val idArtistMBID: String? = null,
        val strAlbum: String,
        @SerialName("strGenre") val genre: String? = null,
        @SerialName("strStyle") val style: String? = null,
        @SerialName("strAlbumThumb") val albumThumb: String? = null,
        @SerialName("strAlbumThumbHQ") val albumThumbHQ: String? = null,
        @SerialName("strAlbumThumbBack") val albumThumbBack: String? = null,
        @SerialName("strAlbumCDart") val albumCDart: String? = null,
        @SerialName("strAlbumSpine") val albumSpine: String? = null,
        @SerialName("strAlbumFront") val albumFront: String? = null,
        @SerialName("strAlbumBack") val albumBack: String? = null
    )

    @Serializable
    data class TrackResponse(val track: List<Track>? = null)

    @Serializable
    data class Track(
        val idTrack: String,
        val idAlbum: String,
        val idArtist: String,
        val strTrack: String,
        @SerialName("strGenre") val genre: String? = null,
        @SerialName("strStyle") val style: String? = null,
    )

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
    }

    private suspend inline fun <reified T> retryableGet(
        path: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
        noinline block: suspend HttpRequestBuilder.() -> Unit = {}
    ): T? {
        val url = "$baseUrl/$apiKey/$path"
        var retries = 0
        while (retries < 5) {
            try {
                val response: HttpResponse = ApiClient.queueInstance.enqueue(url, priority, block)
                if (response.status == HttpStatusCode.TooManyRequests) {
                    logger.warn("Rate limited by TheAudioDB, retrying in 1s... ($retries/5)")
                    delay(1.seconds)
                    retries++
                    continue
                }
                if (!response.status.isSuccess()) return null
                return response.body<T>()
            } catch (e: Exception) {
                logger.error("Error during TheAudioDB request to $url: ${e.message}", e)
                delay(1.seconds)
                retries++
            }
        }
        return null
    }

    private fun String?.splitMetadata(): List<String> {
        if (this.isNullOrBlank()) return emptyList()
        return this.split(Regex("\\s*[/,]\\s*")).map { it.trim() }.filter { it.isNotBlank() }
    }

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Artist> {
        val response = retryableGet<ArtistResponse>("search.php", priority) {
            parameter("s", query)
        }
        return response?.artists?.take(limit)?.map { artist ->
            IMetadataService.Artist(
                id = artist.idArtist,
                name = artist.strArtist,
                popularity = 0f,
                biography = artist.biography,
                styles = artist.style.splitMetadata(),
                genres = artist.genre.splitMetadata(),
                images = listOfNotNull(
                    artist.artistThumb?.let { IMetadataService.Image(it, 1000, 1000) },
                    artist.artistLogo?.let { IMetadataService.Image(it, 800, 300) },
                    artist.artistCutout?.let { IMetadataService.Image(it, 800, 800) },
                    artist.artistClearart?.let { IMetadataService.Image(it, 800, 800) },
                )
            )
        } ?: emptyList()
    }

    override suspend fun getImageUrlByArtistMbId(mbId: UUID, priority: HttpClientPriority): List<IMetadataService.Image> {
        return getArtistByMbId(mbId, priority)?.images ?: emptyList()
    }

    override suspend fun getArtistByMbId(mbId: UUID, priority: HttpClientPriority): IMetadataService.Artist? {
        val response = retryableGet<ArtistResponse>("artist-mb.php", priority) {
            parameter("i", mbId.toString())
        }
        val artist = response?.artists?.firstOrNull() ?: return null
        return IMetadataService.Artist(
            id = artist.idArtist,
            name = artist.strArtist,
            popularity = 0f,
            biography = artist.biography,
            styles = artist.style.splitMetadata(),
            genres = artist.genre.splitMetadata(),
            images = listOfNotNull(
                artist.artistThumb?.let { IMetadataService.Image(it, 1000, 1000) },
                artist.artistLogo?.let { IMetadataService.Image(it, 800, 300) },
                artist.artistCutout?.let { IMetadataService.Image(it, 800, 800) },
                artist.artistClearart?.let { IMetadataService.Image(it, 800, 800) },
            )
        )
    }

    override suspend fun getImageUrlByAlbumMbId(mbId: UUID, priority: HttpClientPriority): List<IMetadataService.Image> {
        return getAlbumByMbId(mbId, priority)?.images ?: emptyList()
    }

    override suspend fun getAlbumByMbId(mbId: UUID, priority: HttpClientPriority): IMetadataService.Album? {
        val response = retryableGet<AlbumResponse>("album-mb.php", priority) {
            parameter("i", mbId.toString())
        }
        val album = response?.album?.firstOrNull() ?: return null
        return IMetadataService.Album(
            id = album.idAlbum,
            title = album.strAlbum,
            genres = album.genre.splitMetadata() + album.style.splitMetadata(),
            images = listOfNotNull(
                album.albumThumb?.let { IMetadataService.Image(it, 1000, 1000) },
                album.albumThumbHQ?.let { IMetadataService.Image(it, 1400, 1400) },
                album.albumFront?.let { IMetadataService.Image(it, 1400, 1400) },
            )
        )
    }

    override suspend fun search(query: String, limit: Int, priority: HttpClientPriority): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean,
        priority: HttpClientPriority
    ): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getAlbumIdByTrackId(trackId: String, priority: HttpClientPriority): String? {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getImageUrlByAlbumId(albumId: String, priority: HttpClientPriority): List<IMetadataService.Image> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>, priority: HttpClientPriority): Map<String, List<IMetadataService.Image>> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getImageUrlByImageId(imageId: UUID, priority: HttpClientPriority): String? {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getTrackById(trackId: String, priority: HttpClientPriority): IMetadataService.Track? {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getImageUrlByTrackMbId(mbId: UUID, priority: HttpClientPriority): List<IMetadataService.Image> {
        return getTrackByMbId(mbId, priority)?.images ?: emptyList()
    }

    override suspend fun getTrackByMbId(mbId: UUID, priority: HttpClientPriority): IMetadataService.Track? {
        val response = retryableGet<TrackResponse>("track-mb.php", priority) {
            parameter("i", mbId.toString())
        }
        val track = response?.track?.firstOrNull() ?: return null
        return IMetadataService.Track(
            id = track.idTrack,
            title = track.strTrack,
            genres = track.genre.splitMetadata() + track.style.splitMetadata(),
            duration = kotlin.time.Duration.ZERO,
            images = emptyList(),
            albumId = track.idAlbum
        )
    }

    override suspend fun getTracksByIds(trackIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun albumExistsById(albumId: String, priority: HttpClientPriority): Boolean {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override suspend fun getArtistsByIds(artistIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override fun getAlbumTracks(albumId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override fun getArtistTracks(artistId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?,
        priority: HttpClientPriority
    ): Flow<IMetadataService.FlowPlaylist> {
        throw NotImplementedError("Not implemented for TheAudioDB")
    }
}
