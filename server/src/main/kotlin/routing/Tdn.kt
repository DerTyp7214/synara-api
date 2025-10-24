package dev.dertyp.routing

import dev.dertyp.core.capitalize
import dev.dertyp.core.digitCount
import dev.dertyp.core.isClientConnected
import dev.dertyp.core.zeroPad
import dev.dertyp.services.ProcessExecutionResult
import dev.dertyp.services.TdnFavoriteType
import dev.dertyp.services.TdnService
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@Serializable
data class DlBody(
    val urls: List<String> = emptyList(),
)

@OptIn(ExperimentalAtomicApi::class)
fun Route.tdn(service: TdnService) {
    route("/tdn", {
        tags("tdn")
    }) {
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
            if (!service.isDownloadActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(
                    HttpStatusCode.Conflict,
                    "Download is already running. (If you just closed one, please wait a few seconds)"
                )
                return@post
            }

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
            if (urls.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest)

            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header("X-Accel-Buffering", "no")

            val maxRetries = call.parameters["maxRetries"]?.toIntOrNull() ?: 5

            call.respondBytesWriter(ContentType.Text.EventStream) {
                suspend fun sendSafe(msg: String, message: String = "") = try {
                    writeStringUtf8("event: $message${
                            LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).split(".").first()
                        }\ndata: $msg\n\n"
                    )
                    flush()
                } catch (_: Throwable) {
                }

                val results = mutableListOf<ProcessExecutionResult>()

                for (url in urls) {
                    if (isClosedForWrite) break
                    val indexLine = "${(urls.indexOf(url) + 1).zeroPad(urls.size.digitCount())}/${urls.size} "
                    sendSafe("Starting download of \"$url\"", indexLine)

                    val result = service.downloadContent(url.toString(), maxRetries, { isClientConnected() }) {
                        sendSafe(it,indexLine)
                    }

                    results.add(result)

                    if (result.exitCode == 0) sendSafe("Download complete ($url)", indexLine)
                    else sendSafe("Download failed: ${result.error} ($url)", indexLine)
                }

                sendSafe("${results.count { it.exitCode == 0 }}/${results.size} successful")

                service.isDownloadActive.store(false)
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
            if (!service.isDownloadActive.compareAndSet(expectedValue = false, newValue = true)) {
                call.respond(HttpStatusCode.Conflict, "Download is already running.")
                return@post
            }

            val type = call.parameters["type"]?.let { TdnFavoriteType.valueOf(it) }
            if (type == null) return@post call.respond(HttpStatusCode.BadRequest)

            call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.response.header(HttpHeaders.Connection, "keep-alive")
            call.response.header("X-Accel-Buffering", "no")

            val maxRetries = call.parameters["maxRetries"]?.toIntOrNull() ?: 5

            call.respondBytesWriter(ContentType.Text.EventStream) {
                suspend fun sendSafe(msg: String) = try {
                    writeStringUtf8(
                        "event: ${
                            LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).split(".").first()
                        }\ndata: $msg\n\n"
                    )
                    flush()
                } catch (_: Throwable) {
                }

                sendSafe("Starting download of ${type.name.capitalize()}")

                val result = service.downloadFavoriteCollection(type, maxRetries, { isClientConnected() }) {
                    sendSafe(it)
                }

                if (result.exitCode == 0) sendSafe("Download complete")
                else sendSafe("Download failed: ${result.error}")

                service.isDownloadActive.store(false)
            }
        }
    }
}