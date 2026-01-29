package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.bytes
import dev.dertyp.data.User
import dev.dertyp.services.ImageService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.util.*
import kotlinx.coroutines.flow.Flow
import org.koin.core.context.GlobalContext
import java.util.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class ImageCacheService(
    private val environment: ApplicationEnvironment
) : MetadataService("ImageCache", Companion.MetadataType.imageCache, environment) {
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

    override suspend fun getImageUrlByImageId(imageId: UUID): String? {
        val imageService = GlobalContext.get().get<ImageService>()

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

    override suspend fun getTrackById(trackId: String): IMetadataService.Track? {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getTracksByIds(trackIds: List<String>): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun albumExistsById(albumId: String): Boolean {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getAlbumsByIds(albumIds: List<String>): List<IMetadataService.Album> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getArtistsByIds(artistIds: List<String>): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getAlbumTracks(albumId: String): Flow<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override fun getPlaylistsByIds(
        playlistIds: List<String>,
        includeTracks: Boolean,
        user: User?
    ): Flow<IMetadataService.FlowPlaylist> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override fun supported(): Boolean {
        return !environment.config.propertyOrNull("imageCache.url")?.getString().isNullOrBlank()
    }

    override suspend fun searchArtists(query: String, limit: Int): List<IMetadataService.Artist> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun search(query: String, limit: Int): List<IMetadataService.Track> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getAlbumIdByTrackId(trackId: String): String? {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getImageUrlByAlbumId(albumId: String): List<IMetadataService.Image> {
        throw NotImplementedError("Not implemented for ImageCache")
    }

    override suspend fun getImageUrlsByAlbumIds(albumIds: List<String>): Map<String, List<IMetadataService.Image>> {
        throw NotImplementedError("Not implemented for ImageCache")
    }
}