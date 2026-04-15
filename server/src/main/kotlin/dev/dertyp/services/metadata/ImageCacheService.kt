package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.bytes
import dev.dertyp.data.User
import dev.dertyp.services.ImageService
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.util.url
import kotlinx.coroutines.flow.Flow
import org.koin.core.component.inject
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class ImageCacheService(
    private val environment: ApplicationEnvironment
) : MetadataService("ImageCache", IMetadataService.MetadataType.imageCache, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath: String = ""
    override val clientSecretConfigPath: String = ""

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    private fun getUrl(path: String? = null, block: URLBuilder.() -> Unit = {}): String {
        return url {
            takeFrom(environment.config.property("imageCache.url").getString())
            if (!path.isNullOrBlank()) appendPathSegments(path)
            block()
        }
    }

    override suspend fun getImageUrlByImageId(imageId: UUID, priority: HttpClientPriority): String? {
        val imageService by inject<ImageService>()

        val image = imageService.byId(imageId)
        if (image == null) {
            logger.error("Image $imageId not found")
            return null
        }

        val url = getUrl(image.imageHash)
        val token = environment.config.propertyOrNull("imageCache.token")?.getString()
        if (token.isNullOrBlank()) {
            logger.error("No token found")
            return null
        }

        val imageCheck = ApiClient.instance.head(url)

        if (imageCheck.status == HttpStatusCode.NotFound) {
            val response = ApiClient.instance.put(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                }
                setBody(image.bytes())
            }

            if (!response.status.isSuccess()) {
                logger.error(response.bodyAsText())
            }
        } else {
            logger.info(imageCheck.status.toString())
        }

        return url
    }

    override suspend fun getTrackById(trackId: String, priority: HttpClientPriority): IMetadataService.Track? {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getTracksByIds(trackIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun albumExistsById(albumId: String, priority: HttpClientPriority): Boolean {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getArtistsByIds(artistIds: List<String>, priority: HttpClientPriority): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override fun getAlbumTracks(albumId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override fun getArtistTracks(artistId: String, priority: HttpClientPriority): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?,
        priority: HttpClientPriority
    ): Flow<IMetadataService.FlowPlaylist> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override fun supported(): Boolean {
        return !environment.config.propertyOrNull("imageCache.url")?.getString().isNullOrBlank()
    }

    override suspend fun searchArtists(query: String, limit: Int, priority: HttpClientPriority): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun search(query: String, limit: Int, priority: HttpClientPriority): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun searchAlbums(
        query: String,
        limit: Int,
        includeTracks: Boolean,
        priority: HttpClientPriority
    ): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getAlbumIdByTrackId(trackId: String, priority: HttpClientPriority): String? {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getImageUrlByAlbumId(albumId: String, priority: HttpClientPriority): List<IMetadataService.Image> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getImageUrlsByAlbumIds(
        albumIds: List<String>,
        priority: HttpClientPriority
    ): Map<String, List<IMetadataService.Image>> {
        throw NotImplementedError("Not implemented for ImageCache")
    }
}