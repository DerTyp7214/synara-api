package dev.dertyp.routing

import dev.dertyp.AudioUtils
import dev.dertyp.core.apiKeyUser
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.services.RadioChannelService
import dev.dertyp.services.RadioService
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
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

private const val API_KEY_NOTE =
    "Authenticate with an API key via the `apiKey` query parameter, the `X-API-Key` header, or `Authorization: Bearer <key>`."

fun Route.radioRouting() {
    val radioService by inject<RadioService>()
    val radioChannelService by inject<RadioChannelService>()
    val songService by inject<SongService>()

    route("/radio") {
        get("/channel/{channelId}/stream", {
            tags("Radio")
            summary = "Stream a radio channel"
            description = "Infinite chained-Ogg/Opus audio stream for a curated radio channel, playable directly in mpv/VLC. $API_KEY_NOTE"
            securitySchemeNames("ApiKeyAuth")
            request {
                pathParameter<String>("channelId") { description = "The radio channel unique identifier." }
                queryParameter<Int>("quality") {
                    description = "Target Opus bitrate in kbps (e.g. 128)."
                    required = true
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "An endless audio/ogg stream of the channel's songs."
                    body<ByteArray> { mediaTypes(OGG) }
                }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key." }
                HttpStatusCode.BadRequest to { description = "Missing quality parameter or invalid channel id." }
                HttpStatusCode.NotFound to { description = "Channel does not exist, is not visible, or has no songs." }
            }
        }) {
            val user = call.apiKeyUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val quality = call.request.queryParameters["quality"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "quality parameter required")
            val channelId = call.parameters["channelId"]?.toUUIDOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "invalid channel id")
            val channel = radioChannelService.byId(channelId)?.takeIf { it.enabled || user.isAdmin }
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown radio channel")
            if (radioChannelService.randomSongs(channelId, emptySet(), 1).isEmpty()) {
                return@get call.respond(HttpStatusCode.NotFound, "channel has no songs")
            }
            val sessionId = radioService.createChannelSession(user.id, channel.discovery) { exclude, limit ->
                radioChannelService.randomSongs(channelId, exclude, limit)
            }
            val session = radioService.getSession(sessionId, user.id)
            call.streamRadio(radioService, songService, session, quality)
        }

        get("/{sessionId}/stream", {
            tags("Radio")
            summary = "Resume a radio session"
            description = "Continues an existing radio session where it left off as an infinite audio stream. $API_KEY_NOTE"
            securitySchemeNames("ApiKeyAuth")
            request {
                pathParameter<String>("sessionId") { description = "A radio session id from createRadioSession or startChannel." }
                queryParameter<Int>("quality") {
                    description = "Target Opus bitrate in kbps (e.g. 128)."
                    required = true
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "An endless audio/ogg stream continuing the session."
                    body<ByteArray> { mediaTypes(OGG) }
                }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key." }
                HttpStatusCode.BadRequest to { description = "Missing quality parameter or invalid session id." }
                HttpStatusCode.NotFound to { description = "The session does not exist or belongs to another user." }
            }
        }) {
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

        get("/stream", {
            tags("Radio")
            summary = "Start a radio stream"
            description = "Creates a radio session and streams it as infinite audio. Seed with a type (random/history) or with song/playlist/album/artist ids. $API_KEY_NOTE"
            securitySchemeNames("ApiKeyAuth")
            request {
                queryParameter<Int>("quality") {
                    description = "Target Opus bitrate in kbps (e.g. 128)."
                    required = true
                }
                queryParameter<RadioType>("type") { description = "Seed strategy: RANDOM (default), LAST_WEEK, LAST_MONTH or LAST_YEAR." }
                queryParameter<String>("songId") { description = "Seed song id(s); repeatable." }
                queryParameter<String>("playlistId") { description = "Seed from a playlist's songs." }
                queryParameter<String>("albumId") { description = "Seed from an album's songs." }
                queryParameter<String>("artistId") { description = "Seed from an artist's songs." }
            }
            response {
                HttpStatusCode.OK to {
                    description = "An endless audio/ogg radio stream."
                    body<ByteArray> { mediaTypes(OGG) }
                }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key." }
                HttpStatusCode.BadRequest to { description = "Missing quality parameter." }
            }
        }) {
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
