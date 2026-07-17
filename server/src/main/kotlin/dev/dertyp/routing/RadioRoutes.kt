package dev.dertyp.routing

import dev.dertyp.AudioUtils
import dev.dertyp.core.apiKeyUser
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.plugins.ApiKeyScope
import dev.dertyp.services.RadioChannelService
import dev.dertyp.services.RadioService
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koin.ktor.ext.inject
import java.io.File
import java.nio.file.Files

private val AAC = ContentType("audio", "aac")

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
            description = "Infinite chained AAC (ADTS) audio stream with ICY metadata for a curated radio channel. $API_KEY_NOTE"
            securitySchemeNames("ApiKeyAuth")
            request {
                pathParameter<String>("channelId") { description = "The radio channel unique identifier." }
                queryParameter<Int>("quality") {
                    description = "Target AAC bitrate in kbps (e.g. 256)."
                    required = true
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "An endless audio/aac stream of the channel's songs."
                    body<ByteArray> { mediaTypes(AAC) }
                }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key." }
                HttpStatusCode.BadRequest to { description = "Missing quality parameter or invalid channel id." }
                HttpStatusCode.NotFound to { description = "Channel does not exist, is not visible, or has no songs." }
            }
        }) {
            val user = call.apiKeyUser(ApiKeyScope.Radio) ?: return@get call.respond(HttpStatusCode.Unauthorized)
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
            call.streamRadio(radioService, songService, session, quality, channel.name)
        }

        get("/{sessionId}/stream", {
            tags("Radio")
            summary = "Resume a radio session"
            description = "Continues an existing radio session where it left off as an infinite audio stream. $API_KEY_NOTE"
            securitySchemeNames("ApiKeyAuth")
            request {
                pathParameter<String>("sessionId") { description = "A radio session id from createRadioSession or startChannel." }
                queryParameter<Int>("quality") {
                    description = "Target AAC bitrate in kbps (e.g. 256)."
                    required = true
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "An endless audio/aac stream continuing the session."
                    body<ByteArray> { mediaTypes(AAC) }
                }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key." }
                HttpStatusCode.BadRequest to { description = "Missing quality parameter or invalid session id." }
                HttpStatusCode.NotFound to { description = "The session does not exist or belongs to another user." }
            }
        }) {
            val user = call.apiKeyUser(ApiKeyScope.Radio) ?: return@get call.respond(HttpStatusCode.Unauthorized)
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
                    description = "Target AAC bitrate in kbps (e.g. 256)."
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
                    description = "An endless audio/aac radio stream."
                    body<ByteArray> { mediaTypes(AAC) }
                }
                HttpStatusCode.Unauthorized to { description = "Missing or invalid API key." }
                HttpStatusCode.BadRequest to { description = "Missing quality parameter." }
            }
        }) {
            val user = call.apiKeyUser(ApiKeyScope.Radio) ?: return@get call.respond(HttpStatusCode.Unauthorized)
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

private const val ICY_META_INTERVAL = 16000

internal suspend fun ApplicationCall.streamRadio(
    radioService: RadioService,
    songService: SongService,
    session: RadioService.RadioSessionState,
    quality: Int,
    stationName: String = "Synara Radio",
) {
    val environment = application.environment
    val icyEnabled = request.headers["Icy-MetaData"] == "1"
    val tempFiles = mutableListOf<File>()

    suspend fun nextTranscoded(): Pair<File, String> {
        val songId = radioService.nextSongId(session)
        val song = songService.byId(songId) ?: error("song missing for $songId")
        val m4a = AudioUtils.transcodeAudio(environment, File(song.path), quality, false, AudioFormat.AAC).file
        val adts = withContext(Dispatchers.IO) { Files.createTempFile("radio_", ".aac").toFile() }
        synchronized(tempFiles) { tempFiles.add(adts) }
        AudioUtils.remuxToAdts(m4a, adts)
        val artists = song.artists.joinToString(", ") { it.creditedName ?: it.name }
        return adts to if (artists.isEmpty()) song.title else "$artists - ${song.title}"
    }

    response.header("icy-name", stationName)
    response.header("icy-br", quality.toString())
    if (icyEnabled) {
        response.header("icy-metaint", ICY_META_INTERVAL.toString())
    }

    respondBytesWriter(contentType = AAC) {
        try {
            coroutineScope {
                var bytesUntilMeta = ICY_META_INTERVAL
                var pendingTitle: String? = null

                suspend fun writeMeta() {
                    val title = pendingTitle
                    if (title == null) {
                        writeByte(0)
                        return
                    }
                    pendingTitle = null
                    val payload = "StreamTitle='${title.replace("'", "’")}';".toByteArray()
                    val blocks = (payload.size + 15) / 16
                    writeByte(blocks.toByte())
                    writeFully(payload, 0, payload.size)
                    repeat(blocks * 16 - payload.size) { writeByte(0) }
                }

                suspend fun writeAudio(buffer: ByteArray, length: Int) {
                    if (!icyEnabled) {
                        writeFully(buffer, 0, length)
                        return
                    }
                    var offset = 0
                    while (offset < length) {
                        val chunk = minOf(length - offset, bytesUntilMeta)
                        writeFully(buffer, offset, offset + chunk)
                        offset += chunk
                        bytesUntilMeta -= chunk
                        if (bytesUntilMeta == 0) {
                            writeMeta()
                            bytesUntilMeta = ICY_META_INTERVAL
                        }
                    }
                }

                var prefetch = async { nextTranscoded() }
                while (true) {
                    val (file, title) = prefetch.await()
                    prefetch = async { nextTranscoded() }
                    pendingTitle = title
                    val buffer = ByteArray(64 * 1024)
                    file.inputStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            writeAudio(buffer, read)
                        }
                    }
                    flush()
                    file.delete()
                    synchronized(tempFiles) { tempFiles.remove(file) }
                }
            }
        } finally {
            synchronized(tempFiles) { tempFiles.toList() }.forEach { it.delete() }
        }
    }
}
