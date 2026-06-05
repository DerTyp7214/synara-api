@file:UseContextualSerialization(PlatformUUID::class)

package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.cleanTitle
import dev.dertyp.data.*
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.Service
import dev.dertyp.toPlatformUUID
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Serializable
data class MusicBrainzSearchResponse(
    val recordings: List<MusicBrainzRecording>? = null
)

@Serializable
data class MusicBrainzReleaseSearchResponse(
    val releases: List<MusicBrainzRelease>? = null
)

@Serializable
data class MusicBrainzArtistSearchResponse(
    val count: Int? = null,
    val offset: Int? = null,
    val artists: List<MusicBrainzArtist>? = null
)

@Serializable
data class MusicBrainzReleaseGroupResponse(
    @SerialName("release-group-count")
    val count: Int? = null,
    @SerialName("release-groups")
    val releaseGroups: List<MusicBrainzReleaseGroup>? = null
)

@Serializable
data class MusicBrainzReleaseResponse(
    @SerialName("release-count")
    val count: Int? = null,
    val releases: List<MusicBrainzRelease>? = null
)

class MusicBrainzService : Service() {
    private val mbBaseUrl = "https://musicbrainz.org/ws/2"

    private suspend inline fun <reified T> retryableGet(
        urlString: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
        noinline block: suspend HttpRequestBuilder.() -> Unit = {}
    ): T? {
        var retries = 0
        val maxRetries = 3
        while (retries < maxRetries) {
            try {
                val response: HttpResponse = ApiClient.queueInstance.enqueue(urlString, priority, block)
                if (response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.TooManyRequests) {
                    logger.warn("Rate limited by MusicBrainz, retrying in 1s... ($retries/$maxRetries)")
                    delay(1.seconds)
                    retries++
                    continue
                }
                return response.body<T>()
            } catch (e: Exception) {
                if (retries < maxRetries - 1) {
                    logger.warn("Error during MusicBrainz request ($urlString): ${e.message}, retrying... ($retries/$maxRetries)")
                } else {
                    logger.error("Error during MusicBrainz request after $maxRetries retries ($urlString): ${e.message}", e)
                }
                delay(10.seconds)
                retries++
            }
        }
        return null
    }

    suspend fun searchRecordingMb(
        title: String,
        artists: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): MusicBrainzRecording? {
        if (artists.isEmpty()) {
            try {
                val id = title.toPlatformUUID()
                return fetchRecordingById(id, priority)
            } catch (_: Exception) {
            }
        }
        val query = "recording:\"${title.cleanTitle()}\" AND artist:${artists.joinToString { "\"$it\"" }}"

        return try {
            val searchResponse = retryableGet<MusicBrainzSearchResponse>("$mbBaseUrl/recording", priority) {
                parameter("query", query)
                parameter("limit", 5)
                parameter("fmt", "json")
                parameter("inc", "tags+genres+releases+release-groups+media")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            searchResponse?.recordings?.firstOrNull { rec ->
                val recArtists = rec.artistCredit?.mapNotNull { it.artist?.name?.lowercase() } ?: emptyList()
                val targetArtists = artists.map { it.lowercase() }
                targetArtists.any { it in recArtists }
            }
        } catch (e: Exception) {
            logger.error("Failed to search MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchMb(song: BaseSong, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRecording? {
        if (song.isrc != null && song.isrc!!.length >= 10 && song.isrc!!.uppercase() != "ISRC") {
            try {
                val searchResponse = retryableGet<MusicBrainzSearchResponse>("$mbBaseUrl/recording", priority) {
                    parameter("query", "isrc:${song.isrc}")
                    parameter("fmt", "json")
                    parameter("inc", "tags+genres+releases+release-groups+media")
                    header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
                }
                searchResponse?.recordings?.firstOrNull { rec ->
                    val recArtists = rec.artistCredit?.mapNotNull { it.artist?.name?.lowercase() } ?: emptyList()
                    val targetArtists = song.artists.map { it.name.lowercase() }
                    targetArtists.any { it in recArtists }
                }?.let { return it }
            } catch (e: Exception) {
                logger.error("Failed to search MusicBrainz for ISRC ${song.isrc}", e)
            }
        }

        val queryParts = mutableListOf<String>()
        queryParts.add("recording:\"${song.title.cleanTitle()}\"")
        song.artists.forEach {
            if (it.musicbrainzId != null) {
                queryParts.add("arid:${it.musicbrainzId}")
            } else {
                queryParts.add("artist:\"${it.name}\"")
            }
        }

        song.album?.let { album ->
            if (album.musicbrainzId != null) {
                queryParts.add("reid:${album.musicbrainzId}")
            } else {
                if (album.name != song.title) {
                    queryParts.add("release:\"${album.name}\"")
                }

                album.artists.forEach { artist ->
                    if (artist.musicbrainzId == null) {
                        queryParts.add("artistname:\"${artist.name}\"")
                    }
                }
            }
        }

        val query = queryParts.joinToString(" AND ")

        return try {
            val searchResponse = retryableGet<MusicBrainzSearchResponse>("$mbBaseUrl/recording", priority) {
                parameter("query", query)
                parameter("limit", 1)
                parameter("fmt", "json")
                parameter("inc", "tags+genres+releases+release-groups+media")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            searchResponse?.recordings?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Failed to search MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchAlbumMb(album: Album, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRelease? {
        if (album.barcode != null && album.barcode!!.length >= 8 && album.barcode!!.uppercase() != "BARCODE") {
            try {
                val response = retryableGet<MusicBrainzReleaseSearchResponse>("$mbBaseUrl/release", priority) {
                    parameter("query", "barcode:${album.barcode}")
                    parameter("fmt", "json")
                    parameter("inc", "artist-credits+recordings+isrcs+release-groups+tags+genres+media")
                    header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
                }
                response?.releases?.firstOrNull { rel ->
                    val relArtists = rel.artistCredit?.mapNotNull { it.artist?.name?.lowercase() } ?: emptyList()
                    val targetArtists = album.artists.map { it.name.lowercase() }
                    targetArtists.any { it in relArtists }
                }?.let { return it }
            } catch (e: Exception) {
                logger.error("Failed to search MusicBrainz for barcode ${album.barcode}", e)
            }
        }

        val queryParts = mutableListOf<String>()
        queryParts.add("release:\"${album.name}\"")
        album.artists.forEach {
            if (it.musicbrainzId != null) {
                queryParts.add("arid:${it.musicbrainzId}")
            } else {
                queryParts.add("artist:\"${it.name}\"")
            }
        }

        val query = queryParts.joinToString(" AND ")

        return try {
            val response = retryableGet<MusicBrainzReleaseSearchResponse>("$mbBaseUrl/release", priority) {
                parameter("query", query)
                parameter("limit", 5)
                parameter("fmt", "json")
                parameter("inc", "artist-credits+recordings+isrcs+release-groups+tags+genres+media")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.releases?.firstOrNull { rel ->
                val relArtists = rel.artistCredit?.mapNotNull { it.artist?.name?.lowercase() } ?: emptyList()
                val targetArtists = album.artists.map { it.name.lowercase() }
                targetArtists.any { it in relArtists }
            }
        } catch (e: Exception) {
            logger.error("Error searching MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchReleaseByBarcodeMb(
        barcode: String,
        artists: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): MusicBrainzRelease? {
        if (barcode.length < 8 || barcode.uppercase() == "BARCODE") return null

        return try {
            val response = retryableGet<MusicBrainzReleaseSearchResponse>("$mbBaseUrl/release", priority) {
                parameter("query", "barcode:$barcode")
                parameter("fmt", "json")
                parameter("inc", "artist-credits+recordings+isrcs+release-groups+tags+genres+media")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.releases?.firstOrNull { rel ->
                val relArtists = rel.artistCredit?.mapNotNull { it.artist?.name?.lowercase() } ?: emptyList()
                val targetArtists = artists.map { it.lowercase() }
                targetArtists.any { it in relArtists }
            }
        } catch (e: Exception) {
            logger.error("Failed to search MusicBrainz for barcode $barcode", e)
            null
        }
    }

    suspend fun searchArtistMb(artist: Artist, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzArtist? {
        val queryParts = mutableListOf<String>()
        queryParts.add("artist:\"${artist.name}\"")
        queryParts.add("artistaccent:\"${artist.name}\"")

        if (artist.isGroup) {
            queryParts.add("type:\"group\"")
        }

        val query = queryParts.joinToString(" AND ")

        return try {
            val response = retryableGet<MusicBrainzArtistSearchResponse>("$mbBaseUrl/artist", priority) {
                parameter("query", query)
                parameter("limit", 1)
                parameter("fmt", "json")
                parameter("inc", "tags+genres")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.artists?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Error searching MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchArtistsMbPaged(query: String, page: Int, pageSize: Int, priority: HttpClientPriority = HttpClientPriority.NORMAL): PaginatedResponse<MusicBrainzArtist> {
        try {
            val id = query.toPlatformUUID()
            val artist = fetchArtistById(id, priority)
            if (artist != null) {
                return PaginatedResponse(
                    data = if (page == 0) listOf(artist) else emptyList(),
                    total = 1,
                    page = page,
                    pageSize = pageSize,
                    hasNextPage = false
                )
            }
        } catch (_: Exception) {
        }
        val offset = page * pageSize

        val response = try {
            retryableGet<MusicBrainzArtistSearchResponse>("$mbBaseUrl/artist", priority) {
                parameter("query", query)
                parameter("limit", pageSize)
                parameter("offset", offset)
                parameter("fmt", "json")
                parameter("inc", "tags+genres")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
        } catch (e: Exception) {
            logger.error("Error searching MusicBrainz for artists: $query", e)
            null
        }

        val total = response?.count ?: 0
        val items = response?.artists ?: emptyList()

        return PaginatedResponse(
            data = items,
            total = total,
            page = page,
            pageSize = pageSize,
            hasNextPage = (offset + items.size) < total
        )
    }

    suspend fun fetchReleaseGroups(artistMbId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzReleaseGroup> {
        return try {
            val response = retryableGet<MusicBrainzReleaseGroupResponse>("$mbBaseUrl/release-group", priority) {
                parameter("artist", artistMbId.toString())
                parameter("inc", "url-rels+release-group-rels+tags+genres")
                parameter("limit", 100)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
            response?.releaseGroups ?: emptyList()
        } catch (e: Exception) {
            logger.error("Error fetching release groups for artist $artistMbId", e)
            emptyList()
        }
    }

    suspend fun fetchArtistById(mbId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzArtist? {
        return try {
            retryableGet<MusicBrainzArtist>("$mbBaseUrl/artist/$mbId", priority) {
                parameter("inc", "tags+genres+aliases")
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
        } catch (e: Exception) {
            logger.error("Error fetching artist by ID $mbId", e)
            null
        }
    }

    suspend fun fetchReleaseGroupById(mbId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzReleaseGroup? {
        return try {
            retryableGet<MusicBrainzReleaseGroup>("$mbBaseUrl/release-group/$mbId", priority) {
                parameter("inc", "tags+genres+url-rels")
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
        } catch (e: Exception) {
            logger.error("Error fetching release group by ID $mbId", e)
            null
        }
    }

    suspend fun fetchReleasesByArtist(artistMbId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRelease> {
        return try {
            val response = retryableGet<MusicBrainzReleaseResponse>("$mbBaseUrl/release", priority) {
                parameter("artist", artistMbId.toString())
                parameter("inc", "artist-credits+recordings+isrcs+release-groups+tags+genres+media")
                parameter("limit", 100)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
            response?.releases ?: emptyList()
        } catch (e: Exception) {
            logger.error("Error fetching releases for artist $artistMbId", e)
            emptyList()
        }
    }

    suspend fun fetchReleasesByReleaseGroup(releaseGroupId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRelease> {
        return try {
            val response = retryableGet<MusicBrainzReleaseResponse>("$mbBaseUrl/release", priority) {
                parameter("release-group", releaseGroupId.toString())
                parameter("inc", "artist-credits+recordings+isrcs+url-rels+tags+genres+media")
                parameter("limit", 100)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
            response?.releases ?: emptyList()
        } catch (e: Exception) {
            logger.error("Error fetching releases for release group $releaseGroupId", e)
            emptyList()
        }
    }

    suspend fun searchReleaseMb(
        title: String,
        artists: List<String>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): MusicBrainzRelease? {
        if (artists.isEmpty()) {
            try {
                val id = title.toPlatformUUID()
                return fetchReleaseById(id, priority)
            } catch (_: Exception) {
            }
        }
        val query = "release:\"${title.cleanTitle()}\" AND artist:${artists.joinToString { "\"$it\"" }}"

        return try {
            val response = retryableGet<MusicBrainzReleaseSearchResponse>("$mbBaseUrl/release", priority) {
                parameter("query", query)
                parameter("limit", 5)
                parameter("fmt", "json")
                parameter("inc", "artist-credits+recordings+isrcs+release-groups+tags+genres+media")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.releases?.firstOrNull { rel ->
                val relArtists = rel.artistCredit?.mapNotNull { it.artist?.name?.lowercase() } ?: emptyList()
                val targetArtists = artists.map { it.lowercase() }
                targetArtists.any { it in relArtists }
            }
        } catch (e: Exception) {
            logger.error("Failed to search MusicBrainz for release $query", e)
            null
        }
    }

    suspend fun fetchRecordingById(mbId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRecording? {
        return try {
            retryableGet<MusicBrainzRecording>("$mbBaseUrl/recording/$mbId", priority) {
                parameter("inc", "artist-credits+releases+tags+genres+url-rels+isrcs")
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
        } catch (e: Exception) {
            logger.error("Error fetching recording by ID $mbId", e)
            null
        }
    }

    suspend fun fetchRecordingsMetadataLB(
        mbIds: List<PlatformUUID>,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<MusicBrainzRecording> {
        if (mbIds.isEmpty()) return emptyList()

        return mbIds.chunked(50).flatMap { chunk ->
            try {
                val mbidsString = chunk.joinToString(",")
                val response = retryableGet<JsonObject>("https://api.listenbrainz.org/1/metadata/recording/", priority) {
                    parameter("recording_mbids", mbidsString)
                    parameter("inc", "artist release tag")
                } ?: return@flatMap emptyList()

                response.mapNotNull { (mbid, data) ->
                    try {
                        val dataObj = data as? JsonObject ?: return@mapNotNull null
                        mapLBToMusicBrainzRecording(UUID.fromString(mbid), dataObj)
                    } catch (e: Exception) {
                        logger.error("Failed to map LB metadata for $mbid", e)
                        null
                    }
                }
            } catch (e: Exception) {
                logger.error("Error fetching recordings from ListenBrainz", e)
                emptyList()
            }
        }
    }

    private fun mapLBToMusicBrainzRecording(mbId: UUID, data: JsonObject): MusicBrainzRecording? {
        val rec = data["recording"] as? JsonObject ?: return null
        val tagData = (data["tag"] as? JsonObject)?.get("recording") as? JsonArray
        val artistData = data["artist"] as? JsonObject
        val releaseData = data["release"] as? JsonObject

        val title = rec["name"]?.jsonPrimitive?.contentOrNull
        val length = rec["length"]?.jsonPrimitive?.longOrNull
        val isrcs = (rec["isrcs"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }

        val artistCredits = (artistData?.get("artists") as? JsonArray)?.mapNotNull { artistElement ->
            val art = artistElement as? JsonObject ?: return@mapNotNull null
            val artistId = art["artist_mbid"]?.jsonPrimitive?.contentOrNull?.let { UUID.fromString(it) } ?: return@mapNotNull null
            MusicBrainzArtistCredit(
                name = art["name"]?.jsonPrimitive?.contentOrNull,
                artist = MusicBrainzArtist(
                    id = artistId,
                    name = art["name"]?.jsonPrimitive?.contentOrNull,
                    type = art["type"]?.jsonPrimitive?.contentOrNull?.let { type ->
                        ArtistType.entries.find { it.name.equals(type, ignoreCase = true) }
                    },
                    country = art["area"]?.jsonPrimitive?.contentOrNull
                )
            )
        }

        val releases = releaseData?.let { rel ->
            val relId = rel["mbid"]?.jsonPrimitive?.contentOrNull?.let { UUID.fromString(it) }
            if (relId != null) {
                listOf(
                    MusicBrainzRelease(
                        id = relId,
                        title = rel["name"]?.jsonPrimitive?.contentOrNull,
                        date = rel["year"]?.jsonPrimitive?.contentOrNull,
                        releaseGroup = rel["release_group_mbid"]?.jsonPrimitive?.contentOrNull?.let {
                            MusicBrainzReleaseGroup(id = UUID.fromString(it), title = "")
                        }
                    )
                )
            } else null
        }

        val tags = tagData?.mapNotNull { tagElement ->
            try {
                val tagObj = tagElement as? JsonObject ?: return@mapNotNull null
                MusicBrainzTag(
                    name = tagObj["tag"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                    count = tagObj["count"]?.jsonPrimitive?.int ?: 0
                )
            } catch (_: Exception) {
                null
            }
        }

        val lbRels = rec["rels"] as? JsonArray
        val relations = lbRels?.mapNotNull { relElement ->
            val rel = relElement as? JsonObject ?: return@mapNotNull null
            val target = rel["target"]?.jsonPrimitive?.contentOrNull
                ?: rel["artist_mbid"]?.jsonPrimitive?.contentOrNull?.let { "https://musicbrainz.org/artist/$it" }
                ?: return@mapNotNull null
            val type = rel["type"]?.jsonPrimitive?.contentOrNull
            MusicBrainzRelation(
                type = type,
                url = MusicBrainzRelationUrl(id = UUID.randomUUID(), resource = target)
            )
        }

        return MusicBrainzRecording(
            id = mbId,
            title = title,
            length = length,
            isrcs = isrcs,
            artistCredit = artistCredits,
            releases = releases,
            tags = tags,
            relations = relations,
            fetchedAt = Clock.System.now().toEpochMilliseconds()
        )
    }

    suspend fun fetchReleaseById(mbId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRelease? {
        return try {
            retryableGet<MusicBrainzRelease>("$mbBaseUrl/release/$mbId", priority) {
                parameter("inc", "artist-credits+recordings+isrcs+release-groups+tags+genres+media+url-rels")
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
        } catch (e: Exception) {
            logger.error("Error fetching release by ID $mbId", e)
            null
        }
    }

    suspend fun fetchRecordingsByReleaseGroup(releaseGroupId: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRecording> {
        return try {
            val response = retryableGet<MusicBrainzSearchResponse>("$mbBaseUrl/recording", priority) {
                parameter("release-group", releaseGroupId.toString())
                parameter("inc", "releases+release-groups+tags+genres")
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
            response?.recordings ?: emptyList()
        } catch (e: Exception) {
            logger.error("Error fetching recordings for release group $releaseGroupId", e)
            emptyList()
        }
    }
}

class CachedMusicBrainzService(
    private val musicBrainzService: MusicBrainzService,
    private val musicBrainzCacheService: MusicBrainzCacheService
) : IMusicBrainzService {
    override suspend fun getArtist(id: PlatformUUID) = getArtist(id, HttpClientPriority.HIGH)

    suspend fun getArtist(id: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzArtist? {
        val cached = musicBrainzCacheService.getArtist(id)
        if (cached != null && cached.fetchedAt != 0L) return cached
        return musicBrainzService.fetchArtistById(id, priority)?.also {
            musicBrainzCacheService.updateArtistCache(it)
        } ?: cached
    }

    override suspend fun getRecording(id: PlatformUUID) = getRecording(id, HttpClientPriority.HIGH)

    suspend fun getRecording(id: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRecording? {
        val cached = musicBrainzCacheService.getRecording(id)
        if (cached != null && cached.fetchedAt != 0L) return cached
        return musicBrainzService.fetchRecordingById(id, priority)?.also {
            musicBrainzCacheService.updateRecordingCache(it)
        } ?: cached
    }

    override suspend fun getRelease(id: PlatformUUID) = getRelease(id, HttpClientPriority.HIGH)

    suspend fun getRelease(id: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRelease? {
        val cached = musicBrainzCacheService.getRelease(id)
        if (cached != null && cached.fetchedAt != 0L && cached.media?.isNotEmpty() == true) {
            val hasTracks = cached.media!!.firstOrNull()?.tracks?.isNotEmpty() == true
            if (hasTracks) return cached
        }
        return musicBrainzService.fetchReleaseById(id, priority)?.also {
            musicBrainzCacheService.updateReleaseCache(it)
        } ?: cached
    }

    override suspend fun getReleaseGroup(id: PlatformUUID) = getReleaseGroup(id, HttpClientPriority.HIGH)

    suspend fun getReleaseGroup(id: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzReleaseGroup? {
        val cached = musicBrainzCacheService.getReleaseGroup(id)
        if (cached != null && cached.fetchedAt != 0L) return cached
        return musicBrainzService.fetchReleaseGroupById(id, priority)?.also {
            musicBrainzCacheService.updateReleaseGroupCache(it)
        } ?: cached
    }

    override suspend fun getReleasesByReleaseGroup(id: PlatformUUID): List<MusicBrainzRelease> = getReleasesByReleaseGroup(id, HttpClientPriority.HIGH)

    suspend fun getReleasesByReleaseGroup(id: PlatformUUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRelease> {
        val cached = musicBrainzCacheService.getReleasesByReleaseGroup(id)
        if (cached.isNotEmpty()) return cached
        return musicBrainzService.fetchReleasesByReleaseGroup(id, priority).onEach {
            musicBrainzCacheService.updateReleaseCache(it)
        }
    }

    override suspend fun searchRecording(title: String, artists: List<String>) = searchRecording(title, artists, HttpClientPriority.HIGH)

    suspend fun searchRecording(title: String, artists: List<String>, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRecording? {
        val result = musicBrainzService.searchRecordingMb(title, artists, priority) ?: return null
        return getRecording(result.id, priority)
    }

    override suspend fun searchRelease(title: String, artists: List<String>) = searchRelease(title, artists, HttpClientPriority.HIGH)

    suspend fun searchRelease(title: String, artists: List<String>, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRelease? {
        val result = musicBrainzService.searchReleaseMb(title, artists, priority) ?: return null
        return getRelease(result.id, priority)
    }

    override suspend fun searchReleaseByBarcode(barcode: String, artists: List<String>) = searchReleaseByBarcode(barcode, artists, HttpClientPriority.HIGH)

    suspend fun searchReleaseByBarcode(barcode: String, artists: List<String>, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRelease? {
        val result = musicBrainzService.searchReleaseByBarcodeMb(barcode, artists, priority) ?: return null
        return getRelease(result.id, priority)
    }
}
