package dev.dertyp.services.metadata

import com.google.gson.annotations.SerializedName
import dev.dertyp.ApiClient
import dev.dertyp.services.Service
import dev.dertyp.services.models.Image
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.util.*
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
    abstract suspend fun searchArtists(query: String, limit: Int = 50): List<Artist>
    abstract suspend fun getAlbumIdByTrackId(trackId: String): String?
    abstract suspend fun getImageUrlByAlbumId(albumId: String): List<Image>
    abstract suspend fun getImageUrlByImageId(imageId: UUID): String?

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
        @SerializedName("access_token")
        val accessToken: String,
        @SerializedName("token_type")
        val tokenType: String,
        @SerializedName("expires_in")
        val expiresIn: Int,
    )

    @Serializable
    data class Artist(
        val id: String,
        val name: String,
        val popularity: Float,
        val url: String?,
        val images: suspend () -> List<Image>,
    ) {
        @Serializable
        data class Image(
            val url: String,
            val width: Int,
            val height: Int,
        )
    }
}