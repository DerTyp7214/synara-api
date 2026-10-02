package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.core.RetryOnError
import dev.dertyp.core.RetryPolicy
import dev.dertyp.core.retryingGet
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class TheAudioDBService(
    environment: ApplicationEnvironment
) : MetadataService("TheAudioDB", IMetadataService.MetadataType.theAudioDB, environment) {
    override val credentialName: String = CredentialNames.THEAUDIODB_API

    private suspend fun apiKey(): String? =
        (credentialProvider.resolve(credentialName) as? ResolvedCredential.ApiKey)?.key

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
        @SerialName("idArtistMBID") val idArtistMbId: String? = null,
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

    private suspend inline fun <reified T> retryableGet(
        path: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
        noinline block: suspend HttpRequestBuilder.() -> Unit = {}
    ): T? {
        val key = apiKey()
        if (key == null) {
            logger.warn("TheAudioDB credential $credentialName is unavailable, skipping $path")
            return null
        }
        val url = "$baseUrl/$key/$path"
        return retryingGet(
            policy = RETRY_POLICY,
            label = "TheAudioDB request to $url",
            logger = logger,
            request = { ApiClient.queueInstance.enqueue(url, priority, block) },
        ) { it.body<T>() }
    }

    companion object {
        val RETRY_POLICY = RetryPolicy(
            maxAttempts = 5,
            retryOn = { it == HttpStatusCode.TooManyRequests },
            backoff = { _, _ -> 1.seconds },
            onError = RetryOnError.RETRY,
        )
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
}
