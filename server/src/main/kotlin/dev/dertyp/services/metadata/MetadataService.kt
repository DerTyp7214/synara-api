package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.User
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.Service
import dev.dertyp.services.metadata.IMetadataService.MetadataType
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
import org.koin.mp.KoinPlatformTools
import java.util.UUID
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
abstract class MetadataService(
    private val providerName: String,
    metadataType: MetadataType,
    protected val environment: ApplicationEnvironment
) : IMetadataService, Service() {
    protected abstract val clientIdConfigPath: String
    protected abstract val clientSecretConfigPath: String
    protected abstract val tokenUrl: String

    private val clientId by lazy { environment.config.propertyOrNull(clientIdConfigPath)?.getString() }
    private val clientSecret by lazy { environment.config.propertyOrNull(clientSecretConfigPath)?.getString() }

    protected abstract fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String)

    override suspend fun searchArtists(
        type: MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Artist> = searchArtists(query, limit)

    abstract suspend fun searchArtists(
        query: String,
        limit: Int = 50,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Artist>

    override suspend fun search(
        type: MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Track> = search(query, limit)

    abstract suspend fun search(
        query: String,
        limit: Int = 50,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Track>

    override suspend fun searchAlbums(
        type: MetadataType,
        query: String,
        limit: Int,
        includeTracks: Boolean
    ): List<IMetadataService.Album> = searchAlbums(query, limit, includeTracks)

    abstract suspend fun searchAlbums(
        query: String,
        limit: Int = 50,
        includeTracks: Boolean = false,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Album>

    override suspend fun getAlbumIdByTrackId(
        type: MetadataType,
        trackId: String
    ): String? = getAlbumIdByTrackId(trackId)

    abstract suspend fun getAlbumIdByTrackId(
        trackId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): String?

    override suspend fun getImageUrlByAlbumId(
        type: MetadataType,
        albumId: String
    ): List<IMetadataService.Image> = getImageUrlByAlbumId(albumId)

    abstract suspend fun getImageUrlByAlbumId(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Image>

    override suspend fun getArtistByMbId(
        type: MetadataType,
        mbId: UUID
    ): IMetadataService.Artist? = getArtistByMbId(mbId)

    open suspend fun getArtistByMbId(
        mbId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Artist? = null

    override suspend fun getAlbumByMbId(
        type: MetadataType,
        mbId: UUID
    ): IMetadataService.Album? = getAlbumByMbId(mbId)

    open suspend fun getAlbumByMbId(
        mbId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Album? = null

    override suspend fun getTrackByMbId(
        type: MetadataType,
        mbId: UUID
    ): IMetadataService.Track? = getTrackByMbId(mbId)

    open suspend fun getTrackByMbId(
        mbId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Track? = null

    override suspend fun getImageUrlByArtistMbId(
        type: MetadataType,
        mbId: UUID
    ): List<IMetadataService.Image> = getImageUrlByArtistMbId(mbId)

    open suspend fun getImageUrlByArtistMbId(
        mbId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Image> = emptyList()

    override suspend fun getImageUrlByAlbumMbId(
        type: MetadataType,
        mbId: UUID
    ): List<IMetadataService.Image> = getImageUrlByAlbumMbId(mbId)

    open suspend fun getImageUrlByAlbumMbId(
        mbId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Image> = emptyList()

    override suspend fun getImageUrlByTrackMbId(
        type: MetadataType,
        mbId: UUID
    ): List<IMetadataService.Image> = getImageUrlByTrackMbId(mbId)

    open suspend fun getImageUrlByTrackMbId(
        mbId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Image> = emptyList()

    override suspend fun getImageUrlsByAlbumIds(
        type: MetadataType,
        albumIds: List<String>
    ): Map<String, List<IMetadataService.Image>> = getImageUrlsByAlbumIds(albumIds)

    abstract suspend fun getImageUrlsByAlbumIds(
        albumIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Map<String, List<IMetadataService.Image>>

    override suspend fun getImageUrlByImageId(
        type: MetadataType,
        imageId: UUID
    ): String? = getImageUrlByImageId(imageId)

    abstract suspend fun getImageUrlByImageId(
        imageId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): String?

    override suspend fun getTrackById(
        type: MetadataType,
        trackId: String
    ): IMetadataService.Track? = getTrackById(trackId)

    abstract suspend fun getTrackById(
        trackId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Track?

    override suspend fun getTracksByIds(
        type: MetadataType,
        trackIds: List<String>
    ): List<IMetadataService.Track> = getTracksByIds(trackIds)

    abstract suspend fun getTracksByIds(
        trackIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Track>

    override suspend fun getAlbumsByIds(
        type: MetadataType,
        albumIds: List<String>
    ): List<IMetadataService.Album> = getAlbumsByIds(albumIds)

    abstract suspend fun getAlbumsByIds(
        albumIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Album>

    override suspend fun albumExistsById(
        type: MetadataType,
        albumId: String
    ): Boolean = albumExistsById(albumId)

    abstract suspend fun albumExistsById(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Boolean

    override suspend fun getArtistsByIds(
        type: MetadataType,
        artistIds: List<String>
    ): List<IMetadataService.Artist> = getArtistsByIds(artistIds)

    abstract suspend fun getArtistsByIds(
        artistIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Artist>

    abstract fun getAlbumTracks(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Flow<IMetadataService.Track>

    abstract fun getArtistTracks(
        artistId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Flow<IMetadataService.Track>

    abstract fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean = false,
        user: User? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Flow<IMetadataService.FlowPlaylist>

    private var accessToken: Pair<IMetadataService.AccessTokenResponse, Long>? = null

    init {
        logger.info("Initializing MetadataService for $providerName")
        instances[metadataType] = this
    }

    companion object {
        val isFetching = AtomicBoolean(false)

        private val instances: MutableMap<MetadataType, MetadataService> = mutableMapOf()

        fun register(type: MetadataType, service: MetadataService) {
            instances[type] = service
        }

        fun getMetadataService(type: MetadataType, environment: ApplicationEnvironment): MetadataService {
            instances[type]?.let { return it }

            return when (type) {
                MetadataType.tidal -> TidalService(environment)
                MetadataType.spotify -> SpotifyService(environment)
                MetadataType.appleMusic -> AppleMusicService(environment)
                MetadataType.deezer -> DeezerService(environment)
                MetadataType.imageCache -> ImageCacheService(environment)
                MetadataType.theAudioDB -> TheAudioDBService(environment)
                MetadataType.musicBrainz -> MusicBrainzMetadataService(
                    KoinPlatformTools.defaultContext().get().get<IMusicBrainzService>(),
                    environment
                )

                else -> {
                    val pluginManager = KoinPlatformTools.defaultContext().get().get<PluginManager>()
                    val service = pluginManager.getMetadataService(type)
                    if (service is MetadataService) {
                        register(type, service)
                        return service
                    }
                    throw IllegalArgumentException("Unknown metadata provider: ${type.value}")
                }
            }
        }
    }

    open fun supported(): Boolean {
        if (clientIdConfigPath.isNotEmpty() && environment.config.propertyOrNull(clientIdConfigPath)?.getString().isNullOrBlank()) return false
        if (clientSecretConfigPath.isNotEmpty() && environment.config.propertyOrNull(clientSecretConfigPath)?.getString().isNullOrBlank()) return false
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