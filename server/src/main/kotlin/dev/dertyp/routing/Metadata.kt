package dev.dertyp.routing

import com.ucasoft.ktor.simpleCache.cacheOutput
import dev.dertyp.ApiClient
import dev.dertyp.Indexer
import dev.dertyp.core.getMetadataProvider
import dev.dertyp.core.tidalId
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.services.LyricsSearch
import dev.dertyp.services.MetadataFetchingService
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.metadata.TidalService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.head
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.util.UUID
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
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
    route("/metadata") {
        get("/lyrics/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The id of the song."
                }
            }
        }) {
            val songService by inject<SongService>()
            val lyricsSearch by inject<LyricsSearch>()

            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.NotFound)

            val song = songService.byId(id) ?: return@get call.respond(HttpStatusCode.NotFound)

            val lyrics = lyricsSearch.searchLyrics(
                song.artists.joinToString(", ") { it.name },
                song.title
            )

            call.respond(lyrics)
        }

        route("/{metadataProvider}", {
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

                    val imageUrl =
                        service.getImageUrlByImageId(imageId) ?: return@get call.respond(HttpStatusCode.NotFound)

                    call.respond(imageUrl)
                }
            }
            route("/fetchArtistImages", HttpMethod.Get) {
                sse {
                    val indexer by inject<Indexer>()
                    val metadataFetchingService by inject<MetadataFetchingService>()

                    if (indexer.isActive.load()) {
                        call.respond(HttpStatusCode.Conflict, "Index is running")
                        return@sse
                    }

                    val metadataProviderString =
                        call.parameters["metadataProvider"] ?: return@sse call.respond(HttpStatusCode.BadRequest)

                    val metadataProvider = MetadataService.Companion.MetadataType.valueOf(metadataProviderString)

                    metadataFetchingService.fetchArtistImages(metadataProvider) {
                        send(it)
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
                val url = Base64.decode(base64).decodeToString()

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
                val url = Base64.decode(base64).decodeToString()

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
                            call.parameters["trackId"]?.toUUIDOrNull()
                                ?: return@get call.respond(HttpStatusCode.BadRequest)

                        val logger = KtorSimpleLogger("animatedImageByTrack[$trackId]")

                        logger.info("Fetching animated image for track with id $trackId")

                        val track = songService.byId(trackId) ?: return@get call.respond(HttpStatusCode.NotFound)
                        val tidalTrackId = track.originalUrl.tidalId()

                        logger.info("Fetching image for track ${track.title} with id $tidalTrackId")

                        val albumId =
                            service.getAlbumIdByTrackId(tidalTrackId)
                                ?: return@get call.respond(HttpStatusCode.NotFound)

                        logger.info("Found album id $albumId for track ${track.title}")

                        val images = service.getImageUrlByAlbumId(albumId)
                        if (images.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)

                        val animated = images.filter { it.animated }.maxByOrNull { it.height }
                        val fallback = images.filter { !it.animated }.maxByOrNull { it.height }

                        logger.info("Found ${images.size} images for album ${albumId}, animated: ${animated != null}, fallback: ${fallback != null}")

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
}