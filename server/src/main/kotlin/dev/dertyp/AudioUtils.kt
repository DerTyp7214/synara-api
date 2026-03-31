package dev.dertyp

import dev.dertyp.AudioUtils.transcodeFlacToOpus
import dev.dertyp.core.deleteOnExitRecursive
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.SimpleSong
import dev.dertyp.db.SongTable
import dev.dertyp.db.TranscodedSongTable
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.ktor.ext.inject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

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

    private val transcodeMutexes = ConcurrentHashMap<Pair<String, Int>, Mutex>()

    internal fun closestSampleRate(rate: Int): Int {
        val supported = listOf(8000, 12000, 16000, 24000, 48000)

        return supported.minByOrNull { abs(it - rate) } ?: supported.first()
    }

    private fun getDuration(file: File): Duration {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET)
        val grabber = FFmpegFrameGrabber(file.absolutePath)
        return try {
            grabber.start()
            val duration = grabber.lengthInTime.microseconds
            grabber.stop()
            duration
        } catch (e: Throwable) {
            Duration.ZERO
        } finally {
            grabber.release()
        }
    }

    suspend fun Application.transcodeFlacToOpus(
        flacFile: File,
        targetKbps: Int
    ) = transcodeFlacToOpus(environment, flacFile, targetKbps)

    suspend fun Route.transcodeFlacToOpus(
        flacFile: File,
        targetKbps: Int
    ) = transcodeFlacToOpus(environment, flacFile, targetKbps)

    suspend fun transcodeFlacToOpus(
        environment: ApplicationEnvironment,
        flacFile: File,
        targetKbps: Int,
    ): StreamInfo = withContext(Dispatchers.IO) {
        val mutex =
            transcodeMutexes.computeIfAbsent(flacFile.absolutePath to targetKbps) { Mutex() }

        mutex.withLock {
            val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()
            val transcoderPath =
                environment.config.propertyOrNull("audio.transcode")?.getString() ?: ""

            val parent = if (tracksPath != null)
                flacFile.parentFile.absolutePath.removePrefix(tracksPath)
            else flacFile.parentFile.name

            val fileName =
                Paths.get(
                    transcoderPath,
                    "${targetKbps}kbps",
                    parent,
                    "${flacFile.nameWithoutExtension}.ogg"
                ).toFile()

            val transcodingFile = Files.createTempDirectory("transcoder_").toFile().apply {
                deleteOnExitRecursive()
            }.resolve("transcoding_$fileName")

            val tempFolder =
                if (fileName.isRooted) Paths.get("/").toFile()
                else Files.createTempDirectory("transcoder_").toFile().apply {
                    deleteOnExitRecursive()
                }
            val tempFile = tempFolder.resolve(fileName)

            if (tempFile.exists() && tempFile.length() > 0) {
                val flacDuration = getDuration(flacFile)
                val tempDuration = getDuration(tempFile)

                if (flacDuration != Duration.ZERO && tempDuration != Duration.ZERO && (flacDuration - tempDuration).absoluteValue < 1.seconds) {
                    return@withLock StreamInfo(
                        tempFile,
                        ContentType.Audio.OGG,
                        tempFile.length(),
                        tempFile.name,
                    )
                }

                logger.info("Duration mismatch for ${tempFile.name}: flac=$flacDuration, temp=$tempDuration. Re-transcoding.")
                tempFile.delete()
            }

            tempFile.parentFile.mkdirs()
            tempFile.createNewFile()

            transcodingFile.parentFile.mkdirs()
            transcodingFile.createNewFile()

            try {
                avutil.av_log_set_level(avutil.AV_LOG_ERROR)

                val grabber = FFmpegFrameGrabber(flacFile.absolutePath).apply { start() }

                if (grabber.audioChannels <= 0) {
                    val fileSize = flacFile.length()
                    throw IllegalStateException("Invalid audio channels: ${grabber.audioChannels} for file ${flacFile.absolutePath} (Size: $fileSize bytes)")
                }

                val inputMetadata: Map<String, String> = grabber.metadata.toMap()

                val recorder =
                    FFmpegFrameRecorder(transcodingFile.absolutePath, grabber.audioChannels).apply {
                        imageWidth = 0
                        imageHeight = 0
                        videoCodec = avcodec.AV_CODEC_ID_NONE

                        audioCodec = avcodec.AV_CODEC_ID_OPUS
                        format = "ogg"
                        sampleRate = closestSampleRate(grabber.sampleRate)
                        audioBitrate = targetKbps * 1000
                        sampleFormat = AV_SAMPLE_FMT_S16

                        frameRate = 1.0

                        inputMetadata.forEach { (key, value) ->
                            setMetadata(key.lowercase(), value)
                        }

                        setOption("id3v2_version", "0")
                        setMetadata("encoder", "Lavc-Ogg-Opus")

                        try {
                            start()
                        } catch (e: Exception) {
                            logger.error("FFmpeg recorder failed to start for ${transcodingFile.absolutePath}. grabber.audioChannels=${grabber.audioChannels}, grabber.sampleRate=${grabber.sampleRate}, targetKbps=$targetKbps")
                            throw e
                        }
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

                transcodingFile.copyTo(tempFile, true)

                StreamInfo(
                    tempFile,
                    ContentType.Audio.OGG,
                    tempFile.length(),
                    tempFile.name
                )
            } catch (e: Throwable) {
                tempFile.delete()
                e.printStackTrace()
                throw e
            } finally {
                transcodingFile.delete()
            }
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
        songs.forEach { (song, file, bitrate) ->
            TranscodedSongTable.insertIgnore {
                it[TranscodedSongTable.songId] = song.id
                it[TranscodedSongTable.bitrate] = bitrate
                it[TranscodedSongTable.path] = file.absolutePath
            }
        }
    }

    suspend fun insertTranscodedSong(songId: UUID, file: File, bitrate: Int) = dbQuery {
        TranscodedSongTable.insertIgnore {
            it[TranscodedSongTable.songId] = songId
            it[TranscodedSongTable.bitrate] = bitrate
            it[TranscodedSongTable.path] = file.absolutePath
        }
    }
}

private class NoOutputWithContentLength(
    override val contentType: ContentType,
    override val status: HttpStatusCode? = null,
    override val contentLength: Long? = null
) : OutgoingContent.NoContent()

@Suppress("LoggingSimilarMessage")
fun Route.stream() {
    route("/stream") {
        install(PartialContent) {
            // Maximum number of ranges that will be accepted from an HTTP request.
            // If the HTTP request specifies more ranges, they will all be merged into a single range.
            maxRangeCount = 10
        }

        head("/{id}") {
            val service by inject<SongService>()

            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@head call.respond(
                HttpStatusCode.BadRequest
            )

            val song = service.byId(id) ?: return@head call.respond(
                HttpStatusCode.NotFound,
                "Song not found."
            )

            val bitrate = call.request.queryParameters["bitrate"]?.toIntOrNull()
            val targetKbps = bitrate ?: 0

            val flacFile = Path(song.path).toFile()
            if (!flacFile.exists()) return@head call.respond(
                HttpStatusCode.NotFound,
                "File not found."
            )

            val (_, contentType, fullSize) = if (targetKbps > 0) {
                transcodeFlacToOpus(flacFile, targetKbps).also {
                    AudioUtils.insertTranscodedSong(song.id, it.file, targetKbps)
                }
            } else {
                StreamInfo(
                    flacFile,
                    ContentType.parse("application/octet-stream"),
                    flacFile.length(),
                    flacFile.name
                )
            }

            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            call.respond(
                NoOutputWithContentLength(
                    contentType = contentType,
                    status = HttpStatusCode.OK,
                    contentLength = fullSize
                )
            )
        }
        get("/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The id of the song."
                }
                queryParameter<Int>("bitrate") {
                    description =
                        "Target bitrate in kbps (e.g., 320, 192, 128). Defaults to full quality if omitted."
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
            val service by inject<SongService>()

            val id = call.parameters["id"]?.toUUIDOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val song = service.byId(id) ?: return@get call.respond(
                HttpStatusCode.NotFound,
                "Song not found."
            )

            val bitrate = call.request.queryParameters["bitrate"]?.toIntOrNull()
            val targetKbps = bitrate ?: 0

            val flacFile = Path(song.path).toFile()
            if (!flacFile.exists()) return@get call.respond(
                HttpStatusCode.NotFound,
                "File not found."
            )

            val (serveFile, _, _, fileName) = if (targetKbps > 0) {
                transcodeFlacToOpus(flacFile, targetKbps).also {
                    AudioUtils.insertTranscodedSong(song.id, it.file, targetKbps)
                }
            } else {
                StreamInfo(
                    flacFile,
                    ContentType.parse(Files.probeContentType(flacFile.toPath())),
                    flacFile.length(),
                    flacFile.name
                )
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(
                    ContentDisposition.Parameters.FileName,
                    fileName
                )
                    .toString()
            )

            return@get call.respondFile(serveFile)
        }
    }
}