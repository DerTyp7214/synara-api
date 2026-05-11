package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.bytes
import dev.dertyp.services.ImageService
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.util.url
import org.koin.core.component.inject
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class ImageCacheService(
    environment: ApplicationEnvironment
) : MetadataService("ImageCache", IMetadataService.MetadataType.imageCache, environment) {
    override val tokenUrl = ""
    override val clientIdConfigPath: String = ""
    override val clientSecretConfigPath: String = ""

    override fun HttpRequestBuilder.getAccessTokenHeader(clientId: String, clientSecret: String) {
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

    override fun supported(): Boolean {
        return !environment.config.propertyOrNull("imageCache.url")?.getString().isNullOrBlank()
    }
}
