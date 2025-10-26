package dev.dertyp

import dev.dertyp.AudioUtils.transcodeFlacToWebm
import dev.dertyp.core.deleteOnExitRecursive
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.SimpleSong
import dev.dertyp.db.SongTable
import dev.dertyp.db.TranscodedSongTable
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.jetbrains.exposed.sql.batchInsert
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.min

data class StreamInfo(
    val file: File,
    val contentType: ContentType,
    val contentLength: Long,
    val fileName: String,
)

@Suppress("unused")
@OptIn(ExperimentalAtomicApi::class)
object AudioUtils {
    val logger = KtorSimpleLogger("AudioUtils")

    val isTranscoderActive = AtomicBoolean(false)

    private fun closestSampleRate(rate: Int): Int {
        val supported = listOf(8000, 12000, 16000, 24000, 48000)

        return supported.minByOrNull { abs(it - rate) } ?: supported.first()
    }

    suspend fun Application.transcodeFlacToWebm(
        flacFile: File,
        targetKbps: Int
    ) = transcodeFlacToWebm(environment, flacFile, targetKbps)

    suspend fun Route.transcodeFlacToWebm(
        flacFile: File,
        targetKbps: Int
    ) = transcodeFlacToWebm(environment, flacFile, targetKbps)

    suspend fun transcodeFlacToWebm(
        environment: ApplicationEnvironment,
        flacFile: File,
        targetKbps: Int,
    ): StreamInfo = withContext(Dispatchers.IO) {
        val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()
        val transcoderPath = environment.config.propertyOrNull("audio.transcode")?.getString() ?: ""

        val parent = if (tracksPath != null)
            flacFile.parentFile.absolutePath.removePrefix(tracksPath)
        else flacFile.parentFile.name

        val fileName =
            Paths.get(transcoderPath, "${targetKbps}kbps", parent, "${flacFile.nameWithoutExtension}.ogg").toFile()
        val tempFolder =
            if (fileName.isRooted) Paths.get("/").toFile()
            else Files.createTempDirectory("transcoder_").toFile().apply {
                deleteOnExitRecursive()
            }
        val tempFile = tempFolder.resolve(fileName)

        if (tempFile.exists()) return@withContext StreamInfo(
            tempFile,
            ContentType.Audio.MPEG,
            tempFile.length(),
            tempFile.name,
        )

        tempFile.parentFile.mkdirs()
        tempFile.createNewFile()

        try {
            avutil.av_log_set_level(avutil.AV_LOG_QUIET)

            val grabber = FFmpegFrameGrabber(flacFile.absolutePath).apply { start() }

            val inputMetadata: Map<String, String> = grabber.metadata.toMap()

            val recorder = FFmpegFrameRecorder(tempFile.absolutePath, grabber.audioChannels).apply {
                imageWidth = 0
                imageHeight = 0
                videoCodec = avcodec.AV_CODEC_ID_NONE

                audioCodec = avcodec.AV_CODEC_ID_OPUS
                format = "ogg"
                sampleRate = closestSampleRate(grabber.sampleRate)
                audioBitrate = targetKbps * 1000

                frameRate = 1.0

                inputMetadata.forEach { (key, value) ->
                    setMetadata(key.lowercase(), value)
                }

                setOption("id3v2_version", "0")
                setMetadata("encoder", "Lavc-Ogg-Opus")

                start()
            }

            var frame = grabber.grabFrame(true, false, true, false)
            while (frame != null) {
                recorder.record(frame)
                frame = grabber.grabFrame(true, false, true, false)
            }

            recorder.stop()
            recorder.release()
            grabber.stop()
            grabber.release()

            return@withContext StreamInfo(
                tempFile,
                ContentType.Audio.MPEG,
                tempFile.length(),
                tempFile.name
            )
        } catch (e: Throwable) {
            tempFile.delete()
            e.printStackTrace()
            throw e
        }
    }

    suspend fun getSongsWithTranscodingInfo(exclude: List<Int> = emptyList()) = dbQuery {
        val excludedSongIds = TranscodedSongTable
            .select(TranscodedSongTable.songId)
            .where { TranscodedSongTable.bitrate inList exclude }
            .map { it[TranscodedSongTable.songId].value }
            .distinct()

        SongTable
            .leftJoin(TranscodedSongTable)
            .select(SongTable.columns + TranscodedSongTable.columns)
            .where { SongTable.id notInList excludedSongIds }
            .map {
                SimpleSong(
                    id = it[SongTable.id].value,
                    title = it[SongTable.title],
                    duration = it[SongTable.duration],
                    explicit = it[SongTable.explicit],
                    releaseDate = getDateFromISO(it[SongTable.releaseDate]),
                    path = it[SongTable.filePath],
                    originalUrl = it[SongTable.originalUrl],
                    trackNumber = it[SongTable.trackNumber],
                    discNumber = it[SongTable.discNumber],
                    sampleRate = it[SongTable.sampleRate],
                    bitsPerSample = it[SongTable.bitsPerSample],
                    bitRate = it[SongTable.bitRate],
                    fileSize = it[SongTable.fileSize],
                    coverId = it[SongTable.cover]?.value,
                    transcodedTo = listOfNotNull(it[TranscodedSongTable.bitrate]),
                )
            }
            .groupBy { it.id }
            .map { (_, songs) ->
                songs.first().copy(
                    transcodedTo = songs.flatMap { it.transcodedTo }.distinct(),
                )
            }
    }

    suspend fun insertTranscodedSong(songs: List<Triple<SimpleSong, File, Int>>) = dbQuery {
        TranscodedSongTable.batchInsert(songs) {
            this[TranscodedSongTable.songId] = it.first.id
            this[TranscodedSongTable.bitrate] = it.third
            this[TranscodedSongTable.path] = it.second.absolutePath
        }.size
    }
}

private class NoOutputWithContentLength(
    override val contentType: ContentType,
    override val status: HttpStatusCode? = null,
    override val contentLength: Long? = null
) : OutgoingContent.NoContent() {
}

@Suppress("LoggingSimilarMessage")
fun Route.stream(service: SongService) {
    head("/stream/{id}") {
        val id = call.parameters["id"]?.toUUIDOrNull()
        if (id == null) return@head call.respond(HttpStatusCode.BadRequest)

        val song = service.byId(id)
        if (song == null) return@head call.respond(HttpStatusCode.NotFound, "Song not found.")

        val bitrate = call.request.queryParameters["bitrate"]?.toIntOrNull()
        val targetKbps = bitrate ?: 0

        val flacFile = Path(song.path).toFile()
        if (!flacFile.exists()) return@head call.respond(HttpStatusCode.NotFound, "File not found.")

        val (serveFile, contentType, fullSize, fileName) = if (targetKbps > 0) {
            transcodeFlacToWebm(flacFile, targetKbps)
        } else {
            StreamInfo(
                flacFile,
                ContentType.parse("audio/flac"),
                flacFile.length(),
                flacFile.name
            )
        }

        call.respond(NoOutputWithContentLength(
            contentType = contentType,
            status = HttpStatusCode.OK,
            contentLength = fullSize
        ))
    }
    get("/stream/{id}", {
        request {
            pathParameter<String>("id") {
                description = "The id of the song."
            }
            queryParameter<Int>("bitrate") {
                description = "Target bitrate in kbps (e.g., 320, 192, 128). Defaults to full quality if omitted."
                required = false
            }
        }
        response {
            HttpStatusCode.OK to {
                description = "Full audio of the song."
            }
            HttpStatusCode.PartialContent to {
                description = "The audio stream of the song."
            }
        }
    }) {
        val id = call.parameters["id"]?.toUUIDOrNull()
        if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

        val song = service.byId(id)
        if (song == null) return@get call.respond(HttpStatusCode.NotFound, "Song not found.")

        val bitrate = call.request.queryParameters["bitrate"]?.toIntOrNull()
        val targetKbps = bitrate ?: 0

        val flacFile = Path(song.path).toFile()
        if (!flacFile.exists()) return@get call.respond(HttpStatusCode.NotFound, "File not found.")

        val range = call.request.ranges()?.ranges?.first()

        val (serveFile, contentType, fullSize, fileName) = if (targetKbps > 0) {
            transcodeFlacToWebm(flacFile, targetKbps)
        } else {
            StreamInfo(
                flacFile,
                ContentType.parse("audio/flac"),
                flacFile.length(),
                flacFile.name
            )
        }

        when (range) {
            is ContentRange.TailFrom,
            is ContentRange.Suffix,
            is ContentRange.Bounded -> {
                val start = when (range) {
                    is ContentRange.TailFrom -> range.from.coerceIn(0 until fullSize)
                    is ContentRange.Bounded -> range.from.coerceIn(0 until fullSize)
                    is ContentRange.Suffix -> 0
                }
                val end = when (range) {
                    is ContentRange.TailFrom -> fullSize
                    is ContentRange.Bounded -> min(range.to, fullSize)
                    is ContentRange.Suffix -> min(range.lastCount, fullSize)
                }
                val chunkSize = end - start

                if (chunkSize <= 0) return@get call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)

                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                call.response.header(HttpHeaders.ContentRange, "bytes ${start}-${end}/${fullSize}")
                call.response.header(HttpHeaders.ContentLength, chunkSize.toString())
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, fileName)
                        .toString()
                )

                call.respondBytesWriter(contentType, HttpStatusCode.PartialContent) {
                    serveFile.inputStream().use { inputStream ->
                        inputStream.skip(start)
                        writeFully(inputStream.readNBytes(chunkSize.toInt()))
                    }
                }

            }

            else -> {
                call.response.header(HttpHeaders.ContentLength, fullSize)
                call.respondBytesWriter(contentType) {
                    serveFile.inputStream().transferTo(toOutputStream())
                }
            }
        }
    }
}