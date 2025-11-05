package dev.dertyp.services.sync

import dev.dertyp.ApiClient
import dev.dertyp.core.parameters
import dev.dertyp.data.User
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
class TidalSyncService(
    database: Database,
    environment: ApplicationEnvironment,
    user: User
) : TidalSyncServiceBase(database, environment, user) {
    private val apiBase = "https://openapi.tidal.com/v2"

    private var me: Me? = null

    private fun HeadersBuilder.defaultHeaders(token: Token) {
        append("Accept", "application/vnd.api+json")
        append("Authorization", "${token.tokenType} ${token.accessToken}")
    }

    private fun getUrl(path: String, block: URLBuilder.() -> Unit = {}): String {
        return url {
            takeFrom(apiBase)
            appendPathSegments(path)
            block()
        }
    }

    override suspend fun getMe(): Me {
        if (me != null) return me!!

        val url = getUrl("/users/me")
        val token = getAccessToken() ?: throw IllegalArgumentException("Invalid access token")

        val response = ApiClient.instance.get(url) {
            headers {
                defaultHeaders(token)
            }
        }

        if (response.status == HttpStatusCode.TooManyRequests) {
            logger.warn("[getMe]: Too many requests, waiting 10 seconds")
            delay(10.seconds)
            return getMe()
        }

        val body = response.body<ResponseWithInclude<TidalDataWithAttrObject<UserAttribute>, Any>>()

        me = Me(
            id = body.data.id,
            username = body.data.attributes.username,
            email = body.data.attributes.email,
        )

        return me!!
    }

    override suspend fun getLikedSongs(
        cursor: String?,
        continueRequest: suspend (List<LikedSong>) -> Boolean
    ): Flow<LikedSong> {
        val me = getMe()

        val url = getUrl("/userCollections/${me.id}/relationships/tracks") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                append("sort", "-tracks.addedAt")
                append("include", "tracks")
                if (cursor != null) append("page[cursor]", cursor)
            }
        }

        val token = getAccessToken() ?: throw IllegalArgumentException("Invalid access token")

        val response = ApiClient.instance.get(url) {
            headers {
                defaultHeaders(token)
            }
        }

        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getLikedSongs]: Too many requests, waiting 10 seconds $cursor")
                delay(10.seconds)
                return getLikedSongs(cursor)
            }

            else -> println("error: ${response.status}")
        }

        val body =
            response.body<ResponseWithInclude<List<TidalDataWithMetaObject>, TidalDataWithAttrObject<TrackAttributes>>>()

        val songMap = body.included.associateBy { i -> i.id }

        val likedSongs = body.data.map { d ->
            val date = d.meta[MetaKeys.addedAt]?.let {
                LocalDateTime.parse(it.removeSuffix("Z"))
            } ?: LocalDateTime.now()

            LikedSong(
                id = d.id,
                addedAt = Date.from(date.toInstant(ZoneOffset.UTC)) ?: Date(),
                title = songMap[d.id]?.attributes?.title ?: "",
                explicit = songMap[d.id]?.attributes?.explicit ?: false,
            )
        }.toMutableList()

        return flow {
            for (song in likedSongs) {
                emit(song)
            }

            if (body.links.meta?.contains(MetaKeys.nextCursor) == true && continueRequest(likedSongs)) {
                logger.info("Fetching with cursor: ${body.links.meta[MetaKeys.nextCursor]}")
                delay(500.milliseconds)
                emitAll(getLikedSongs(body.links.meta[MetaKeys.nextCursor]))
            }
        }
    }

    override suspend fun getAlbumIdByTrackId(trackId: String): String? {
        val url = getUrl("/tracks/${trackId}/relationships/albums") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
            }
        }

        val token = getAccessToken() ?: throw IllegalArgumentException("Invalid access token")

        val response = ApiClient.instance.get(url) {
            headers {
                defaultHeaders(token)
            }
        }

        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getAlbumIdByTrackId]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getAlbumIdByTrackId(trackId)
            }

            else -> println("error: ${response.status}")
        }

        val body = response.body<Response<List<TidalData>>>()

        return body.data.first().id
    }

    override suspend fun getImageUrlByAlbumId(albumId: String): List<Image> {
        val url = getUrl("/albums/${albumId}/relationships/coverArt") {
            parameters {
                append("countryCode", "US")
                append("locale", "en-US")
                append("include", "coverArt")
            }
        }

        val token = getAccessToken() ?: throw IllegalArgumentException("Invalid access token")

        val response = ApiClient.instance.get(url) {
            headers {
                defaultHeaders(token)
            }
        }

        when (response.status) {
            HttpStatusCode.OK -> {}
            HttpStatusCode.TooManyRequests -> {
                logger.warn("[getImageUrlByAlbumId]: Too many requests, waiting 10 seconds")
                delay(10.seconds)
                return getImageUrlByAlbumId(albumId)
            }

            else -> println("error: ${response.status}")
        }

        val body = response.body<ResponseWithInclude<List<TidalData>, TidalDataWithAttrObject<MediaAttribute>>>()

        return body.included.map { i ->
            i.attributes.files.map { f -> Image(f.href, f.meta.width, f.meta.height) }
        }.flatten()
    }

    @Serializable
    private data class Response<T>(
        val data: T,
        val links: Links,
    )

    @Serializable
    private data class ResponseWithInclude<T, I>(
        val data: T,
        val links: Links,
        val included: List<I> = emptyList(),
    )

    @Serializable
    private data class Links(
        val self: String,
        val next: String?,
        val meta: Map<MetaKeys, String>?
    )

    @Serializable
    private data class ExternalLinks<T>(
        val href: String,
        val meta: Map<MetaKeys, T>?
    )

    @Serializable
    private data class ImageFile(
        val href: String,
        val meta: ImageSize
    )

    @Serializable
    private data class ImageSize(
        val width: Int,
        val height: Int
    )

    @Serializable
    private data class TidalData(
        val id: String,
        val type: String
    )

    @Serializable
    private data class TidalDataWithMetaObject(
        val id: String,
        val type: String,
        val meta: Map<MetaKeys, String>
    )

    @Serializable
    private data class TidalDataWithAttrObject<T>(
        val id: String,
        val type: String,
        val attributes: T,
        val relationships: Map<RelationshipsKeys, Links>?
    )

    @Serializable
    private data class UserAttribute(
        val username: String,
        val country: String,
        val email: String,
        val emailVerified: Boolean,
    )

    @Serializable
    private data class MediaAttribute(
        val mediaType: String,
        val files: List<ImageFile>
    )

    @Serializable
    private data class TrackAttributes(
        val title: String,
        val version: String,
        val isrc: String,
        val duration: String,
        val copyright: Map<String, String>,
        val explicit: Boolean,
        val popularity: Float,
        val accessType: String,
        val availability: List<String>,
        val mediaTags: List<String>,
        val externalLings: List<ExternalLinks<String>>,
        val spotlighted: Boolean,
    )

    @Suppress("EnumEntryName")
    @Serializable
    enum class MetaKeys {
        addedAt,
        nextCursor,
        type
    }

    @Suppress("EnumEntryName")
    @Serializable
    enum class RelationshipsKeys {
        shares,
        albums,
        trackStatistics,
        artists,
        genres,
        similarTracks,
        owners,
        lyrics,
        sourceFile,
        providers,
        radio
    }
}