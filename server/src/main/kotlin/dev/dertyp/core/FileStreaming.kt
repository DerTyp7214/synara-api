package dev.dertyp.core

import dev.dertyp.audio.LosslessFormat
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

fun File.chunkFlow(offset: Long, chunkSize: Int): Flow<ByteArray> = flow {
    RandomAccessFile(this@chunkFlow, "r").use { input ->
        input.seek(offset)
        val buffer = ByteArray(chunkSize)
        var bytesRead = input.read(buffer)
        while (bytesRead != -1) {
            emit(buffer.copyOf(bytesRead))
            bytesRead = input.read(buffer)
        }
    }
}

private val commonAudioContentTypes: Map<String, ContentType> = mapOf(
    "flac" to LosslessFormat.FLAC.contentType,
    "wav" to LosslessFormat.WAV.contentType,
    "ogg" to ContentType.Audio.OGG,
    "oga" to ContentType.Audio.OGG,
    "opus" to ContentType.Audio.OGG,
    "m4a" to ContentType.Audio.MP4,
    "mp4" to ContentType.Audio.MP4,
    "mp3" to ContentType.Audio.MPEG,
)

val songContentTypes: Map<String, ContentType> = commonAudioContentTypes + mapOf(
    "aiff" to LosslessFormat.AIFF.contentType,
    "aif" to LosslessFormat.AIFF.contentType,
    "aac" to ContentType.Audio.MP4,
)

val podcastContentTypes: Map<String, ContentType> = commonAudioContentTypes + mapOf(
    "m4b" to ContentType.Audio.MP4,
    "aac" to ContentType("audio", "aac"),
)

fun contentTypeFor(file: File, known: Map<String, ContentType>): ContentType =
    known[file.extension.lowercase()]
        ?: runCatching { Files.probeContentType(file.toPath())?.let(ContentType::parse) }.getOrNull()
        ?: ContentType.Application.OctetStream
