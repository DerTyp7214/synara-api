package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class DeezerService(
    environment: ApplicationEnvironment
) : MetadataService("Deezer", IMetadataService.MetadataType.deezer, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath = ""
    override val clientSecretConfigPath = ""

    private val baseUrl = "https://api.deezer.com"

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
    }

    override suspend fun searchArtists(
        query: String,
        limit: Int,
        priority: HttpClientPriority
    ): List<IMetadataService.Artist> {
        val response = ApiClient.instance.get("$baseUrl/search/artist") {
            parameter("q", query)
            parameter("limit", limit)
        }

        if (response.status != HttpStatusCode.OK) {
            logger.error("Searching artists on Deezer for $query failed with status ${response.status}")
            return emptyList()
        }

        val searchResponse = response.body<SearchResponse>()

        return searchResponse.data.map { artist ->
            IMetadataService.Artist(
                id = artist.id.toString(),
                name = artist.name,
                popularity = 0f,
                url = artist.link,
                images = listOfNotNull(
                    IMetadataService.Image(artist.pictureSmall, 56, 56),
                    IMetadataService.Image(artist.pictureMedium, 250, 250),
                    IMetadataService.Image(artist.pictureBig, 500, 500),
                    IMetadataService.Image(artist.pictureXl, 1000, 1000)
                )
            )
        }
    }

    @Serializable
    data class SearchResponse(
        val data: List<Artist>
    )

    @Serializable
    data class Artist(
        val id: Long,
        val name: String,
        val link: String,
        val picture: String,
        @SerialName("picture_small") val pictureSmall: String,
        @SerialName("picture_medium") val pictureMedium: String,
        @SerialName("picture_big") val pictureBig: String,
        @SerialName("picture_xl") val pictureXl: String,
        @SerialName("nb_album") val nbAlbum: Int? = null,
        @SerialName("nb_fan") val nbFan: Int? = null,
        val radio: Boolean? = null,
        val tracklist: String? = null,
        val type: String
    )
}
