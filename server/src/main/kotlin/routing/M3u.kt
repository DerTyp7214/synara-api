package dev.dertyp.routing

import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.PlaylistEntry
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

fun Route.m3u(
    path: String = "/m3u/{id}",
    pathParams: RequestConfig.() -> Unit = { pathParameter<String>("id") },
    validateId: (id: UUID?) -> Boolean = { it != null },
    fetchData: suspend (id: UUID?) -> Pair<String, List<PlaylistEntry>>?
) {
    get(path, {
        request {
            queryParameter<Boolean>("onlyIds") {
                description = "If only the song ids should be inside the m3u."
            }
            queryParameter<Boolean>("extM3u") {
                description = "If the playlist should use Extended M3U."
            }
            queryParameter<Boolean>("shuffle") {
                description = "If the playlist should be shuffled."
            }
            pathParams()
        }
        response {
            HttpStatusCode.OK to {
                description = "The m3u for the id."
                body<String>()
            }
        }
    }) {
        val onlyIds = call.parameters["onlyIds"].toBoolean()
        val extM3u = call.parameters["extM3u"].toBoolean()
        val shuffle = call.parameters["shuffle"].toBoolean()

        val id = call.parameters["id"]?.toUUIDOrNull()
        if (!validateId(id)) return@get call.respond(HttpStatusCode.BadRequest)

        val playlist = fetchData(id)
        if (playlist == null) return@get call.respond(HttpStatusCode.NotFound)

        val (name, entries) = playlist

        val lines = mutableListOf<String>()

        val scheme = call.request.local.scheme
        val host = call.request.local.serverHost
        val port = call.request.local.serverPort.let { if (it == 80 || it == 443) "" else ":$it" }

        if (extM3u) lines.add("#EXTM3U")

        for (entry in entries.let { if (shuffle) it.shuffled() else it }) {
            if (extM3u) lines.add("#EXTINF:${entry.duration.milliseconds.inWholeSeconds},${entry.name}")
            if (onlyIds) lines.add(entry.id.toString())
            else lines.add("${scheme}://${host}${port}/song/stream/${entry.id}")
        }

        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Inline.withParameter("filename", "$name.m3u").toString()
        )
        call.respondBytesWriter(ContentType.parse("audio/x-mpegurl")) {
            writeFully(lines.joinToString("\n").toByteArray())
        }
    }
}