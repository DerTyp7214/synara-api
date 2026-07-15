package dev.dertyp.routing

import dev.dertyp.AudioUtils
import dev.dertyp.core.apiKeyUser
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.services.RadioService
import dev.dertyp.services.SongService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koin.ktor.ext.inject
import java.io.File

private val OGG = ContentType("audio", "ogg")

fun Route.radioRouting() {
    val radioService by inject<RadioService>()
    val songService by inject<SongService>()

    route("/radio") {
        // Resume an existing session: mpv http://server/radio/<sessionId>/stream?quality=128
        get("/{sessionId}/stream") {
            val user = call.apiKeyUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val quality = call.request.queryParameters["quality"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "quality parameter required")
            val sessionId = call.parameters["sessionId"]?.toUUIDOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "invalid session id")
            val session = try {
                radioService.getSession(sessionId, user.id)
            } catch (_: Exception) {
                return@get call.respond(HttpStatusCode.NotFound, "unknown radio session")
            }
            call.streamRadio(radioService, songService, session, quality)
        }

        // One-shot session: mpv http://server/radio/stream?type=LAST_WEEK&quality=128
        get("/stream") {
            val user = call.apiKeyUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val quality = call.request.queryParameters["quality"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "quality parameter required")
            val type = call.request.queryParameters["type"]?.let {
                runCatching { RadioType.valueOf(it.uppercase()) }.getOrNull()
            } ?: RadioType.RANDOM
            val seed = parseSeed(call.request.queryParameters)
            val sessionId = radioService.createSession(user.id, type, seed)
            val session = radioService.getSession(sessionId, user.id)
            call.streamRadio(radioService, songService, session, quality)
        }
    }
}

private fun parseSeed(params: Parameters): RadioSeed? {
    val songIds = params.getAll("songId")?.mapNotNull { it.toUUIDOrNull() } ?: emptyList()
    val playlistId = params["playlistId"]?.toUUIDOrNull()
    val albumId = params["albumId"]?.toUUIDOrNull()
    val artistId = params["artistId"]?.toUUIDOrNull()
    val seed = RadioSeed(songIds, playlistId, albumId, artistId)
    return if (seed.isEmpty()) null else seed
}

private suspend fun ApplicationCall.streamRadio(
    radioService: RadioService,
    songService: SongService,
    session: RadioService.RadioSessionState,
    quality: Int,
) {
    val environment = application.environment

    suspend fun nextTranscoded(): File {
        val songId = radioService.nextSongId(session)
        val path = songService.byId(songId)?.path ?: error("song file missing for $songId")
        return AudioUtils.transcodeAudio(environment, File(path), quality, false, AudioFormat.OPUS).file
    }

    respondBytesWriter(contentType = OGG) {
        coroutineScope {
            var prefetch = async { nextTranscoded() }
            while (true) {
                val file = prefetch.await()
                prefetch = async { nextTranscoded() }
                val buffer = ByteArray(64 * 1024)
                file.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        writeFully(buffer, 0, read)
                    }
                }
                flush()
            }
        }
    }
}
