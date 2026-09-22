package dev.dertyp.services.metadata

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.safeQueuedGet
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.LocalDate
import java.time.OffsetDateTime
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
    val storefront: String by lazy {
        environment.config.propertyOrNull("appleMusic.storefront")?.getString()?.takeUnless { it.isBlank() } ?: "us"
    }

    val catalogEnabled: Boolean
        get() = !teamId.isNullOrBlank() && !keyId.isNullOrBlank() && !p8Path.isNullOrBlank()

    private var appleMusicToken: String? = null
    private var tokenExpiration: Long = 0

    private fun getAppleMusicToken(): String? {
        if (appleMusicToken != null && System.currentTimeMillis() < tokenExpiration) return appleMusicToken
        if (!catalogEnabled) return null

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
        val body = ApiClient.instance.safeQueuedGet<String>("https://itunes.apple.com/search", priority) {
            parameter("term", query)
            parameter("entity", "song")
            parameter("limit", limit)
        } ?: return emptyList()

        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body.trim())
        return searchResponse.results.filter { it.wrapperType == "track" }.map { track ->
            IMetadataService.Track(
                id = track.collectionId?.toString() ?: "",
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
            val response = try {
                ApiClient.queueInstance.enqueue("https://api.music.apple.com/v1/catalog/$storefront/songs", priority) {
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

        val body = ApiClient.instance.safeQueuedGet<String>("https://itunes.apple.com/lookup", priority) {
            parameter("isrc", isrc)
        } ?: return null

        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body.trim())
        val track = searchResponse.results.firstOrNull { it.wrapperType == "track" } ?: return null

        return IMetadataService.Track(
            id = track.collectionId?.toString() ?: "",
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
        val body = ApiClient.instance.safeQueuedGet<String>("https://itunes.apple.com/lookup", priority) {
            parameter("upc", barcode)
        } ?: return null

        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body.trim())
        val album = searchResponse.results.firstOrNull { it.wrapperType == "collection" && it.collectionId != null } ?: return null

        return IMetadataService.Album(
            id = album.collectionId.toString(),
            title = album.collectionName ?: "",
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
        val body = ApiClient.instance.safeQueuedGet<String>("https://itunes.apple.com/search", priority) {
            parameter("term", query)
            parameter("entity", "musicArtist")
            parameter("limit", limit)
        } ?: return emptyList()

        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesArtist>>(body.trim())
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
        val body = ApiClient.instance.safeQueuedGet<String>("https://itunes.apple.com/search", priority) {
            parameter("term", query)
            parameter("entity", if (includeTracks) "album,song" else "album")
            parameter("limit", limit)
        } ?: return emptyList()

        val searchResponse = ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body.trim())
        return searchResponse.results
            .groupBy { it.collectionId }
            .map { (collectionId, results) ->
                val album = results.first { (it.wrapperType == "collection" || it.wrapperType == "track") && it.collectionId != null }
                val additionalTitles = results.mapNotNull { it.trackName }

                IMetadataService.Album(
                    id = (collectionId ?: 0L).toString(),
                    title = album.collectionName ?: "",
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

    private fun itunesImage(artworkUrl100: String): IMetadataService.Image? {
        if (artworkUrl100.isBlank()) return null
        return IMetadataService.Image(
            url = maxArtworkUrl(artworkUrl100),
            width = ARTWORK_NOMINAL,
            height = ARTWORK_NOMINAL
        )
    }

    private fun ITunesAlbum.toTrack(): IMetadataService.Track = IMetadataService.Track(
        id = trackId?.toString() ?: "",
        title = trackName ?: "",
        artists = listOf(artistName),
        duration = (trackTimeMillis ?: 0L).milliseconds,
        trackNumber = trackNumber,
        discNumber = discNumber,
        images = itunesImage(artworkUrl100)?.let { listOf(it) } ?: emptyList(),
        albumId = collectionId?.toString(),
        albumTitle = collectionName,
        isrc = primaryIsrc
    )

    private suspend fun lookup(id: String, entity: String, priority: HttpClientPriority): List<ITunesAlbum> {
        val body = ApiClient.instance.safeQueuedGet<String>("https://itunes.apple.com/lookup", priority) {
            parameter("id", id)
            parameter("entity", entity)
            parameter("limit", 200)
        } ?: return emptyList()
        return ApplicationScope.json.decodeFromString<ITunesSearchResponse<ITunesAlbum>>(body.trim()).results
    }

    companion object {
        // Request a huge size so mzstatic returns the native maximum: for the "w" format it clamps
        // to the source resolution (it does not upscale), and "-999" forces max quality. Technique
        // from qsniyg/maxurl (the library the MusicBrainz cover-art maximisers use).
        private const val ARTWORK_MAX_TAIL = "10000x0w-999.jpg"

        // Nominal dimension used only for the width/height hint when the API reports none. The
        // fetched image is the true native size; these fields are just metadata.
        private const val ARTWORK_NOMINAL = 3000

        // Matches the trailing size segment of both catalog templates (".../{w}x{h}{c}.{f}",
        // ".../{w}x{h}bb.jpg") and iTunes thumbnails (".../100x100bb.jpg").
        private val ARTWORK_SIZE_TAIL = Regex("""/(?:\{w}|\d+)x(?:\{h}|\d+)[^/]*$""")

        private const val CATALOG_ID_CHUNK = 100

        private const val CATALOG_MAX_PAGES = 20

        /** Rewrite a catalog artwork template or iTunes thumbnail URL to request the native-max image. */
        internal fun maxArtworkUrl(templateOrThumb: String): String =
            templateOrThumb.replace(ARTWORK_SIZE_TAIL, "/$ARTWORK_MAX_TAIL")

        internal fun artworkUrlForSize(templateOrUrl: String, size: Int): String =
            if (size <= 0) maxArtworkUrl(templateOrUrl)
            else templateOrUrl.replace(ARTWORK_SIZE_TAIL, "/${size}x${size}bb.jpg")

        internal fun parseReleaseDate(value: String?): LocalDate? = value?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull()
                ?: when (it.length) {
                    7 -> runCatching { LocalDate.parse("$it-01") }.getOrNull()
                    4 -> runCatching { LocalDate.parse("$it-01-01") }.getOrNull()
                    else -> null
                }
        }
    }

    private suspend fun catalogGet(
        pathOrUrl: String,
        priority: HttpClientPriority,
        block: suspend HttpRequestBuilder.() -> Unit = {}
    ): JsonObject? {
        val token = getAppleMusicToken() ?: return null
        val url = if (pathOrUrl.startsWith("http")) pathOrUrl else "https://api.music.apple.com$pathOrUrl"
        val response = try {
            ApiClient.queueInstance.enqueue(url, priority) {
                header(HttpHeaders.Authorization, "Bearer $token")
                block()
            }
        } catch (e: Exception) {
            logger.error("Failed to call Apple Music Catalog API: $url", e)
            return null
        }
        if (response.status == HttpStatusCode.OK) {
            return ApplicationScope.json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        logger.warn("Apple Music Catalog API failed with status ${response.status}")
        return null
    }

    private fun catalogArtwork(attributes: JsonObject?): IMetadataService.Image? {
        val artwork = attributes?.get("artwork")?.jsonObject ?: return null
        val template = artwork["url"]?.jsonPrimitive?.contentOrNull ?: return null
        // The fetched image is native-max; the reported width/height (which is not the true max)
        // is used only as a conservative metadata hint.
        val width = artwork["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ARTWORK_NOMINAL
        val height = artwork["height"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ARTWORK_NOMINAL
        return IMetadataService.Image(url = maxArtworkUrl(template), width = width, height = height)
    }

    private fun catalogTracksFrom(json: JsonObject): List<IMetadataService.Track> =
        json["data"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "songs") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val attr = obj["attributes"]?.jsonObject
            IMetadataService.Track(
                id = id,
                title = attr?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                artists = attr?.get("artistName")?.jsonPrimitive?.contentOrNull?.let { listOf(it) } ?: emptyList(),
                duration = (attr?.get("durationInMillis")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L).milliseconds,
                trackNumber = attr?.get("trackNumber")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                discNumber = attr?.get("discNumber")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                images = catalogArtwork(attr)?.let { listOf(it) } ?: emptyList(),
                albumTitle = attr?.get("albumName")?.jsonPrimitive?.contentOrNull,
                isrc = attr?.get("isrc")?.jsonPrimitive?.contentOrNull
            )
        } ?: emptyList()

    private fun relationshipIds(relationships: JsonObject?, name: String): List<String> =
        relationships?.get(name)?.jsonObject?.get("data")?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            ?: emptyList()

    private fun catalogAlbumFrom(element: JsonElement): CatalogAlbum? {
        val obj = element.jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "albums") return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val attr = obj["attributes"]?.jsonObject
        return CatalogAlbum(
            id = id,
            title = attr?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
            artistName = attr?.get("artistName")?.jsonPrimitive?.contentOrNull ?: "",
            artistIds = relationshipIds(obj["relationships"]?.jsonObject, "artists"),
            releaseDate = parseReleaseDate(attr?.get("releaseDate")?.jsonPrimitive?.contentOrNull),
            isSingle = attr?.get("isSingle")?.jsonPrimitive?.contentOrNull?.toBoolean() == true,
            isComplete = attr?.get("isComplete")?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
            isCompilation = attr?.get("isCompilation")?.jsonPrimitive?.contentOrNull?.toBoolean() == true,
            upc = attr?.get("upc")?.jsonPrimitive?.contentOrNull,
            url = attr?.get("url")?.jsonPrimitive?.contentOrNull,
            trackCount = attr?.get("trackCount")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            image = catalogArtwork(attr),
            recordLabel = attr?.get("recordLabel")?.jsonPrimitive?.contentOrNull,
            copyright = attr?.get("copyright")?.jsonPrimitive?.contentOrNull,
            genreNames = attr?.get("genreNames")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        )
    }

    private fun catalogSongRefsFrom(json: JsonObject): List<CatalogSongRef> =
        json["data"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "songs") return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val relationships = obj["relationships"]?.jsonObject
            CatalogSongRef(
                id = id,
                artistIds = relationshipIds(relationships, "artists"),
                albumIds = relationshipIds(relationships, "albums")
            )
        } ?: emptyList()

    private fun catalogAlbumsFrom(json: JsonObject): List<CatalogAlbum> =
        json["data"]?.jsonArray?.mapNotNull { catalogAlbumFrom(it) } ?: emptyList()

    suspend fun getArtistCatalogAlbums(
        appleArtistId: String,
        priority: HttpClientPriority = HttpClientPriority.LOW
    ): List<CatalogAlbum>? {
        if (!catalogEnabled) return null
        val id = appleArtistId.removePrefix("appleMusic:")
        val albums = mutableListOf<CatalogAlbum>()
        var next: String? = "/v1/catalog/$storefront/artists/$id/albums?limit=100"
        var pages = 0
        while (next != null && pages < CATALOG_MAX_PAGES) {
            val json = catalogGet(next, priority) ?: return null
            albums.addAll(catalogAlbumsFrom(json))
            next = json["next"]?.jsonPrimitive?.contentOrNull
            pages++
        }
        return albums
    }

    suspend fun getAlbumIsrcs(
        albumId: String,
        priority: HttpClientPriority = HttpClientPriority.LOW
    ): List<String>? {
        if (!catalogEnabled) return null
        val id = albumId.removePrefix("appleMusic:")
        val isrcs = mutableListOf<String>()
        var next: String? = "/v1/catalog/$storefront/albums/$id/tracks?limit=100"
        var pages = 0
        while (next != null && pages < CATALOG_MAX_PAGES) {
            val json = catalogGet(next, priority) ?: return null
            catalogTracksFrom(json).forEach { track ->
                val isrc = track.isrc?.trim()
                if (!isrc.isNullOrEmpty()) isrcs.add(isrc)
            }
            next = json["next"]?.jsonPrimitive?.contentOrNull
            pages++
        }
        return isrcs.distinct()
    }

    suspend fun getCatalogAlbumsByUpc(
        upc: String,
        priority: HttpClientPriority = HttpClientPriority.LOW
    ): List<CatalogAlbum> {
        if (!catalogEnabled || upc.isBlank()) return emptyList()
        val json = catalogGet("/v1/catalog/$storefront/albums", priority) {
            parameter("filter[upc]", upc)
        } ?: return emptyList()
        return catalogAlbumsFrom(json)
    }

    suspend fun getCatalogAlbumsByIds(
        ids: List<String>,
        priority: HttpClientPriority = HttpClientPriority.LOW
    ): List<CatalogAlbum> {
        if (!catalogEnabled) return emptyList()
        val cleaned = ids.map { it.removePrefix("appleMusic:") }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return emptyList()
        val albums = mutableListOf<CatalogAlbum>()
        cleaned.chunked(CATALOG_ID_CHUNK).forEach { chunk ->
            val json = catalogGet("/v1/catalog/$storefront/albums", priority) {
                parameter("ids", chunk.joinToString(","))
            } ?: return@forEach
            albums.addAll(catalogAlbumsFrom(json))
        }
        return albums
    }

    suspend fun getCatalogSongsByIsrc(
        isrc: String,
        priority: HttpClientPriority = HttpClientPriority.LOW
    ): List<CatalogSongRef> {
        if (!catalogEnabled || isrc.isBlank()) return emptyList()
        val json = catalogGet("/v1/catalog/$storefront/songs", priority) {
            parameter("filter[isrc]", isrc)
        } ?: return emptyList()
        return catalogSongRefsFrom(json)
    }

    suspend fun getCatalogSongsByIds(
        ids: List<String>,
        priority: HttpClientPriority = HttpClientPriority.LOW
    ): List<CatalogSongRef> {
        if (!catalogEnabled) return emptyList()
        val cleaned = ids.map { it.removePrefix("appleMusic:") }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return emptyList()
        val songs = mutableListOf<CatalogSongRef>()
        cleaned.chunked(CATALOG_ID_CHUNK).forEach { chunk ->
            val json = catalogGet("/v1/catalog/$storefront/songs", priority) {
                parameter("ids", chunk.joinToString(","))
            } ?: return@forEach
            songs.addAll(catalogSongRefsFrom(json))
        }
        return songs
    }

    override fun getAlbumTracks(albumId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> = flow {
        val id = albumId.removePrefix("appleMusic:")
        if (getAppleMusicToken() != null) {
            var next: String? = "/v1/catalog/$storefront/albums/$id/tracks?limit=100"
            while (next != null) {
                val json = catalogGet(next, priority) ?: break
                catalogTracksFrom(json).forEach { emit(it) }
                next = json["next"]?.jsonPrimitive?.contentOrNull
            }
            return@flow
        }
        lookup(id, "song", priority)
            .filter { it.wrapperType == "track" && it.trackId != null }
            .forEach { emit(it.toTrack()) }
    }

    override fun getArtistTracks(artistId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> = flow {
        val id = artistId.removePrefix("appleMusic:")
        if (getAppleMusicToken() != null) {
            val albumIds = mutableListOf<String>()
            var next: String? = "/v1/catalog/$storefront/artists/$id/albums?limit=100"
            while (next != null) {
                val json = catalogGet(next, priority) ?: break
                json["data"]?.jsonArray?.forEach { el ->
                    el.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.let { albumIds.add(it) }
                }
                next = json["next"]?.jsonPrimitive?.contentOrNull
            }
            albumIds.forEach { emitAll(getAlbumTracks(it, priority)) }
            return@flow
        }
        lookup(id, "song", priority)
            .filter { it.wrapperType == "track" && it.trackId != null }
            .forEach { emit(it.toTrack()) }
    }

    override suspend fun getAlbumsByIds(
        albumIds: List<String>,
        priority: HttpClientPriority
    ): List<IMetadataService.Album> = albumIds.mapNotNull { raw ->
        val id = raw.removePrefix("appleMusic:")
        if (getAppleMusicToken() != null) {
            val attr = catalogGet("/v1/catalog/$storefront/albums/$id", priority)
                ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject?.get("attributes")?.jsonObject
                ?: return@mapNotNull null
            IMetadataService.Album(
                id = raw,
                title = attr["name"]?.jsonPrimitive?.contentOrNull ?: "",
                artists = attr["artistName"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) } ?: emptyList(),
                trackCount = attr["trackCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                releaseDate = parseReleaseDate(attr["releaseDate"]?.jsonPrimitive?.contentOrNull),
                images = catalogArtwork(attr)?.let { listOf(it) } ?: emptyList()
            )
        } else {
            val results = lookup(id, "song", priority)
            val collection = results.firstOrNull { it.wrapperType == "collection" && it.collectionId != null }
                ?: return@mapNotNull null
            val trackEntries = results.filter { it.wrapperType == "track" }
            IMetadataService.Album(
                id = raw,
                title = collection.collectionName ?: "",
                artists = listOf(collection.artistName),
                trackCount = if (collection.trackCount > 0) collection.trackCount else trackEntries.size,
                releaseDate = parseReleaseDate(collection.releaseDate),
                images = itunesImage(collection.artworkUrl100)?.let { listOf(it) } ?: emptyList()
            )
        }
    }

    data class CatalogAlbum(
        val id: String,
        val title: String,
        val artistName: String,
        val artistIds: List<String>,
        val releaseDate: LocalDate?,
        val isSingle: Boolean,
        val isComplete: Boolean,
        val isCompilation: Boolean,
        val upc: String?,
        val url: String?,
        val trackCount: Int,
        val image: IMetadataService.Image?,
        val recordLabel: String? = null,
        val copyright: String? = null,
        val genreNames: List<String> = emptyList()
    )

    data class CatalogSongRef(
        val id: String,
        val artistIds: List<String>,
        val albumIds: List<String>
    )

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
        val collectionId: Long? = null,
        val artistName: String = "",
        val collectionName: String? = null,
        val artworkUrl100: String = "",
        val trackCount: Int = 0,
        val trackId: Long? = null,
        val trackName: String? = null,
        val trackNumber: Int? = null,
        val discNumber: Int? = null,
        val trackTimeMillis: Long? = null,
        val releaseDate: String? = null,
        val primaryIsrc: String? = null
    )
}
