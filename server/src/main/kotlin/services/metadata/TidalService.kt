package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.parameters
import dev.dertyp.services.models.Image
import dev.dertyp.services.models.tidal.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.util.*
import kotlinx.coroutines.delay
import java.util.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalAtomicApi::class)
class TidalService(
    environment: ApplicationEnvironment
) : MetadataService("Tidal", Companion.MetadataType.tidal, environment) {
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

    private fun getUrl(path: String? = null, block: URLBuilder.() -> Unit = {}): String {
        return url {
            takeFrom(baseUrl)
            if (!path.isNullOrBlank()) appendPathSegments(path)
            block()
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
        return searchResponse.included.map { included ->
            Artist(
                id = included.id,
                name = included.attributes.name,
                popularity = included.attributes.popularity.toFloat(),
                url = included.attributes.externalLinks?.firstOrNull()?.href,
                images = {
                    getImages(
                        included.relationships.profileArt.links.self
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

    private suspend fun getImages(urlPath: String?): List<ArtworkFile> {
        if (urlPath == null) return emptyList()

        val url = getUrl {
            appendPathSegments(Url(urlPath).segments)

            parameters {
                append("include", "profileArt")
                append("countryCode", "US")
            }
        }

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

        val response = ApiClient.instance.get(url) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.Accept, "application/vnd.api+json")
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

        val response = ApiClient.instance.get(url) {
            val token = getAccessToken()
            header(HttpHeaders.Authorization, "${token.tokenType} ${token.accessToken}")
            header(HttpHeaders.Accept, "application/vnd.api+json")
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

        try {
            val body = response.body<AlbumsMultiRelationshipDataDocument<ArtworksAttributes, ArtworksRelationships>>()

            return body.included.flatMap { i ->
                i.attributes.files.map { f -> Image(f.href, f.meta.width, f.meta.height) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println(response.bodyAsText())
            return listOf()
        }
    }

    override suspend fun getImageUrlByImageId(imageId: UUID): String? {
        throw NotImplementedError("Not implemented for spotify!")
    }
}