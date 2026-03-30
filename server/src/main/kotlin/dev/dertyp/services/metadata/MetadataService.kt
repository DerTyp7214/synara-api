package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.data.User
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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
    abstract suspend fun searchArtists(query: String, limit: Int = 50): List<IMetadataService.Artist>
    abstract suspend fun search(query: String, limit: Int = 50): List<IMetadataService.Track>
    abstract suspend fun searchAlbums(
        query: String,
        limit: Int = 50,
        includeTracks: Boolean = false
    ): List<IMetadataService.Album>
    abstract suspend fun getAlbumIdByTrackId(trackId: String): String?
    abstract suspend fun getImageUrlByAlbumId(albumId: String): List<IMetadataService.Image>
    abstract suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<IMetadataService.Image>>
    abstract suspend fun getImageUrlByImageId(imageId: UUID): String?
    abstract suspend fun getTrackById(trackId: String): IMetadataService.Track?
    abstract suspend fun getTracksByIds(trackIds: List<String>): List<IMetadataService.Track>
    abstract suspend fun getAlbumsByIds(albumIds: List<String>): List<IMetadataService.Album>
    abstract suspend fun albumExistsById(albumId: String): Boolean
    abstract suspend fun getArtistsByIds(artistIds: List<String>): List<IMetadataService.Artist>
    abstract suspend fun getAlbumTracks(albumId: String): Flow<IMetadataService.Track>
    abstract suspend fun getArtistTracks(artistId: String): Flow<IMetadataService.Track>
    abstract fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean = false,
        user: User? = null
    ): Flow<IMetadataService.FlowPlaylist>

    private var accessToken: Pair<IMetadataService.AccessTokenResponse, Long>? = null

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
            appleMusic,
            imageCache,
        }

        fun getMetadataService(type: MetadataType, environment: ApplicationEnvironment): MetadataService {
            if (instances.contains(type)) return instances[type]!!

            return when (type) {
                MetadataType.tidal -> TidalService(environment)
                MetadataType.spotify -> SpotifyService(environment)
                MetadataType.appleMusic -> AppleMusicService(environment)
                MetadataType.imageCache -> ImageCacheService(environment)
            }
        }
    }

    open fun supported(): Boolean {
        return true
    }

    protected open suspend fun getAccessToken(): IMetadataService.AccessTokenResponse {
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

        val tokenResponse = response.body<IMetadataService.AccessTokenResponse>()

        logger.info("Got new access token for $providerName")

        accessToken = Pair(
            tokenResponse,
            System.currentTimeMillis() + tokenResponse.expiresIn.seconds.inWholeMilliseconds
        )
        return tokenResponse
    }
}