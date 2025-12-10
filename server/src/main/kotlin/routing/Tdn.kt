package dev.dertyp.routing

import dev.dertyp.core.digitCount
import dev.dertyp.core.isClientConnected
import dev.dertyp.core.sendSafe
import dev.dertyp.core.zeroPad
import dev.dertyp.services.tdn.*
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Serializable
data class DlBody(
    val urls: List<String> = emptyList(),
)

@OptIn(ExperimentalAtomicApi::class)
fun Route.tdn(service: DownloadService) {
    route("/tdn", {
        tags("tdn")
    }) {
        post("login", {}) {
            if (!service.tdnService.isDownloadActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(
                    HttpStatusCode.Conflict,
                    "Download is already running. (If you just closed one, please wait a few seconds)"
                )
                return@post
            }

            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header("X-Accel-Buffering", "no")

            call.respondBytesWriter(ContentType.Text.EventStream) {
                service.tdnService.login({ isClientConnected() }) {
                    sendSafe(it)
                }

                service.tdnService.isDownloadActive.store(false)
            }
        }

        post("dl", {
            request {
                queryParameter<String>("url") {
                    description = "Tidal share url to download."
                }
                queryParameter<Int>("maxRetries") {
                    description = "Maximum retries to download. (defaults to 5)"
                }
                body<DlBody>()
            }
        }) {
            if (!service.tdnService.authorized()) return@post call.respond(HttpStatusCode.Unauthorized)

            val bodyUrls = call.receive<DlBody>().urls
            val pathUrls = call.parameters.getAll("url") ?: emptyList()

            val urls = (pathUrls + bodyUrls).mapNotNull {
                try {
                    Url(it)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
            }

            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header("X-Accel-Buffering", "no")

            val maxRetries = call.parameters["maxRetries"]?.toIntOrNull() ?: 5

            call.respondBytesWriter(ContentType.Text.EventStream) {
                service.addToQueue(*urls.map {
                    UrlDownloadQueueEntry(url = it.toString(), maxRetries = maxRetries)
                }.toTypedArray())

                service.waitForActive()

                coroutineScope {
                    val job = launch {
                        var index = 0
                        var lastEntry: DownloadQueueEntry? = null

                        val queueSize = service.queueSize() + 1
                        service.logs().collect { line ->
                            val indexLine = "${index.zeroPad(queueSize.digitCount())}/${queueSize} "

                            if (lastEntry != line.queueEntry) {
                                lastEntry = line.queueEntry
                                index++
                            } else if (line.line != null) sendSafe(line.line, indexLine)
                        }
                    }
                    val checkJob = launch {
                        service.waitForInactive()

                        if (job.isActive) job.cancel()
                    }

                    job.join()
                    if (checkJob.isActive) checkJob.cancel()
                }

                val results = service.finishedDownloads().map { it.result }

                sendSafe("${results.count { it.exitCode == 0 }}/${results.size} successful")
            }
        }

        post("dl_fav/{type}", {
            request {
                pathParameter<TdnFavoriteType>("type") {
                    description = "The type of fav to download."
                }
                queryParameter<Int>("maxRetries") {
                    description = "Maximum retries to download. (defaults to 5)"
                }
            }
        }) {
            if (!service.tdnService.authorized()) return@post call.respond(HttpStatusCode.Unauthorized)

            val type = call.parameters["type"]?.let { TdnFavoriteType.valueOf(it) }
            if (type == null) return@post call.respond(HttpStatusCode.BadRequest)

            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header("X-Accel-Buffering", "no")

            val maxRetries = call.parameters["maxRetries"]?.toIntOrNull() ?: 5

            call.respondBytesWriter(ContentType.Text.EventStream) {
                service.addToQueue(FavouriteDownloadQueueEntry(tdnFavoriteType = type, maxRetries = maxRetries))

                service.waitForActive()

                coroutineScope {
                    val job = launch {
                        var index = 0
                        var lastEntry: DownloadQueueEntry? = null

                        val queueSize = service.queueSize() + 1
                        service.logs().collect { line ->
                            val indexLine = "${index.zeroPad(queueSize.digitCount())}/${queueSize} "

                            if (lastEntry != line.queueEntry) {
                                lastEntry = line.queueEntry
                                index++
                            } else if (line.line != null) sendSafe(line.line, indexLine)
                        }
                    }
                    val checkJob = launch {
                        service.waitForInactive()

                        if (job.isActive) job.cancel()
                    }

                    job.join()
                    if (checkJob.isActive) checkJob.cancel()
                }
            }
        }
    }
}