package dev.dertyp.routing

import dev.dertyp.core.*
import dev.dertyp.services.tdn.*
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import org.koin.ktor.ext.inject
import java.io.File
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.min
import kotlin.time.ExperimentalTime

@Serializable
data class DlBody(
    val urls: List<String> = emptyList(),
)

@OptIn(ExperimentalAtomicApi::class, ExperimentalTime::class)
fun Route.tdn() {
    route("/tdn", {
        tags("tdn")
    }) {
        get("/authenticated") {
            val tdnService by inject<TdnService>()

            val homeDir = System.getProperty("user.home")
            val tdnTokenJson = File(homeDir, ".config/tidal_dl_ng/token.json")

            call.respond(tdnTokenJson.exists() && tdnService.authorized())
        }
        post("/login", {}) {
            val tdnService by inject<TdnService>()

            if (!tdnService.isDownloadActive.compareAndSet(expectedValue = false, newValue = true)) {
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
                tdnService.login({ isClientConnected() }) {
                    sendSafe(it)
                }

                tdnService.isDownloadActive.store(false)
            }
        }

        get("/dlQueue", {
            request {
                queryParameter<Boolean>("self") {
                    description = "If true, returns only queued downloads by the user that initiated this call."
                }
            }
        }) {
            val service by inject<DownloadService>()
            val user = call.getUser()

            val self = call.parameters["self"].toBoolean()

            if (user == null && self) return@get call.respond(HttpStatusCode.BadRequest, "No user found.")

            call.respond(service.downloadQueue(if (self) user else null).map {
                when (it) {
                    is UrlDownloadQueueEntry -> ApplicationScope.json.encodeToJsonElement(it)
                    is FavouriteDownloadQueueEntry -> ApplicationScope.json.encodeToJsonElement(it)
                }
            })
        }

        post("/dl", {
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
            val service by inject<DownloadService>()
            val tdnService by inject<TdnService>()

            if (!tdnService.authorized()) return@post call.respond(HttpStatusCode.Unauthorized)

            val bodyUrls = call.receive<DlBody>().urls
            val pathUrls = call.parameters.getAll("url") ?: emptyList()

            val urls = (pathUrls + bodyUrls).mapNotNull {
                try {
                    Url(it.removeSuffix("/u"))
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
                sendSafe("Sending urls to DownloadService")

                val (contentToDownload) = service.downloadTidalIds(
                    call,
                    urls.map { it.segments.last { s -> s != "u" } }.asFlow()
                )
                for ((_, urls) in urls.groupBy { it.segments.first() }) {
                    for (chunk in urls.map { it.toString().removeSuffix("/u") }.chunked(250)) {
                        service.addToQueue(
                            UrlDownloadQueueEntry(
                                urls = chunk.toMutableList(),
                                ids = chunk.map { it.split("/").last() },
                                maxRetries = min(maxRetries * urls.size, 75)
                            )
                        )
                    }
                }

                if (contentToDownload) service.waitForActive()

                coroutineScope {
                    val job = launch {
                        var lastEntry: DownloadQueueEntry? = null

                        sendSafe("Attaching to DownloadService")

                        service.logs().collect { line ->
                            val finishedSize = service.finishedDownloads().size
                            val totalSize = finishedSize + service.queueSize() + 1
                            val indexLine = "${(finishedSize + 1).zeroPad(totalSize.digitCount())}/${totalSize} "

                            if (lastEntry != line.queueEntry) lastEntry = line.queueEntry
                            else if (line.line != null) sendSafe(line.line, indexLine)
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

        post("/dl_fav/{type}", {
            request {
                pathParameter<TdnFavoriteType>("type") {
                    description = "The type of fav to download."
                }
                queryParameter<Int>("maxRetries") {
                    description = "Maximum retries to download. (defaults to 5)"
                }
            }
        }) {
            val service by inject<DownloadService>()
            val tdnService by inject<TdnService>()

            if (!tdnService.authorized()) return@post call.respond(HttpStatusCode.Unauthorized)

            val type = call.parameters["type"]?.let { TdnFavoriteType.valueOf(it) }
            if (type == null) return@post call.respond(HttpStatusCode.BadRequest)

            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header("X-Accel-Buffering", "no")

            val maxRetries = call.parameters["maxRetries"]?.toIntOrNull() ?: 5

            call.respondBytesWriter(ContentType.Text.EventStream) {
                sendSafe("Sending urls to DownloadService")

                service.addToQueue(FavouriteDownloadQueueEntry(tdnFavoriteType = type, maxRetries = maxRetries))

                service.waitForActive()

                coroutineScope {
                    val job = launch {
                        var lastEntry: DownloadQueueEntry? = null

                        sendSafe("Attaching to DownloadService")

                        service.logs().collect { line ->
                            val finishedSize = service.finishedDownloads().size
                            val totalSize = finishedSize + service.queueSize() + 1
                            val indexLine = "${(finishedSize + 1).zeroPad(totalSize.digitCount())}/${totalSize} "

                            if (lastEntry != line.queueEntry) lastEntry = line.queueEntry
                            else if (line.line != null) sendSafe(line.line, indexLine)
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