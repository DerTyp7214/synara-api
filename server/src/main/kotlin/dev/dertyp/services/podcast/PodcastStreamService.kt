package dev.dertyp.services.podcast

import dev.dertyp.StreamInfo
import dev.dertyp.services.Service
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.UUID

class PodcastStreamService(
    private val podcastService: PodcastService,
    private val http: PodcastHttp
) : Service() {
    suspend fun resolveFile(episodeId: UUID): StreamInfo? {
        val episode = podcastService.episodeById(episodeId) ?: return null
        val path = episode.filePath ?: return null
        val file = File(path)

        if (!file.exists()) {
            podcastService.clearImport(episodeId)
            return null
        }

        return StreamInfo(file, contentTypeFor(file), file.length(), file.name)
    }

    fun streamEpisode(episodeId: UUID, offset: Long, chunkSize: Int = 4096): Flow<ByteArray>? {
        val episode = runBlocking { podcastService.episodeById(episodeId) } ?: return null
        val stored = episode.filePath?.let(::File)
        val file = stored?.takeIf { it.exists() }

        if (stored != null && file == null) {
            runBlocking { podcastService.clearImport(episodeId) }
        }

        val url = episode.enclosureUrl
        if (file == null && url == null) return null

        return flow {
            if (file != null) {
                val buffer = ByteArray(chunkSize)
                file.inputStream().use { input ->
                    input.skip(offset)
                    var read = input.read(buffer)
                    while (read != -1) {
                        emit(buffer.copyOf(read))
                        read = input.read(buffer)
                    }
                }
                return@flow
            }

            val target = url ?: return@flow
            http.requirePublicHttpUrl(target)

            http.mediaClient.prepareGet(target) {
                header(HttpHeaders.Range, "bytes=$offset-")
            }.execute { response ->
                require(response.status.isSuccess()) {
                    "The episode could not be streamed from its origin: ${response.status}"
                }

                var remaining = if (response.status == HttpStatusCode.PartialContent) 0L else offset
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(chunkSize)

                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue

                    var start = 0
                    if (remaining > 0) {
                        val skipped = minOf(remaining, read.toLong()).toInt()
                        remaining -= skipped
                        start = skipped
                    }

                    if (start < read) emit(buffer.copyOfRange(start, read))
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getStreamSize(episodeId: UUID): Long {
        val episode = podcastService.episodeById(episodeId) ?: return 0
        val file = episode.filePath?.let(::File)
        if (file != null && file.exists()) return file.length()

        val known = episode.fileSize ?: episode.enclosureLength
        if (known != null && known > 0) return known

        val url = episode.enclosureUrl ?: return 0
        val remote = runCatching { remoteSize(url) }.getOrNull() ?: 0
        if (remote <= 0) return 0

        podcastService.updateEnclosureLength(episodeId, remote)
        return remote
    }

    private suspend fun remoteSize(url: String): Long {
        http.requirePublicHttpUrl(url)

        val probe = runCatching { http.mediaClient.head(url) }.getOrNull()
        if (probe != null && probe.status.isSuccess()) {
            val length = probe.contentLength()
            if (length != null && length > 0) return length
        }

        return http.mediaClient.prepareGet(url) {
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { response ->
            if (!response.status.isSuccess()) {
                0L
            } else {
                val total = response.headers[HttpHeaders.ContentRange]
                    ?.substringAfterLast('/', "")
                    ?.trim()
                    ?.toLongOrNull()

                total ?: response.contentLength() ?: 0L
            }
        }
    }

    private fun contentTypeFor(file: File): ContentType = when (file.extension.lowercase()) {
        "mp3" -> ContentType.Audio.MPEG
        "m4a", "m4b", "mp4" -> ContentType.Audio.MP4
        "aac" -> ContentType("audio", "aac")
        "ogg", "oga", "opus" -> ContentType.Audio.OGG
        "flac" -> ContentType("audio", "flac")
        "wav" -> ContentType("audio", "wav")
        else -> runCatching { Files.probeContentType(file.toPath())?.let(ContentType::parse) }.getOrNull()
            ?: ContentType.Application.OctetStream
    }
}
