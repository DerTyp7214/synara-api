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
import kotlinx.coroutines.flow.emptyFlow
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

    open val supportedFeatures: Set<IMetadataService.Feature> by lazy {
        if (!supported()) return@lazy emptySet()
        val features = mutableSetOf<IMetadataService.Feature>()
        val baseClass = MetadataService::class.java
        val interfaceClass = IMetadataService::class.java

        val annotatedMethods = interfaceClass.declaredMethods.filter {
            it.isAnnotationPresent(IMetadataService.ProvidesFeature::class.java)
        }

        baseClass.declaredMethods.forEach { baseMethod ->
            val feature = annotatedMethods.find { it.name == baseMethod.name }
                ?.getAnnotation(IMetadataService.ProvidesFeature::class.java)?.feature
                ?: return@forEach

            try {
                val subMethod = this.javaClass.getMethod(baseMethod.name, *baseMethod.parameterTypes)
                if (subMethod.declaringClass != baseClass) {
                    features.add(feature)
                }
            } catch (_: NoSuchMethodException) {
            }
        }
        features
    }

    override suspend fun getSupportedFeatures(type: MetadataType): Set<IMetadataService.Feature> = supportedFeatures

    override suspend fun getAllMetadataTypes(features: Set<IMetadataService.Feature>): List<MetadataType> = MetadataType.all()

    override suspend fun searchArtists(
        type: MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Artist> = searchArtists(query, limit)

    open suspend fun searchArtists(
        query: String,
        limit: Int = 50,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Artist> = emptyList()

    override suspend fun search(
        type: MetadataType,
        query: String,
        limit: Int
    ): List<IMetadataService.Track> = search(query, limit)

    open suspend fun search(
        query: String,
        limit: Int = 50,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Track> = emptyList()

    override suspend fun searchAlbums(
        type: MetadataType,
        query: String,
        limit: Int,
        includeTracks: Boolean
    ): List<IMetadataService.Album> = searchAlbums(query, limit, includeTracks)

    open suspend fun searchAlbums(
        query: String,
        limit: Int = 50,
        includeTracks: Boolean = false,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Album> = emptyList()

    override suspend fun getAlbumIdByTrackId(
        type: MetadataType,
        trackId: String
    ): String? = getAlbumIdByTrackId(trackId)

    open suspend fun getAlbumIdByTrackId(
        trackId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): String? = null

    override suspend fun getImageUrlByAlbumId(
        type: MetadataType,
        albumId: String
    ): List<IMetadataService.Image> = getImageUrlByAlbumId(albumId)

    open suspend fun getImageUrlByAlbumId(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Image> = emptyList()

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

    open suspend fun getImageUrlsByAlbumIds(
        albumIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Map<String, List<IMetadataService.Image>> = emptyMap()

    override suspend fun getImageUrlByImageId(
        type: MetadataType,
        imageId: UUID
    ): String? = getImageUrlByImageId(imageId)

    open suspend fun getImageUrlByImageId(
        imageId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): String? = null

    override suspend fun getTrackById(
        type: MetadataType,
        trackId: String
    ): IMetadataService.Track? = getTrackById(trackId)

    open suspend fun getTrackById(
        trackId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Track? = null

    override suspend fun getTrackByIsrc(
        type: MetadataType,
        isrc: String
    ): IMetadataService.Track? = getTrackByIsrc(isrc)

    open suspend fun getTrackByIsrc(
        isrc: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Track? = null

    override suspend fun getTracksByIds(
        type: MetadataType,
        trackIds: List<String>
    ): List<IMetadataService.Track> = getTracksByIds(trackIds)

    open suspend fun getTracksByIds(
        trackIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Track> = emptyList()

    override suspend fun getAlbumsByIds(
        type: MetadataType,
        albumIds: List<String>
    ): List<IMetadataService.Album> = getAlbumsByIds(albumIds)

    open suspend fun getAlbumsByIds(
        albumIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Album> = emptyList()

    override suspend fun getAlbumByBarcode(
        type: MetadataType,
        barcode: String
    ): IMetadataService.Album? = getAlbumByBarcode(barcode)

    open suspend fun getAlbumByBarcode(
        barcode: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): IMetadataService.Album? = null

    override suspend fun albumExistsById(
        type: MetadataType,
        albumId: String
    ): Boolean = albumExistsById(albumId)

    open suspend fun albumExistsById(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Boolean = false

    override suspend fun getArtistsByIds(
        type: MetadataType,
        artistIds: List<String>
    ): List<IMetadataService.Artist> = getArtistsByIds(artistIds)

    open suspend fun getArtistsByIds(
        artistIds: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<IMetadataService.Artist> = emptyList()

    open fun getAlbumTracks(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Flow<IMetadataService.Track> = emptyFlow()

    open fun getArtistTracks(
        artistId: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Flow<IMetadataService.Track> = emptyFlow()

    open fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean = false,
        user: User? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): Flow<IMetadataService.FlowPlaylist> = emptyFlow()

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
