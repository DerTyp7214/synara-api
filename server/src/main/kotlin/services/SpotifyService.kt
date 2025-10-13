package dev.dertyp.services

import com.google.gson.annotations.SerializedName
import dev.dertyp.ApiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class SpotifyService(environment: ApplicationEnvironment) : Service() {
    private val clientId = environment.config.propertyOrNull("spotify.clientId")?.getString()
    private val clientSecret = environment.config.propertyOrNull("spotify.clientSecret")?.getString()

    private var accessToken: Pair<AccessTokenResponse, Long>? = null

    val isFetching = AtomicBoolean(false)

    private suspend fun getAccessToken(): AccessTokenResponse {
        if (clientId == null || clientSecret == null) throw NullPointerException("Spotify credentials are null. (spotify.clientId & spotify.clientSecret")

        if ((accessToken?.second ?: 0) > System.currentTimeMillis()) return accessToken!!.first

        val tokenResponse = ApiClient.instance.post("https://accounts.spotify.com/api/token") {
            header(HttpHeaders.ContentType, ContentType.parse("application/x-www-form-urlencoded"))
            parameter("grant_type", "client_credentials")
            parameter("client_id", clientId)
            parameter("client_secret", clientSecret)
        }.body<AccessTokenResponse>()

        accessToken = Pair(tokenResponse, System.currentTimeMillis() + tokenResponse.expiresIn)
        return tokenResponse
    }

    suspend fun searchArtists(query: String, limit: Int = 5): List<Artist> {
        val response = ApiClient.instance.get("https://api.spotify.com/v1/search") {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            parameter("q", query)
            parameter("type", "artist")
            parameter("limit", limit)
        }

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(30.seconds)
            return searchArtists(query, limit)
        }

        val searchResponse = response.body<SearchResponse>()

        return searchResponse.artists.items
    }

    @Serializable
    data class AccessTokenResponse(
        @SerializedName("access_token")
        val accessToken: String,
        @SerializedName("token_type")
        val tokenType: String,
        @SerializedName("expires_in")
        val expiresIn: Int,
    )

    @Serializable
    data class SearchResponse(
        val artists: Artists,
    )

    @Serializable
    data class Artists(
        val href: String,
        val limit: Int,
        val next: String?,
        val offset: Int,
        val previous: String?,
        val total: Int,
        val items: List<Artist>
    )

    @Serializable
    data class Artist(
        val id: String,
        val genres: List<String>,
        val href: String,
        val name: String,
        val popularity: Int,
        val uri: String,
        val images: List<Image>,
    )

    @Serializable
    data class Image(
        val url: String,
        val width: Int,
        val height: Int,
    )
}