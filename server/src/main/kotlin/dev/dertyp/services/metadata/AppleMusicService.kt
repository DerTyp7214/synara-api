package dev.dertyp.services.metadata

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientPriority
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class AppleMusicService(
    environment: ApplicationEnvironment
) : MetadataService("Apple Music", IMetadataService.MetadataType.appleMusic, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath = ""
    override val clientSecretConfigPath = ""

    private val teamId by lazy { environment.config.propertyOrNull("appleMusic.teamId")?.getString() }
    private val keyId by lazy { environment.config.propertyOrNull("appleMusic.keyId")?.getString() }
    private val p8Path by lazy { environment.config.propertyOrNull("appleMusic.p8Path")?.getString() }

    private var appleMusicToken: String? = null
    private var tokenExpiration: Long = 0

    private fun getAppleMusicToken(): String? {
        if (teamId == null || keyId == null || p8Path == null) return null
        if (appleMusicToken != null && System.currentTimeMillis() < tokenExpiration) return appleMusicToken

        val file = File(p8Path!!)
        if (!file.exists()) {
            logger.error("p8 file not found at $p8Path")
            return null
        }

        val keyContent = file.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(keyContent)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("EC")
        val privateKey = kf.generatePrivate(spec) as ECPrivateKey

        val expiration = System.currentTimeMillis() + 30.minutes.inWholeMilliseconds
        tokenExpiration = expiration

        appleMusicToken = JWT.create()
            .withHeader(mapOf("alg" to "ES256", "kid" to keyId))
            .withIssuer(teamId)
            .withIssuedAt(Date())
            .withExpiresAt(Date(expiration))
            .sign(Algorithm.ECDSA256(null, privateKey))

        return appleMusicToken
    }

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {}

    override suspend fun getAccessToken(): IMetadataService.AccessTokenResponse {
        return IMetadataService.AccessTokenResponse("", "", 0)
    }

    override suspend fun search(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Track> {
        val response = ApiClient.instance.get("https://itunes.apple.com/search") {
            parameter("term", query)
            parameter("entity", "song")
            parameter("limit", limit)
        }

        if (response.status != HttpStatusCode.OK) return emptyList()

        val body = response.bodyAsText().trim()
        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body)
        return searchResponse.results.filter { it.wrapperType == "track" }.map { track ->
            IMetadataService.Track(
                id = track.collectionId.toString(),
                title = track.trackName ?: "",
                artists = listOf(track.artistName),
                duration = (track.trackTimeMillis ?: 0L).milliseconds,
                images = listOf(
                    IMetadataService.Image(
                        url = track.artworkUrl100.replace("100x100bb", "600x600bb"),
                        width = 600,
                        height = 600
                    )
                ),
                isrc = track.primaryIsrc
            )
        }
    }

    override suspend fun getTrackByIsrc(
        isrc: String,
        priority: HttpClientPriority
    ): IMetadataService.Track? {
        val token = getAppleMusicToken()
        if (token != null) {
            val storefront = "us"
            val response = try {
                ApiClient.instance.get("https://api.music.apple.com/v1/catalog/$storefront/songs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("filter[isrc]", isrc)
                }
            } catch (e: Exception) {
                logger.error("Failed to call Apple Music Catalog API", e)
                null
            }

            if (response?.status == HttpStatusCode.OK) {
                val body = response.bodyAsText()
                val json = ApplicationScope.json.parseToJsonElement(body).jsonObject
                val data = json["data"]?.jsonArray
                val trackObj = data?.firstOrNull()?.jsonObject
                if (trackObj != null) {
                    val attributes = trackObj["attributes"]?.jsonObject
                    val id = trackObj["id"]?.jsonPrimitive?.content ?: ""
                    val title = attributes?.get("name")?.jsonPrimitive?.content ?: ""
                    val artists = attributes?.get("artistName")?.jsonPrimitive?.content?.let { listOf(it) } ?: emptyList()
                    val duration = attributes?.get("durationInMillis")?.jsonPrimitive?.content?.toLongOrNull()?.milliseconds ?: 0L.milliseconds

                    val artwork = attributes?.get("artwork")?.jsonObject
                    val artworkUrl = artwork?.get("url")?.jsonPrimitive?.content
                        ?.replace("{w}", "600")
                        ?.replace("{h}", "600")
                        ?.replace("{f}", "jpg") ?: ""

                    return IMetadataService.Track(
                        id = id,
                        title = title,
                        artists = artists,
                        duration = duration,
                        images = if (artworkUrl.isNotBlank()) listOf(
                            IMetadataService.Image(url = artworkUrl, width = 600, height = 600)
                        ) else emptyList(),
                        isrc = isrc
                    )
                }
            } else if (response != null) {
                logger.warn("Apple Music Catalog API failed with status ${response.status}: ${response.bodyAsText()}")
            }
        }

        val response = ApiClient.instance.get("https://itunes.apple.com/lookup") {
            parameter("isrc", isrc)
        }

        if (response.status != HttpStatusCode.OK) return null

        val body = response.bodyAsText().trim()
        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body)
        val track = searchResponse.results.firstOrNull { it.wrapperType == "track" } ?: return null

        return IMetadataService.Track(
            id = track.collectionId.toString(),
            title = track.trackName ?: "",
            artists = listOf(track.artistName),
            duration = (track.trackTimeMillis ?: 0L).milliseconds,
            images = listOf(
                IMetadataService.Image(
                    url = track.artworkUrl100.replace("100x100bb", "600x600bb"),
                    width = 600,
                    height = 600
                )
            ),
            isrc = track.primaryIsrc
        )
    }

    override suspend fun getAlbumByBarcode(
        barcode: String,
        priority: HttpClientPriority
    ): IMetadataService.Album? {
        val response = ApiClient.instance.get("https://itunes.apple.com/lookup") {
            parameter("upc", barcode)
        }

        if (response.status != HttpStatusCode.OK) return null

        val body = response.bodyAsText().trim()
        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body)
        val album = searchResponse.results.firstOrNull { it.wrapperType == "collection" } ?: return null

        return IMetadataService.Album(
            id = album.collectionId.toString(),
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
            barcode = barcode
        )
    }

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Artist> {
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

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean,
        priority: HttpClientPriority
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
        val trackName: String? = null,
        val trackTimeMillis: Long? = null,
        val primaryIsrc: String? = null
    )
}
