package dev.dertyp

import dev.dertyp.core.deleteOnExitRecursive
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.SimpleSong
import dev.dertyp.data.TranscodedVersion
import dev.dertyp.db.SongTable
import dev.dertyp.db.TranscodedSongTable
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.routing.Route
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
import org.bytedeco.javacv.FFmpegLogCallback
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
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

    init {
        FFmpegLogCallback.set()
    }

    val isTranscoderActive = AtomicBoolean(false)

    private val transcodeMutexes = ConcurrentHashMap<Triple<String, Int, AudioFormat>, Mutex>()

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

    suspend fun Application.transcodeAudio(
        flacFile: File,
        targetKbps: Int,
        force: Boolean = true,
        audioFormat: AudioFormat = AudioFormat.OPUS
    ) = transcodeAudio(environment, flacFile, targetKbps, force, audioFormat)

    suspend fun Route.transcodeAudio(
        flacFile: File,
        targetKbps: Int,
        force: Boolean = true,
        audioFormat: AudioFormat = AudioFormat.OPUS
    ) = transcodeAudio(environment, flacFile, targetKbps, force, audioFormat)

    suspend fun transcodeAudio(
        environment: ApplicationEnvironment,
        flacFile: File,
        targetKbps: Int,
        force: Boolean = true,
        audioFormat: AudioFormat = AudioFormat.OPUS,
    ): StreamInfo = withContext(Dispatchers.IO) {
        val mutex =
            transcodeMutexes.computeIfAbsent(Triple(flacFile.absolutePath, targetKbps, audioFormat)) { Mutex() }

        mutex.withLock {
            if (!flacFile.exists()) {
                throw FileNotFoundException("Input file not found: ${flacFile.absolutePath}")
            }

            if (flacFile.isDirectory) {
                throw IOException("Input file is a directory: ${flacFile.absolutePath}")
            }

            if (flacFile.length() == 0L) {
                throw IOException("Input file is empty: ${flacFile.absolutePath}")
            }

            if (!flacFile.canRead()) {
                throw IOException("Input file is not readable: ${flacFile.absolutePath}")
            }

            val tracksPath = environment.config.propertyOrNull("audio.tracks")?.getString()
            val transcoderPath =
                environment.config.propertyOrNull("audio.transcode")?.getString() ?: ""

            val parent = if (tracksPath != null)
                flacFile.absoluteFile.parentFile.absolutePath.removePrefix(tracksPath)
            else flacFile.absoluteFile.parentFile.name

            val extension = if (audioFormat == AudioFormat.AAC) "m4a" else "ogg"
            val contentType = if (audioFormat == AudioFormat.AAC) ContentType.Audio.MP4 else ContentType.Audio.OGG

            val folder = if (audioFormat == AudioFormat.AAC) "${targetKbps}kbps_aac" else "${targetKbps}kbps"
            val fileName =
                Paths.get(
                    transcoderPath,
                    folder,
                    parent,
                    "${flacFile.nameWithoutExtension}.$extension"
                ).toFile()

            val transcodingFile = Files.createTempDirectory("transcoder_").toFile().apply {
                deleteOnExitRecursive()
            }.resolve("transcoding_${flacFile.nameWithoutExtension}.$extension")

            val tempFolder =
                if (fileName.isRooted) Paths.get("/").toFile()
                else Files.createTempDirectory("transcoder_").toFile().apply {
                    deleteOnExitRecursive()
                }
            val tempFile = tempFolder.resolve(fileName)

            if (tempFile.exists() && tempFile.length() > 0) {
                if (!force) {
                    return@withLock StreamInfo(
                        tempFile,
                        contentType,
                        tempFile.length(),
                        tempFile.name,
                    )
                }

                val flacDuration = getDuration(flacFile)
                val tempDuration = getDuration(tempFile)

                if (flacDuration != Duration.ZERO && tempDuration != Duration.ZERO && (flacDuration - tempDuration).absoluteValue < 1.seconds) {
                    return@withLock StreamInfo(
                        tempFile,
                        contentType,
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

                        if (audioFormat == AudioFormat.AAC) {
                            audioCodec = avcodec.AV_CODEC_ID_AAC
                            format = "mp4"
                            sampleFormat = avutil.AV_SAMPLE_FMT_FLTP
                        } else {
                            audioCodec = avcodec.AV_CODEC_ID_OPUS
                            format = "ogg"
                            sampleFormat = AV_SAMPLE_FMT_S16
                        }

                        sampleRate = closestSampleRate(grabber.sampleRate)
                        audioBitrate = targetKbps * 1000

                        frameRate = 1.0

                        inputMetadata.forEach { (key, value) ->
                            setMetadata(key.lowercase(), value)
                        }

                        setOption("id3v2_version", "0")
                        if (audioFormat == AudioFormat.OPUS) {
                            setMetadata("encoder", "Lavc-Ogg-Opus")
                        }

                        try {
                            start()
                        } catch (e: Exception) {
                            logger.error("FFmpeg recorder failed to start for ${transcodingFile.absolutePath}. grabber.audioChannels=${grabber.audioChannels}, grabber.sampleRate=${grabber.sampleRate}, targetKbps=$targetKbps, format=$audioFormat")
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
                    contentType,
                    tempFile.length(),
                    tempFile.name
                )
            } catch (e: Throwable) {
                tempFile.delete()
                if (e is FileNotFoundException || e is IOException) {
                    logger.error(e.message)
                } else {
                    logger.error("Transcoding failed for ${flacFile.absolutePath}: ${e.message}", e)
                }
                throw e
            } finally {
                transcodingFile.delete()
            }
        }
    }

    suspend fun getSongsWithTranscodingInfo(exclude: List<TranscodedVersion> = emptyList()) = dbQuery {
        val excludedSongIds = TranscodedSongTable
            .select(TranscodedSongTable.songId)
            .where {
                if (exclude.isEmpty()) Op.FALSE
                else exclude.map { (bitrate, format) ->
                    (TranscodedSongTable.bitrate eq bitrate) and (TranscodedSongTable.format eq format)
                }.reduce { acc, op -> acc or op }
            }
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
                    transcodedTo = listOfNotNull(
                        if (it.getOrNull(TranscodedSongTable.bitrate) != null) {
                            TranscodedVersion(
                                it[TranscodedSongTable.bitrate],
                                it[TranscodedSongTable.format]
                            )
                        } else null
                    ),
                )
            }
            .groupBy { it.id }
            .map { (_, songs) ->
                songs.first().copy(
                    transcodedTo = songs.flatMap { it.transcodedTo }.distinct(),
                )
            }
    }

    suspend fun insertTranscodedSong(songs: List<Triple<SimpleSong, File, TranscodedVersion>>) = dbQuery {
        songs.forEach { (song, file, version) ->
            TranscodedSongTable.insertIgnore {
                it[TranscodedSongTable.songId] = song.id
                it[TranscodedSongTable.bitrate] = version.bitrate
                it[TranscodedSongTable.format] = version.format
                it[TranscodedSongTable.path] = file.absolutePath
                it[TranscodedSongTable.fileSize] = file.length()
            }
        }
    }

    suspend fun insertTranscodedSong(songId: UUID, file: File, bitrate: Int, format: AudioFormat = AudioFormat.OPUS) = dbQuery {
        TranscodedSongTable.insertIgnore {
            it[TranscodedSongTable.songId] = songId
            it[TranscodedSongTable.bitrate] = bitrate
            it[TranscodedSongTable.format] = format
            it[TranscodedSongTable.path] = file.absolutePath
            it[TranscodedSongTable.fileSize] = file.length()
        }
    }
}