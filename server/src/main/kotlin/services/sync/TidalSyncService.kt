package dev.dertyp.services.sync

import dev.dertyp.ApiClient
import dev.dertyp.core.parameters
import dev.dertyp.data.User
import dev.dertyp.services.models.tidal.TracksAttributes
import dev.dertyp.services.models.tidal.TracksRelationships
import dev.dertyp.services.models.tidal.UserCollectionsTracksMultiRelationshipDataDocument
import dev.dertyp.services.models.tidal.UsersSingleResourceDataDocument
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.sql.Database
import java.time.Instant
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

        val body = response.body<UsersSingleResourceDataDocument>()

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
                return getLikedSongs(cursor, continueRequest)
            }

            else -> println("error: ${response.status}")
        }

        val body =
            response.body<UserCollectionsTracksMultiRelationshipDataDocument<TracksAttributes, TracksRelationships>>()

        val songMap = body.included.associateBy { i -> i.id }

        val likedSongs = body.data.map { d ->
            val date = d.meta?.addedAt?.toInstant() ?: Instant.now()

            LikedSong(
                id = d.id,
                addedAt = Date.from(date) ?: Date(),
                title = songMap[d.id]?.attributes?.title ?: "",
                explicit = songMap[d.id]?.attributes?.explicit ?: false,
            )
        }.toMutableList()

        return flow {
            for (song in likedSongs) {
                emit(song)
            }

            if (body.links.meta?.nextCursor != null && continueRequest(likedSongs)) {
                logger.info("Fetching with cursor: ${body.links.meta.nextCursor}")
                delay(500.milliseconds)
                emitAll(getLikedSongs(body.links.meta.nextCursor, continueRequest))
            }
        }
    }
}