package dev.dertyp.routing

import com.ucasoft.ktor.simpleCache.cacheOutput
import dev.dertyp.core.sized
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.services.ImageService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration.Companion.minutes

fun Routing.image(service: ImageService) {
    route("/image", {
        tags("image")
    }) {
        route("/byId/{id}", HttpMethod.Get, {
            request {
                pathParameter<String>("id") {
                    description = "The image id."
                }

                queryParameter<Int>("size") {
                    description = "The size of the image. (default: 1280)"
                    required = false
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The image with the id."
                    body<ByteArray>()
                }
            }
        }) {
            route({ hidden = true }) {
                cacheOutput(5.minutes) {
                    get {
                        val id = call.parameters["id"]?.toUUIDOrNull()
                        if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

                        val image = service.byId(id)
                        if (image == null) return@get call.respond(HttpStatusCode.NotFound)

                        val size = call.parameters["size"]?.toIntOrNull() ?: 1280

                        call.respondBytes(image.sized(size), ContentType.Image.JPEG)
                    }
                }
            }
        }

        route("/byHash/{hash}", HttpMethod.Get, {
            request {
                pathParameter<String>("hash") {
                    description = "The image hash."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The image with the hash."
                    body<ByteArray>()
                }
            }
        }) {
            route({ hidden = true }) {
                cacheOutput(5.minutes) {
                    get {
                        val id = call.parameters["hash"]
                        if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

                        val image = service.byHash(id)
                        if (image == null) return@get call.respond(HttpStatusCode.NotFound)

                        val size = call.parameters["size"]?.toIntOrNull() ?: 1280

                        call.respondBytes(image.sized(size), ContentType.Image.JPEG)
                    }
                }
            }
        }
    }
}