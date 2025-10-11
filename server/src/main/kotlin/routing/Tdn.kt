package dev.dertyp.routing

import dev.dertyp.core.capitalize
import dev.dertyp.services.TdnFavoriteType
import dev.dertyp.services.TdnService
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
fun Routing.tdn(service: TdnService) {
    val isDownloadActive = AtomicBoolean(false)

    route("/tdn", {
        tags("tdn")
    }) {
        route("dl", HttpMethod.Get, {
            request {
                queryParameter<String>("url") {
                    description = "Tidal share url to download."
                }
            }
        }) {
            sse {
                if (!isDownloadActive.compareAndSet(expectedValue = false, newValue = true)) {
                    call.respond(HttpStatusCode.Conflict, "Download is already running.")
                    return@sse
                }

                val url = call.parameters["url"]?.let {
                    try {
                        Url(it)
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (url == null) return@sse call.respond(HttpStatusCode.BadRequest)

                send("Starting download of \"$url\"")

                val result = service.downloadContent(url.toString()) {
                    send(it)
                }

                if (result.exitCode == 0) send("Download complete")
                else send("Download failed: ${result.error}")

                isDownloadActive.store(false)
            }
        }

        route("dl_fav/{type}", HttpMethod.Get, {
            request {
                pathParameter<TdnFavoriteType>("type") {
                    description = "The type of fav to download."
                }
            }
        }) {
            sse {
                if (!isDownloadActive.compareAndSet(expectedValue = false, newValue = true)) {
                    call.respond(HttpStatusCode.Conflict, "Download is already running.")
                    return@sse
                }

                val type = call.parameters["type"]?.let { TdnFavoriteType.valueOf(it) }
                if (type == null) return@sse call.respond(HttpStatusCode.BadRequest)

                send("Starting download of ${type.name.capitalize()}")

                val result = service.downloadFavoriteCollection(type) {
                    send(it)
                }

                if (result.exitCode == 0) send("Download complete")
                else send("Download failed: ${result.error}")

                isDownloadActive.store(false)
            }
        }
    }
}