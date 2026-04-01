package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.cleanTitle
import dev.dertyp.data.*
import dev.dertyp.server.BuildConfig
import dev.dertyp.services.Service
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class MusicBrainzReleaseGroup(
    val id: String,
    val title: String,
    @SerialName("primary-type")
    val primaryType: String? = null,
    @SerialName("first-release-date")
    val firstReleaseDate: String? = null,
    val relations: List<MusicBrainzRelation>? = null
)

@Serializable
data class MusicBrainzRelation(
    val type: String? = null,
    val url: MusicBrainzRelationUrl? = null,
    @SerialName("release_group")
    val releaseGroup: MusicBrainzReleaseGroup? = null
)

@Serializable
data class MusicBrainzRelationUrl(
    val id: String,
    val resource: String
)

@Serializable
data class MusicBrainzRecording(
    val id: String,
    val title: String? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MusicBrainzArtistCredit>? = null,
    val releases: List<MusicBrainzRelease>? = null,
    val length: Long? = null
)

@Serializable
data class MusicBrainzArtistCredit(
    val name: String? = null,
    val joinphrase: String? = null,
    val artist: MusicBrainzArtist? = null
)

@Serializable
data class MusicBrainzRelease(
    val id: String,
    val title: String? = null,
    @SerialName("release-group")
    val releaseGroup: MusicBrainzReleaseGroup? = null,
    val relations: List<MusicBrainzRelation>? = null
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
        while (retries < 10) {
            try {
                val response: HttpResponse = ApiClient.queueInstance.enqueue(urlString, priority, block)
                if (response.status == HttpStatusCode.ServiceUnavailable || response.status == HttpStatusCode.TooManyRequests) {
                    logger.warn("Rate limited by MusicBrainz, retrying in 1s... ($retries/10)")
                    delay(1000)
                    retries++
                    continue
                }
                return response.body<T>()
            } catch (e: Exception) {
                logger.error("Error during MusicBrainz request: ${e.message}", e)
                delay(1000)
                retries++
            }
        }
        return null
    }

    suspend fun searchMb(song: BaseSong, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRecording? {
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
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            searchResponse?.recordings?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Failed to search MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchAlbumMb(album: Album, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzRelease? {
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
                parameter("limit", 1)
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.releases?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Error searching MusicBrainz for $query", e)
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
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }

            response?.artists?.firstOrNull()
        } catch (e: Exception) {
            logger.error("Error searching MusicBrainz for $query", e)
            null
        }
    }

    suspend fun searchArtistsMbPaged(query: String, page: Int, pageSize: Int, priority: HttpClientPriority = HttpClientPriority.NORMAL): PaginatedResponse<MusicBrainzArtist> {
        val offset = page * pageSize

        val response = try {
            retryableGet<MusicBrainzArtistSearchResponse>("$mbBaseUrl/artist", priority) {
                parameter("query", query)
                parameter("limit", pageSize)
                parameter("offset", offset)
                parameter("fmt", "json")
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

    suspend fun fetchReleaseGroups(artistMbId: String, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzReleaseGroup> {
        return try {
            val response = retryableGet<MusicBrainzReleaseGroupResponse>("$mbBaseUrl/release-group", priority) {
                parameter("artist", artistMbId)
                parameter("inc", "url-rels+release-group-rels")
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

    suspend fun fetchArtistById(mbId: String, priority: HttpClientPriority = HttpClientPriority.NORMAL): MusicBrainzArtist? {
        return try {
            retryableGet<MusicBrainzArtist>("$mbBaseUrl/artist/$mbId", priority) {
                parameter("fmt", "json")
                header("User-Agent", "Synara/${BuildConfig.VERSION} ( https://github.com/dertyp7214/synara )")
            }
        } catch (e: Exception) {
            logger.error("Error fetching artist by ID $mbId", e)
            null
        }
    }

    suspend fun fetchReleasesByArtist(artistMbId: String, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRelease> {
        return try {
            val response = retryableGet<MusicBrainzReleaseResponse>("$mbBaseUrl/release", priority) {
                parameter("artist", artistMbId)
                parameter("inc", "release-groups")
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

    suspend fun fetchReleasesByReleaseGroup(releaseGroupId: String, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRelease> {
        return try {
            val response = retryableGet<MusicBrainzReleaseResponse>("$mbBaseUrl/release", priority) {
                parameter("release-group", releaseGroupId)
                parameter("inc", "url-rels")
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

    suspend fun fetchRecordingsByReleaseGroup(releaseGroupId: String, priority: HttpClientPriority = HttpClientPriority.NORMAL): List<MusicBrainzRecording> {
        return try {
            val response = retryableGet<MusicBrainzSearchResponse>("$mbBaseUrl/recording", priority) {
                parameter("release-group", releaseGroupId)
                parameter("inc", "releases+release-groups")
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
