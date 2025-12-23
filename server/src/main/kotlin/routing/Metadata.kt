package dev.dertyp.routing

import com.ucasoft.ktor.simpleCache.cacheOutput
import dev.dertyp.ApiClient
import dev.dertyp.Indexer
import dev.dertyp.core.*
import dev.dertyp.data.InsertableImage
import dev.dertyp.db.ArtistTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.metadata.TidalService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.head
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.ktor.ext.inject
import java.util.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

@Serializable
data class AnimatedCover(
    val url: String?,
    val fallbackUrl: String,
    val animated: Boolean
)

@OptIn(ExperimentalAtomicApi::class)
fun Route.metadata() {
    route("/metadata/{metadataProvider}", {
        request {
            pathParameter<MetadataService.Companion.MetadataType>("metadataProvider")
        }
    }) {
        get("/supported") {
            val service = call.getMetadataProvider() ?: return@get call.respond(HttpStatusCode.BadRequest)

            call.respond(service.supported())
        }
        cacheOutput(1.days) {
            get("/imageUrlById/{imageId}", {
                request {
                    pathParameter<UUID>("imageId")
                }
            }) {
                val imageId =
                    call.parameters["imageId"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

                val metadataProviderString =
                    call.parameters["metadataProvider"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

                val service = MetadataService.getMetadataService(metadataProvider, environment)

                val imageUrl = service.getImageUrlByImageId(imageId) ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(imageUrl)
            }
        }
        route("/fetchArtistImages", HttpMethod.Get) {
            sse {
                val indexer by inject<Indexer>()
                val imageService by inject<ImageService>()

                if (indexer.isActive.load()) {
                    call.respond(HttpStatusCode.Conflict, "Index is running")
                    return@sse
                }

                val metadataProviderString =
                    call.parameters["metadataProvider"] ?: return@sse call.respond(HttpStatusCode.BadRequest)

                val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

                val service = MetadataService.getMetadataService(metadataProvider, environment)

                if (!MetadataService.isFetching.compareAndSet(expectedValue = false, newValue = true)) {
                    call.respond(HttpStatusCode.Conflict, "Fetching is already in progress.")
                    return@sse
                }

                val artists = dbQuery {
                    ArtistTable
                        .select(ArtistTable.id, ArtistTable.name, ArtistTable.image)
                        .where { ArtistTable.image.isNull() }
                        .map { Pair(it[ArtistTable.id].value, it[ArtistTable.name]) }
                }

                val artistChannel = Channel<Pair<UUID, String>>(Channel.UNLIMITED)

                try {
                    coroutineScope {
                        repeat(1) {
                            launch {
                                for ((id, name) in artistChannel) {
                                    send("Fetching image for: $name")
                                    val response = service.searchArtists(name, 20)
                                    val artist = response.sortedByDescending { it.popularity }.firstOrNull { artist ->
                                        artist.name.replace(".", "")
                                            .equals(name.replace(".", ""), ignoreCase = true)
                                    }
                                    if (artist == null) {
                                        send("No artist with name \"$name\" ${response.joinToString(", ") { it.name }}")
                                        continue
                                    }

                                    val images = artist.images
                                    val image = images.maxByOrNull { it.width }
                                    if (image == null) {
                                        send("No image for \"$name\" $artist ${images.joinToString(", ")}")
                                        continue
                                    }

                                    val imageBytes = ApiClient.instance.safeGet<ByteArray>(image.url)
                                    if (imageBytes == null) {
                                        send("No image (null) for \"$name\"")
                                        continue
                                    }

                                    val imageId = imageService.createBatch(
                                        listOf(
                                            InsertableImage(
                                                data = imageBytes,
                                                imageHash = imageBytes.sha256(),
                                                origin = image.url
                                            )
                                        )
                                    ).firstOrNull()
                                    if (imageId == null) {
                                        send("Error inserting image for \"$name\": ${image.url} (${imageBytes.sha256()})")
                                        continue
                                    }

                                    val updates = dbQuery {
                                        ArtistTable.update({ ArtistTable.id eq id }) {
                                            it[ArtistTable.image] = imageId
                                        }
                                    }

                                    if (updates == 1) send("Updated \"$name\" with an image.")
                                    else send("Something went wrong. $name")
                                }
                            }
                        }
                        for (artist in artists) {
                            artistChannel.send(artist)
                            ensureActive()
                        }

                        artistChannel.close()
                    }

                    send("Loading artist images done.")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ClosedWriteChannelException) {
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    MetadataService.isFetching.store(false)
                }
            }
        }
        post("/tracks", {
            request {
                body<List<String>> {
                    description = "Track ids"
                }
            }
        }) {
            val service = call.getMetadataProvider() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (service !is TidalService) return@post call.respond(
                HttpStatusCode.MethodNotAllowed,
                "Only Tidal is supported."
            )

            val ids = call.receive<List<String>>().filterNot { it.isBlank() }

            call.respond(service.getTracksByIds(ids))
        }
        post("/albums", {
            request {
                body<List<String>> {
                    description = "Album ids"
                }
            }
        }) {
            val service = call.getMetadataProvider() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (service !is TidalService) return@post call.respond(
                HttpStatusCode.MethodNotAllowed,
                "Only Tidal is supported."
            )

            val ids = call.receive<List<String>>().filterNot { it.isBlank() }

            call.respond(service.getAlbumsByIds(ids))
        }
        post("/artists", {
            request {
                body<List<String>> {
                    description = "Artist ids"
                }
            }
        }) {
            val service = call.getMetadataProvider() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (service !is TidalService) return@post call.respond(
                HttpStatusCode.MethodNotAllowed,
                "Only Tidal is supported."
            )

            val ids = call.receive<List<String>>().filterNot { it.isBlank() }

            call.respond(service.getArtistsByIds(ids))
        }
        post("/playlists", {
            request {
                queryParameter<Boolean>("includeTracks") {
                    description = "Include tracks in response"
                }
                body<List<String>> {
                    description = "Playlist ids"
                }
            }
        }) {
            val service = call.getMetadataProvider() ?: return@post call.respond(HttpStatusCode.BadRequest)
            if (service !is TidalService) return@post call.respond(
                HttpStatusCode.MethodNotAllowed,
                "Only Tidal is supported."
            )

            val ids = call.receive<List<String>>().filterNot { it.isBlank() }
            val includeTracks = call.queryParameters["includeTracks"]?.toBoolean() ?: false

            call.respond(service.getPlaylistsByIds(ids, includeTracks))
        }
        head("/proxy/{url}", {
            request {
                pathParameter<String>("url") {
                    description = "The URL to proxy."
                }
            }
        }) {
            val base64 = call.parameters["url"] ?: return@head call.respond(HttpStatusCode.BadRequest)
            val url = base64.decodeBase64String()

            val response = ApiClient.instance.head(url)

            val contentType = response.headers[HttpHeaders.ContentType]
            val status = response.status
            val bytes = response.bodyAsBytes()

            call.respondBytes(
                bytes = bytes,
                contentType = contentType?.let { ContentType.parse(it) },
                status = status
            )
        }
        get("/proxy/{url}", {
            request {
                pathParameter<String>("url") {
                    description = "The URL to proxy."
                }
            }
        }) {
            val base64 = call.parameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val url = base64.decodeBase64String()

            val response = ApiClient.instance.get(url)

            val contentType = response.headers[HttpHeaders.ContentType]
            val status = response.status
            val bytes = response.bodyAsBytes()

            call.respondBytes(
                bytes = bytes,
                contentType = contentType?.let { ContentType.parse(it) },
                status = status
            )
        }

        cacheOutput(Duration.INFINITE) {
            route("/imageUrl") {
                get("/animatedByTrack/{trackId}", {
                    request {
                        pathParameter<String>("trackId") {
                            description = "The (synara) track ID."
                        }
                    }
                }) {
                    val songService by inject<SongService>()

                    val metadataProviderString =
                        call.parameters["metadataProvider"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

                    val service = MetadataService.getMetadataService(metadataProvider, environment)

                    val trackId =
                        call.parameters["trackId"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

                    val track = songService.byId(trackId) ?: return@get call.respond(HttpStatusCode.NotFound)
                    val tidalTrackId = track.originalUrl.tidalId()

                    val albumId =
                        service.getAlbumIdByTrackId(tidalTrackId) ?: return@get call.respond(HttpStatusCode.NotFound)

                    val images = service.getImageUrlByAlbumId(albumId)
                    if (images.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)

                    val animated = images.filter { it.animated }.maxByOrNull { it.height }
                    val fallback = images.filter { !it.animated }.maxByOrNull { it.height }

                    if (fallback == null) return@get call.respond(HttpStatusCode.NotFound)

                    call.respond(
                        AnimatedCover(
                            url = animated?.url,
                            fallbackUrl = fallback.url,
                            animated = animated != null
                        )
                    )
                }
                get("/byTrackId/{trackId}", {
                    request {
                        pathParameter<String>("trackId") {
                            description = "The service track ID."
                        }
                        pathParameter<Boolean>("animated") {
                            description = "Whether to include animated images. Defaults to false."
                        }
                    }
                }) {
                    val metadataProviderString =
                        call.parameters["metadataProvider"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                    val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

                    val service = MetadataService.getMetadataService(metadataProvider, environment)

                    val trackId = call.parameters["trackId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val animated = call.parameters["animated"]?.toBoolean() ?: false

                    val albumId =
                        service.getAlbumIdByTrackId(trackId) ?: return@get call.respond(HttpStatusCode.NotFound)

                    val images = service.getImageUrlByAlbumId(albumId).filter { it.animated == animated }
                    if (images.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)

                    val image = images.maxBy { it.height }

                    call.respond(image)
                }
            }
        }
    }
}