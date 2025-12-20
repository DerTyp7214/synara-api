package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.serializers.DurationSerializer
import dev.dertyp.serializers.LocalDateSerializer
import dev.dertyp.serializers.OffsetDateTimeSerializer
import dev.dertyp.services.Service
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
abstract class MetadataService(
    private val providerName: String,
    metadataType: MetadataType,
    environment: ApplicationEnvironment
) : Service() {
    protected abstract val clientIdConfigPath: String
    protected abstract val clientSecretConfigPath: String
    protected abstract val tokenUrl: String

    private val clientId by lazy { environment.config.propertyOrNull(clientIdConfigPath)?.getString() }
    private val clientSecret by lazy { environment.config.propertyOrNull(clientSecretConfigPath)?.getString() }

    protected abstract fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String)
    abstract suspend fun searchArtists(query: String, limit: Int = 50): List<Artist>
    abstract suspend fun getAlbumIdByTrackId(trackId: String): String?
    abstract suspend fun getImageUrlByAlbumId(albumId: String): List<Image>
    abstract suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<Image>>
    abstract suspend fun getImageUrlByImageId(imageId: UUID): String?
    abstract suspend fun getTrackById(trackId: String): Track?
    abstract suspend fun getTracksByIds(trackIds: List<String>): List<Track>
    abstract suspend fun getAlbumsByIds(albumIds: List<String>): List<Album>
    abstract suspend fun getArtistsByIds(artistIds: List<String>): List<Artist>
    abstract suspend fun getPlaylistsByIds(playlistIds: List<String>, includeTracks: Boolean = false): List<Playlist>

    private var accessToken: Pair<AccessTokenResponse, Long>? = null

    init {
        logger.info("Initializing MetadataService for $providerName")
        instances[metadataType] = this
    }

    companion object {
        val isFetching = AtomicBoolean(false)

        private var instances: MutableMap<MetadataType, MetadataService> = mutableMapOf()

        @Suppress("EnumEntryName")
        enum class MetadataType {
            tidal,
            spotify,
            imageCache,
        }

        fun getMetadataService(type: MetadataType, environment: ApplicationEnvironment): MetadataService {
            if (instances.contains(type)) return instances[type]!!

            return when (type) {
                MetadataType.tidal -> TidalService(environment)
                MetadataType.spotify -> SpotifyService(environment)
                MetadataType.imageCache -> ImageCacheService(environment)
            }
        }
    }

    open fun supported(): Boolean {
        return true
    }

    protected open suspend fun getAccessToken(): AccessTokenResponse {
        if (clientId == null || clientSecret == null) throw NullPointerException("$providerName credentials are null. ($clientIdConfigPath & $clientSecretConfigPath)")

        if ((accessToken?.second ?: 0) > System.currentTimeMillis()) return accessToken!!.first

        logger.info("Requesting access token for $providerName")

        val response = ApiClient.instance.post(tokenUrl) {
            header(HttpHeaders.ContentType, ContentType.parse("application/x-www-form-urlencoded"))
            parameter("grant_type", "client_credentials")
            getAccessTokenHeader(clientId!!, clientSecret!!)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.info("Something went wrong while fetching access token for $providerName, ${response.status}: ${response.bodyAsText()}")
            delay(30.seconds)
            return getAccessToken()
        }

        val tokenResponse = response.body<AccessTokenResponse>()

        logger.info("Got new access token for $providerName")

        accessToken = Pair(
            tokenResponse,
            System.currentTimeMillis() + tokenResponse.expiresIn.seconds.inWholeMilliseconds
        )
        return tokenResponse
    }

    @Serializable
    protected data class AccessTokenResponse(
        @SerialName("access_token")
        val accessToken: String,
        @SerialName("token_type")
        val tokenType: String,
        @SerialName("expires_in")
        val expiresIn: Int,
    )

    @Serializable
    data class Artist(
        val id: String,
        val name: String,
        val popularity: Float,
        val url: String? = null,
        val images: List<Image>,
    )

    @Serializable
    data class Image(
        val url: String,
        val width: Int,
        val height: Int,
    )

    @Serializable
    data class Track(
        val id: String,
        val title: String,
        val artists: List<String> = emptyList(),
        @Serializable(with = DurationSerializer::class)
        val duration: Duration,
        @Serializable(with = OffsetDateTimeSerializer::class)
        val createdAt: OffsetDateTime? = null,
        val images: List<Image>,
    )

    @Serializable
    data class Album(
        val id: String,
        val title: String,
        val artists: List<String> = emptyList(),
        @Serializable(with = DurationSerializer::class)
        val duration: Duration,
        val trackCount: Int,
        val discCount: Int,
        @Serializable(with = LocalDateSerializer::class)
        val releaseDate: LocalDate? = null,
        val images: List<Image>,
    )

    @Serializable
    data class Playlist(
        val id: String,
        val name: String,
        val description: String,
        val tracks: List<Track> = emptyList(),
        val trackCount: Int = tracks.size,
        @Serializable(with = OffsetDateTimeSerializer::class)
        val createdAt: OffsetDateTime? = null,
        @Serializable(with = OffsetDateTimeSerializer::class)
        val modifiedAt: OffsetDateTime? = null,
        val images: List<Image>,
    )
}