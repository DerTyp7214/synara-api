package dev.dertyp.routing

import com.ucasoft.ktor.simpleCache.cacheOutput
import dev.dertyp.core.respondImageSized
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.services.ImageService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Duration

fun Route.image(service: ImageService) {
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
                cacheOutput(Duration.INFINITE) {
                    get {
                        val id =
                            call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

                        val image = service.byId(id) ?: return@get call.respond(HttpStatusCode.NotFound)

                        val size = call.parameters["size"]?.toIntOrNull() ?: -1

                        call.respondImageSized(image, size)
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
                cacheOutput(Duration.INFINITE) {
                    get {
                        val id = call.parameters["hash"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                        val image = service.byHash(id) ?: return@get call.respond(HttpStatusCode.NotFound)

                        val size = call.parameters["size"]?.toIntOrNull() ?: -1

                        call.respondImageSized(image, size)
                    }
                }
            }
        }
    }
}