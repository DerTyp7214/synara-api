@file:OptIn(ExperimentalAtomicApi::class)

package dev.dertyp.services.subsonic

import dev.dertyp.AudioUtils
import dev.dertyp.Indexer
import dev.dertyp.data.AudioFormat
import dev.dertyp.routing.streamRadio
import dev.dertyp.services.*
import io.ktor.http.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.io.File
import java.util.*
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private fun fileContentType(path: String): ContentType = when (path.substringAfterLast('.', "").lowercase()) {
    "flac" -> ContentType("audio", "flac")
    "mp3" -> ContentType("audio", "mpeg")
    "ogg", "oga", "opus" -> ContentType("audio", "ogg")
    "m4a", "mp4", "aac" -> ContentType("audio", "mp4")
    "wav" -> ContentType("audio", "wav")
    else -> ContentType.Application.OctetStream
}

internal fun Route.subsonicMediaRoutes() {
    val authenticator by inject<SubsonicAuthenticator>()
    val queryService by inject<SubsonicQueryService>()
    val songService by inject<SongService>()
    val albumService by inject<AlbumService>()
    val artistService by inject<ArtistService>()
    val playlistService by inject<UserPlaylistService>()
    val imageService by inject<ImageService>()
    val radioService by inject<RadioService>()
    val radioChannelService by inject<RadioChannelService>()
    val indexer by inject<Indexer>()

    subAuth("stream", authenticator, {
        summary = "Stream a song"
        description = "Serves the original file (with HTTP Range support) or an Opus/AAC transcode when `maxBitRate`/`format` ask for one. Unsupported formats fall back to the original file."
        request {
            queryParameter<String>("id") { description = "Song id (`tr-<uuid>`)."; required = true }
            queryParameter<Int>("maxBitRate") { description = "Target bitrate in kbps; 0 or absent streams the original." }
            queryParameter<String>("format") { description = "raw, opus or aac (default aac when only maxBitRate is set)." }
        }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.Song
            ?: return@subAuth respondNotFound(params, "Song")
        val song = songService.byId(id.uuid, user.id)
            ?: return@subAuth respondNotFound(params, "Song")
        val file = File(song.path)
        if (!file.exists()) return@subAuth respondNotFound(params, "Song")

        val format = params["format"]?.lowercase()
        val maxBitRate = params["maxBitRate"]?.toIntOrNull() ?: 0
        val transcodeFormat = when (format) {
            "opus" -> AudioFormat.OPUS
            "aac" -> AudioFormat.AAC
            null -> if (maxBitRate > 0) AudioFormat.AAC else null
            else -> null
        }

        if (transcodeFormat == null) {
            call.respond(LocalFileContent(file, fileContentType(song.path)))
        } else {
            val bitrate = if (maxBitRate > 0) maxBitRate else 256
            val transcoded = AudioUtils.transcodeAudio(call.application.environment, file, bitrate, false, transcodeFormat)
            call.respond(LocalFileContent(transcoded.file, transcoded.contentType))
        }
    }

    subAuth("download", authenticator, {
        summary = "Download the original file of a song"
        request { queryParameter<String>("id") { description = "Song id (`tr-<uuid>`)."; required = true } }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.Song
            ?: return@subAuth respondNotFound(params, "Song")
        val song = songService.byId(id.uuid, user.id)
            ?: return@subAuth respondNotFound(params, "Song")
        val file = File(song.path)
        if (!file.exists()) return@subAuth respondNotFound(params, "Song")
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString(),
        )
        call.respond(LocalFileContent(file, fileContentType(song.path)))
    }

    subAuth("getCoverArt", authenticator, {
        summary = "Get cover art"
        request {
            queryParameter<String>("id") { description = "Image id (`im-<uuid>`) or a song/album/artist/playlist id whose art is resolved."; required = true }
            queryParameter<Int>("size") { description = "Scale to this size in pixels." }
        }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"])
            ?: return@subAuth respondNotFound(params, "Cover art")
        val imageId: UUID? = when (id) {
            is SubsonicId.Image -> id.uuid
            is SubsonicId.Song -> songService.byId(id.uuid, user.id)?.coverId
            is SubsonicId.Album -> albumService.byId(id.uuid, user.id)?.coverId
            is SubsonicId.Artist -> artistService.byId(id.uuid, user.id)?.imageId
            is SubsonicId.Playlist -> playlistService.byId(id.uuid)?.imageId
            is SubsonicId.RadioChannel -> radioChannelService.byId(id.uuid)?.imageId
        }
        val size = params["size"]?.toIntOrNull() ?: 0
        val data = imageId?.let { imageService.getImageData(it, size) }
            ?: return@subAuth respondNotFound(params, "Cover art")
        call.respondBytes(data, ContentType.Image.JPEG)
    }

    subAuth("getAvatar", authenticator, {
        summary = "Get a user avatar"
        request { queryParameter<String>("username") { description = "Only the authenticated user (or an admin) may request an avatar." } }
    }) { params, user ->
        val requested = params["username"]
        if (requested != null && !requested.equals(user.username, ignoreCase = true) && !user.isAdmin) {
            return@subAuth respondNotFound(params, "Avatar")
        }
        val data = user.profileImageId?.let { imageService.getImageData(it, 0) }
            ?: return@subAuth respondNotFound(params, "Avatar")
        call.respondBytes(data, ContentType.Image.JPEG)
    }

    subAuth("getInternetRadioStations", authenticator, {
        summary = "List internet radio stations"
        description = "Synara radio channels exposed as internet radio stations; stream URLs point at the radioStream endpoint and echo the caller's credentials."
    }) { params, user ->
        val channels = radioChannelService.list(includeDisabled = user.isAdmin)
        val origin = call.request.origin
        val portPart = when (origin.scheme) {
            "http" if origin.serverPort == 80 -> ""
            "https" if origin.serverPort == 443 -> ""
            else -> ":${origin.serverPort}"
        }
        val base = "${origin.scheme}://${origin.serverHost}$portPart/rest/radioStream"
        val authQuery = listOf("u", "t", "s", "p", "apiKey")
            .mapNotNull { key -> params[key]?.let { "$key=${it.encodeURLParameter()}" } }
            .joinToString("&")
        val stations = channels.map {
            InternetRadioStation(
                id = it.id.rcId(),
                name = it.name,
                streamUrl = "$base?id=${it.id.rcId()}&quality=256&$authQuery",
            )
        }
        call.respondSubsonic(
            SubsonicResponse(internetRadioStations = InternetRadioStations(stations)),
            params["f"], params["callback"],
        )
    }

    subAuth("radioStream", authenticator, {
        summary = "Stream a radio channel (Synara extension)"
        description = "Endless AAC (ADTS) stream with ICY metadata of a Synara radio channel, used by getInternetRadioStations stream URLs."
        request {
            queryParameter<String>("id") { description = "Radio channel id (`rc-<uuid>`)."; required = true }
            queryParameter<Int>("quality") { description = "Target AAC bitrate in kbps (default 256)." }
        }
    }) { params, user ->
        val id = SubsonicId.parse(params["id"]) as? SubsonicId.RadioChannel
            ?: return@subAuth respondNotFound(params, "Radio channel")
        val quality = (params["quality"]?.toIntOrNull() ?: 256).coerceIn(32, 320)
        val channel = radioChannelService.byId(id.uuid)?.takeIf { it.enabled || user.isAdmin }
            ?: return@subAuth respondNotFound(params, "Radio channel")
        if (radioChannelService.randomSongs(id.uuid, emptySet(), 1).isEmpty()) {
            return@subAuth respondNotFound(params, "Radio channel")
        }
        val sessionId = radioService.createChannelSession(user.id, channel.discovery) { exclude, limit ->
            radioChannelService.randomSongs(id.uuid, exclude, limit)
        }
        val session = radioService.getSession(sessionId, user.id)
        call.streamRadio(radioService, songService, session, quality, channel.name)
    }

    subAuth("getScanStatus", authenticator, {
        summary = "Get library scan status"
    }) { params, _ ->
        call.respondSubsonic(
            SubsonicResponse(scanStatus = ScanStatus(scanning = indexer.isActive.load(), count = queryService.songCount())),
            params["f"], params["callback"],
        )
    }
}
