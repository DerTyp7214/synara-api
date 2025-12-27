package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.*
import dev.dertyp.data.User
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.models.tidal.*
import dev.dertyp.services.sync.SyncService
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.core.component.inject
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClient
import java.util.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class TidalService(
    private val environment: ApplicationEnvironment
) : MetadataService("Tidal", Companion.MetadataType.tidal, environment) {
    override val tokenUrl = "https://auth.tidal.com/v1/oauth2/token"
    override val clientIdConfigPath: String = "tidal.clientId"
    override val clientSecretConfigPath: String = "tidal.clientSecret"

    private val jedisConfig by inject<RedisCacheProvider.Config>()
    val jedis by lazy {
        jedisConfig.let {
            if (jedisConfig.host != "none") RedisClient.create(
                HostAndPort(jedisConfig.host, jedisConfig.port)
            )
            else null
        }
    }

    fun List<Track>.cacheTracks(): List<Track> {
        jedis?.let {
            for (track in this) writeToJedis(track)
        }

        return this
    }

    fun List<Album>.cacheAlbums(): List<Album> {
        jedis?.let {
            for (album in this) writeToJedis(album)
        }

        return this
    }

    fun List<Artist>.cacheArtists(): List<Artist> {
        jedis?.let {
            for (artist in this) writeToJedis(artist)
        }

        return this
    }

    fun List<Playlist>.cachePlaylists(): List<Playlist> {
        jedis?.let {
            for (playlist in this) writeToJedis(playlist)
        }

        return this
    }

    fun Flow<FlowPlaylist>.cachePlaylists(): Flow<FlowPlaylist> {
        return jedis?.let {
            onEach {
                ApplicationScope.scope.launch {
                    writeToJedis(it.collect())
                }
            }
        } ?: this
    }

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
        header(HttpHeaders.Authorization, "Basic ${Base64.encode("$clientId:$clientSecret".toByteArray())}")
        header("grant_type", "client_credentials")
    }

    private val baseUrl = URLBuilder().apply {
        protocol = URLProtocol.HTTPS
        host = "openapi.tidal.com"
        encodedPath = "v2"
    }

    private fun getUrl(path: String? = null, block: URLBuilder.() -> Unit = {}): String {
        return url {
            takeFrom(baseUrl)
            if (!path.isNullOrBlank()) appendPathSegments(path)
            block()
        }
    }

    private suspend fun makeRequest(url: String, user: User? = null): HttpResponse {
        return ApiClient.instance.queuedGet(url) {
            val token = if (user != null) {
                SyncService.getInstance(user, environment, SyncService.SyncServiceType.tidal).getAccessToken()?.let {
                    AccessTokenResponse(
                        tokenType = it.tokenType,
                        accessToken = it.accessToken,
                        expiresIn = it.expiresIn
                    )
                } ?: getAccessToken()
            } else getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.Accept, "application/vnd.api+json")
        }
    }

    override suspend fun searchArtists(query: String, limit: Int): List<Artist> {
        val url = getUrl("searchResults") {
            encodedPath += "/" + query.encodeURLParameter()

            parameters {
                append("include", "artists")
                append("countryCode", "US")
            }
        }

        val response = makeRequest(url)

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(30.seconds)
            return searchArtists(query, limit)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.info("Searching artists for $query: $url")
            logger.info(response.bodyAsText())

            when (response.status) {
                HttpStatusCode.BadRequest -> {
                    logger.error("Searching artist for $query failed")
                    logger.error("Status: ${response.status}")
                    return emptyList()
                }

                else -> {
                    delay(30.seconds)
                    return searchArtists(query, limit)
                }
            }
        }

        val searchResponse =
            response.body<SearchResultsSingleResourceDataDocument<ArtistsAttributes, ArtistsRelationships>>()

        return getArtistsByIds(searchResponse.included.map { it.id })
    }

    private suspend fun getImages(urlPath: String?): List<ArtworkFile> {
        if (urlPath == null) return emptyList()

        val url = getUrl {
            appendPathSegments(Url(urlPath).segments)

            parameters {
                append("include", "profileArt")
                append("countryCode", "US")
            }
        }

        val response = makeRequest(url)

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(5.seconds)
            return getImages(urlPath)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.info("Fetching images for $url")
            logger.info(response.bodyAsText())
        }

        val imagesResponse =
            response.body<ArtworksMultiResourceDataDocument<ArtworksAttributes, ArtworksRelationships>>()

        return imagesResponse.included.firstOrNull()?.attributes?.files ?: emptyList()
    }

    override suspend fun getAlbumIdByTrackId(trackId: String): String? {
        val url = getUrl("/tracks/${trackId}/relationships/albums") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getAlbumIdByTrackId]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getAlbumIdByTrackId(trackId)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<TracksMultiRelationshipDataDocument<ResourceIdentifier, EmptyRelationships>>()

            return body.data.first().id
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return null
        }
    }

    override suspend fun getImageUrlByAlbumId(albumId: String): List<Image> {
        val url = getUrl("/albums/${albumId}/relationships/coverArt") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                append("include", "coverArt")
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getImageUrlByAlbumId]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getImageUrlByAlbumId(albumId)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<AlbumsMultiRelationshipDataDocument<ArtworksAttributes, ArtworksRelationships>>()

            return body.included?.flatMap { i ->
                i.attributes.files.map { f -> Image(f.href, f.meta.width, f.meta.height) }
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())
            println(url)
            return listOf()
        }
    }

    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<Image>> {
        if (albumIds.isEmpty()) return emptyMap()

        val url = getUrl("/albums") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                append("include", "coverArt")
                appendAll("filter[id]", albumIds)
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getImageUrlsByAlbumIds]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getImageUrlsByAlbumIds(albumIds)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<AlbumsMultiRelationshipDataDocument<ArtworksAttributes, ArtworksRelationships>>()
            val coverMap = body.included?.associateBy { it.id } ?: emptyMap()

            return body.data.associate { album ->
                Pair(
                    album.id,
                    coverMap[album.relationships?.coverArt?.data?.firstOrNull()?.id]?.attributes?.files?.map { file ->
                        Image(file.href, file.meta.width, file.meta.height)
                    })
            }.filterValueNotNull()
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())
            println(url)
            return albumIds.associateWith { emptyList() }
        }
    }

    override suspend fun getImageUrlByImageId(imageId: UUID): String? {
        throw NotImplementedError("Not implemented for tidal!")
    }

    override suspend fun getTrackById(trackId: String): Track? {
        val existing = getTrackFromJedis(trackId)
        if (existing != null) return existing

        val url = getUrl("/tracks/$trackId") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                appendAll("include", listOf("albums", "artists"))
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getTrackById]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getTrackById(trackId)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<TracksSingleResourceDataDocument<JsonAttribute, EmptyRelationships>>()

            val imageUrls = body.data.singleImage(::getImageUrlByAlbumId)

            val artists = body.included?.mapAttributes<ArtistsAttributes>() ?: emptyMap()

            return body.data.attributes?.let { track ->
                Track(
                    id = body.data.id,
                    title = track.title,
                    duration = track.duration,
                    createdAt = track.createdAt,
                    artists = body.data.artists(artists),
                    images = imageUrls,
                )
            }?.also { writeToJedis(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return null
        }
    }

    override suspend fun getTracksByIds(trackIds: List<String>): List<Track> {
        val filteredTrackIds = trackIds.distinct().toMutableList()
        val existing = checkExistingTracksFromCache(filteredTrackIds)

        filteredTrackIds.removeAll(existing)

        if (filteredTrackIds.isEmpty()) return getTracksFromCache(existing)

        if (filteredTrackIds.size > 20) {
            return filteredTrackIds.chunked(20).flatMap { getTracksByIds(it) }
        }

        val url = getUrl("/tracks") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                appendAll("include", listOf("albums", "artists"))
                appendAll("filter[id]", filteredTrackIds)
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getTracksByIds]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getTracksByIds(filteredTrackIds) + getTracksFromCache(existing)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<TracksMultiResourceDataDocument<JsonAttribute, EmptyRelationships>>()

            val albumIds = body.data.mapNotNull { it.relationships?.albums?.data?.firstOrNull()?.id }
            val imageUrls = getImageUrlsByAlbumIds(albumIds)

            val artists = body.included?.mapAttributes<ArtistsAttributes>() ?: emptyMap()

            return body.data.mapNotNull { trackObj ->
                trackObj.attributes?.let { track ->
                    Track(
                        id = trackObj.id,
                        title = track.title,
                        duration = track.duration,
                        createdAt = track.createdAt,
                        artists = trackObj.artists(artists),
                        images = trackObj.images(imageUrls),
                    )
                }
            }.cacheTracks() + getTracksFromCache(existing)
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return emptyList()
        }
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>): List<Album> {
        val filteredAlbumIds = albumIds.distinct().toMutableList()
        val existing = checkExistingAlbumsFromCache(filteredAlbumIds)

        filteredAlbumIds.removeAll(existing)

        if (filteredAlbumIds.isEmpty()) return getAlbumsFromCache(existing)

        if (filteredAlbumIds.size > 20) {
            return filteredAlbumIds.chunked(20).flatMap { getAlbumsByIds(it) }
        }

        val url = getUrl("/albums") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                appendAll("include", listOf("artists", "coverArt"))
                appendAll("filter[id]", filteredAlbumIds)
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getAlbumsByIds]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getAlbumsByIds(filteredAlbumIds) + getAlbumsFromCache(existing)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<AlbumsMultiRelationshipDataDocument<JsonAttribute, EmptyRelationships>>()

            val images = body.included?.mapAttributes<ArtworksAttributes>() ?: emptyMap()
            val artists = body.included?.mapAttributes<ArtistsAttributes>() ?: emptyMap()

            return body.data.mapNotNull { albumObj ->
                albumObj.attributes?.let { album ->
                    Album(
                        id = albumObj.id,
                        title = album.title,
                        duration = album.duration,
                        trackCount = album.numberOfItems,
                        discCount = album.numberOfVolumes,
                        releaseDate = album.releaseDate,
                        artists = albumObj.artists(artists),
                        images = albumObj.images(images)
                    )
                }
            }.cacheAlbums() + getAlbumsFromCache(existing)
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return emptyList()
        }
    }

    override suspend fun getArtistsByIds(artistIds: List<String>): List<Artist> {
        val filteredArtistIds = artistIds.distinct().toMutableList()
        val existing = checkExistingAlbumsFromCache(filteredArtistIds)

        filteredArtistIds.removeAll(existing)

        if (filteredArtistIds.isEmpty()) return getArtistsFromCache(existing)

        if (filteredArtistIds.size > 20) {
            return filteredArtistIds.chunked(20).flatMap { getArtistsByIds(it) }
        }

        val url = getUrl("/artists") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                appendAll("include", listOf("profileArt"))
                appendAll("filter[id]", filteredArtistIds)
            }
        }

        val response = makeRequest(url)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getArtistsByIds]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getArtistsByIds(filteredArtistIds) + getArtistsFromCache(existing)
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<ArtistsMultiRelationshipDataDocument<JsonAttribute, EmptyRelationships>>()

            val images = body.included?.mapAttributes<ArtworksAttributes>() ?: emptyMap()

            return body.data.mapNotNull { artistObj ->
                artistObj.attributes?.let { artist ->
                    Artist(
                        id = artistObj.id,
                        name = artist.name,
                        popularity = artist.popularity.toFloat(),
                        images = artistObj.images(images)
                    )
                }
            }.cacheArtists() + getArtistsFromCache(existing)
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return emptyList()
        }
    }

    override suspend fun getAlbumTracks(albumId: String): Flow<Track> = flow {
        var cursor: String? = null
        var depth = 1

        do {
            val url = getUrl("/albums/${albumId}/relationships/items") {
                parameters {
                    append("countryCode", "US")
                    append("locale", "en-US")
                    appendAll("include", listOf("items"))
                    cursor?.let { append("page[cursor]", it) }
                }
            }

            val response = makeRequest(url)
            when (response.status) {
                HttpStatusCode.OK -> {}
                HttpStatusCode.TooManyRequests -> {
                    logger.warn("[getAlbumTracks]: Too many requests, waiting ${10 * depth} seconds")
                    delay(10.seconds * depth)
                    depth++
                    continue
                }

                else -> println("error: ${response.status}")
            }

            try {
                val body = response.body<AlbumsItemsMultiRelationshipDataDocument<JsonAttribute, EmptyRelationships>>()
                val meta = body.data?.associate { it.id to it.meta }?.filterValueNotNull() ?: emptyMap()
                val tracks = body.included?.mapAttributes<TracksAttributes>() ?: emptyMap()

                cursor = body.links.meta?.nextCursor

                emitAll(tracks.entries.map { (id, track) ->
                    Track(
                        id = id,
                        title = track.title,
                        duration = track.duration,
                        createdAt = track.createdAt,
                        trackNumber = meta[id]?.trackNumber,
                        discNumber = meta[id]?.volumeNumber,
                        artists = emptyList(),
                        images = emptyList(),
                    )
                }.asFlow())

                if (cursor != null) {
                    logger.info("Fetching tracks for $albumId with cursor: $cursor")
                    delay(500.milliseconds)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println(response.bodyAsText())
            }
        } while (cursor != null)
    }

    private fun getTracksFromPlaylist(
        playlistId: String,
        user: User?,
        cursor: String? = null,
        depth: Int = 1
    ): Flow<Track> = flow {
        val url = getUrl("/playlists/$playlistId/relationships/items") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                appendAll("include", listOf("items"))
                if (cursor != null) append("page[cursor]", cursor)
            }
        }

        val response = makeRequest(url, user)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getTracksFromPlaylist]: Too many requests, waiting ${10 * depth} seconds")
                delay(10.seconds * depth)
                return@flow emitAll(getTracksFromPlaylist(playlistId, user, cursor, depth + 1))
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<PlaylistsItemsMultiRelationshipDataDocument<JsonAttribute, EmptyRelationships>>()
            val meta = body.data?.associate { it.id to it.meta }?.filterValueNotNull() ?: emptyMap()
            val tracks = body.included?.mapAttributes<TracksAttributes>() ?: emptyMap()
            val nextCursor = body.links.meta?.nextCursor

            emitAll(tracks.entries.map { (id, track) ->
                Track(
                    id = id,
                    title = track.title,
                    duration = track.duration,
                    createdAt = track.createdAt,
                    addedAt = meta[id]?.addedAt,
                    artists = emptyList(),
                    images = emptyList(),
                )
            }.asFlow())
            if (nextCursor != null) {
                logger.info("Fetching tracks for $playlistId with cursor: $nextCursor")
                delay(500.milliseconds)
                emitAll(getTracksFromPlaylist(playlistId, user, nextCursor))
            }

            return@flow
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return@flow
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?
    ): Flow<FlowPlaylist> = flow {
        val filteredPlaylistIds = playlistIds.distinct().toMutableList()
        val existing = if (!includeTracks) checkExistingPlaylistsFromCache(filteredPlaylistIds) else emptyList()

        filteredPlaylistIds.removeAll(existing)

        if (filteredPlaylistIds.size > 20) {
            for (chunk in filteredPlaylistIds.chunked(20)) {
                emitAll(getPlaylistsByIds(chunk, includeTracks, user))
            }
            return@flow
        }

        emitAll(getPlaylistsFromCache(existing).toFlow())
        if (filteredPlaylistIds.isEmpty()) return@flow

        val url = getUrl("/playlists") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                appendAll("include", mutableListOf("coverArt"))
                appendAll("filter[id]", filteredPlaylistIds)
            }
        }

        val response = makeRequest(url, user)
        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getPlaylistsByIds]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return@flow emitAll(
                    getPlaylistsByIds(
                        filteredPlaylistIds,
                        includeTracks,
                        user
                    )
                )
            }

            else -> println("error: ${response.status}")
        }

        try {
            val body = response.body<PlaylistsMultiRelationshipDataDocument<JsonAttribute, EmptyRelationships>>()

            val images = body.included?.mapAttributes<ArtworksAttributes>() ?: emptyMap()
            val tracks =
                if (includeTracks) body.data.map { it.id }
                    .associateWith { getTracksFromPlaylist(it, user) } else emptyMap()

            return@flow emitAll(body.data.mapNotNull { playlistObj ->
                playlistObj.attributes?.let { playlist ->
                    FlowPlaylist(
                        id = playlistObj.id,
                        name = playlist.name,
                        description = playlist.description ?: "",
                        trackCount = playlist.numberOfItems ?: 0,
                        tracks = tracks[playlistObj.id] ?: emptyFlow(),
                        images = playlistObj.images(images)
                    )
                }
            }.asFlow().let {
                if (!includeTracks) it.cachePlaylists() else it
            })
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())

            return@flow
        }
    }
}