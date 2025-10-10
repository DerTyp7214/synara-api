package dev.dertyp.routing

import dev.dertyp.data.PlaylistEntry
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlin.time.Duration.Companion.milliseconds

fun Route.m3u(
    path: String = "/m3u/{id}",
    pathParams: List<Pair<String, String>> = listOf(Pair("id", "")),
    validate: (Map<String, String?>) -> Boolean = { it.none { (_, value) -> value == null } },
    fetchData: suspend (Map<String, String?>) -> Pair<String, List<PlaylistEntry>>?
) {
    get(path, {
        tags("m3u")
        request {
            queryParameter<Boolean>("onlyIds") {
                description = "If only the song ids should be inside the m3u."
                required = false
            }
            queryParameter<Boolean>("extM3u") {
                description = "If the playlist should use Extended M3U."
                required = false
            }
            queryParameter<Boolean>("shuffle") {
                description = "If the playlist should be shuffled."
                required = false
            }
            for ((name, desc) in pathParams) {
                pathParameter<String>(name) {
                    description = desc
                }
            }
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

        val paramMap = mutableMapOf<String, String?>()

        for ((name) in pathParams) paramMap[name] = call.parameters[name]

        if (!validate(paramMap)) return@get call.respond(HttpStatusCode.BadRequest)

        val playlist = fetchData(paramMap)
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