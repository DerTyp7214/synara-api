package dev.dertyp.services.metadata

import com.google.gson.annotations.SerializedName
import dev.dertyp.ApiClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class TidalService(
    environment: ApplicationEnvironment
) : MetadataService("Tidal", environment) {
    override val tokenUrl = "https://auth.tidal.com/v1/oauth2/token"
    override val clientIdConfigPath: String = "tidal.clientId"
    override val clientSecretConfigPath: String = "tidal.clientSecret"

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
        header(HttpHeaders.Authorization, "Basic ${Base64.encode("$clientId:$clientSecret".toByteArray())}")
        header("grant_type", "client_credentials")
    }

    private val baseUrl = URLBuilder().apply {
        protocol = URLProtocol.HTTPS
        host = "openapi.tidal.com"
        encodedPath = "v2"
    }

    override suspend fun searchArtists(query: String, limit: Int): List<Artist> {
        val url = baseUrl.clone().apply {
            appendPathSegments("searchResults")

            encodedPath += "/" + query.encodeURLParameter()

            parameters.apply {
                append("include", "artists")
                append("countryCode", "US")
                append("explicitFilter", "include, exclude")
            }
        }.build()

        val response =
            ApiClient.instance.get(url) {
                val token = getAccessToken()
                header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
                header(HttpHeaders.Accept, "application/vnd.api+json")
            }

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(30.seconds)
            return searchArtists(query, limit)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.info("Searching artists for $query: $url")
            logger.info(response.bodyAsText())
        }

        val searchResponse = response.body<Response<Response.Included.SearchAttributes>>()
        return searchResponse.included.map { included ->
            Artist(
                id = included.id,
                name = included.attributes.name,
                popularity = included.attributes.popularity,
                url = included.attributes.externalLinks.firstOrNull()?.href,
                images = {
                    getImages(
                        included.relationships[Response.Included.RelationshipType.PROFILE_ART]?.links?.self
                    ).map { file ->
                        Artist.Image(
                            url = file.href,
                            width = file.meta.width,
                            height = file.meta.height,
                        )
                    }
                }
            )
        }
    }

    private suspend fun getImages(urlPath: String?): List<Response.Included.FilesAttributes.File> {
        if (urlPath == null) return emptyList()

        val url = baseUrl.clone().apply {
            appendPathSegments(Url(urlPath).segments)

            parameters.apply {
                append("include", "profileArt")
                append("countryCode", "US")
            }
        }.build()

        val response = ApiClient.instance.get(url) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.Accept, "application/vnd.api+json")
        }

        if (response.status == HttpStatusCode.TooManyRequests) {
            delay(5.seconds)
            return getImages(urlPath)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.info("Fetching images for $url")
            logger.info(response.bodyAsText())
        }

        val imagesResponse = response.body<Response<Response.Included.FilesAttributes>>()

        return imagesResponse.included.firstOrNull()?.attributes?.files ?: emptyList()
    }

    @Serializable
    private data class Response<T>(
        val included: List<Included<T>> = emptyList()
    ) {
        @Serializable
        data class Included<T>(
            val id: String,
            val type: String,
            val attributes: T,
            val relationships: Map<RelationshipType, Relationship>,
        ) {
            @Serializable
            data class FilesAttributes(
                val mediaType: String,
                val files: List<File>
            ) {
                @Serializable
                data class File(
                    val href: String,
                    val meta: Meta
                ) {
                    @Serializable
                    data class Meta(
                        val width: Int,
                        val height: Int,
                    )
                }
            }

            @Serializable
            data class SearchAttributes(
                val name: String,
                val popularity: Float,
                val externalLinks: List<ExternalLink>
            ) {
                @Serializable
                data class ExternalLink(
                    val href: String,
                    val meta: Meta
                ) {
                    @Serializable
                    data class Meta(
                        val type: String,
                    )
                }
            }

            @Serializable
            enum class RelationshipType {
                @SerializedName("similarArtist")
                SIMILAR_ARTISTS,

                @SerializedName("albums")
                ALBUMS,

                @SerializedName("followers")
                FOLLOWERS,

                @SerializedName("following")
                FOLLOWING,

                @SerializedName("roles")
                ROLES,

                @SerializedName("videos")
                VIDEOS,

                @SerializedName("owners")
                OWNERS,

                @SerializedName("biography")
                BIOGRAPHY,

                @SerializedName("profileArt")
                PROFILE_ART,

                @SerializedName("trackProviders")
                TRACK_PROVIDERS,

                @SerializedName("tracks")
                TRACKS,

                @SerializedName("radio")
                RADIO,
            }

            @Serializable
            data class Relationship(
                val links: Links
            ) {
                @Serializable
                data class Links(
                    val self: String,
                )
            }
        }
    }
}