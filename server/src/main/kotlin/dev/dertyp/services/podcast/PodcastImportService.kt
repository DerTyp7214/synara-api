package dev.dertyp.services.podcast

import dev.dertyp.core.isInside
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.TaskKeys
import dev.dertyp.services.Service
import dev.dertyp.services.StorageCategory
import dev.dertyp.services.StorageService
import dev.dertyp.services.schedule.ScheduleService
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface ImportResult {
    data class Done(val file: File, val bytes: Long) : ImportResult

    data class Failed(val error: String, val permanent: Boolean) : ImportResult
}

class PodcastImportService(
    private val podcastService: PodcastService,
    private val storageService: StorageService,
    private val scheduleService: ScheduleService,
    private val http: PodcastHttp
) : Service() {

    fun targetDir(showId: UUID): File = File(storageService.podcastImportsPath, showId.toString())

    internal fun extensionFor(enclosureType: String?, url: String): String {
        val normalizedType = enclosureType?.substringBefore(';')?.trim()?.lowercase()
        val urlExtension = runCatching { Url(url).encodedPath }.getOrNull()
            ?.substringAfterLast('/', "")
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

        return when (normalizedType) {
            "audio/mpeg", "audio/mp3", "audio/x-mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "application/ogg" -> if (urlExtension == "opus") "opus" else "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> urlExtension?.takeIf { it in PodcastMediaProbe.AUDIO_EXTENSIONS } ?: "mp3"
        }
    }

    suspend fun import(episode: PodcastEpisodeRow): ImportResult {
        podcastService.markImportState(episode.id, PodcastImportState.IMPORTING)

        val directory = targetDir(episode.showId)
        val partFile = File(directory, "${episode.id}$PART_SUFFIX")

        val url = episode.enclosureUrl?.takeIf { it.isNotBlank() }
            ?: return fail(episode, partFile, "no enclosure", permanent = true)

        return try {
            http.requirePublicHttpUrl(url)
            withContext(Dispatchers.IO) { directory.mkdirs() }

            val extension = extensionFor(episode.enclosureType, url)
            val finalFile = File(directory, "${episode.id}.$extension")

            val outcome = http.mediaClient.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    val permanent = response.status.value == 404 || response.status.value == 410
                    return@execute ImportResult.Failed("HTTP ${response.status.value}", permanent)
                }

                val contentType = response.headers[HttpHeaders.ContentType]?.lowercase()
                if (contentType != null && contentType.startsWith("text/html")) {
                    return@execute ImportResult.Failed("Unexpected content type $contentType", true)
                }

                val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > MAX_EPISODE_BYTES) {
                    return@execute ImportResult.Failed("Episode is larger than $MAX_EPISODE_BYTES bytes", true)
                }

                val written = writeToFile(response.bodyAsChannel(), partFile)
                if (declaredLength != null && declaredLength != written) {
                    return@execute ImportResult.Failed("Incomplete transfer, expected $declaredLength bytes but got $written", false)
                }

                ImportResult.Done(partFile, written)
            }

            if (outcome is ImportResult.Failed) return fail(episode, partFile, outcome.error, outcome.permanent)

            val done = outcome as ImportResult.Done

            withContext(Dispatchers.IO) {
                try {
                    Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(partFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }

            if (episode.enclosureLength == null && done.bytes > 0) {
                podcastService.updateEnclosureLength(episode.id, done.bytes)
            }

            val probe = withContext(Dispatchers.IO) { PodcastMediaProbe.probe(finalFile) }
            val lyrics = probe.lyrics?.takeIf { it.isNotBlank() }
            if (lyrics != null) {
                podcastService.addEmbeddedTranscript(episode.id, lyrics, PodcastMediaProbe.transcriptTypeOf(lyrics))
            }

            podcastService.markImported(
                episodeId = episode.id,
                filePath = finalFile.absolutePath,
                fileSize = finalFile.length(),
                format = extension,
                durationMs = episode.durationMs ?: probe.durationMs
            )

            storageService.invalidate(StorageCategory.PODCASTS)
            storageService.invalidate(StorageCategory.TOTAL)

            ImportResult.Done(finalFile, done.bytes)
        } catch (e: FeedTooLargeException) {
            fail(episode, partFile, e.message ?: "Episode is too large", permanent = true)
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "Import failed"
            val diskFull = e is IOException && message.contains("No space left", ignoreCase = true)
            fail(episode, partFile, message, permanent = false, diskFull = diskFull)
        }
    }

    suspend fun processQueue(
        maxConcurrent: Int = 2,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Any?> {
        val recovered = recoverStale()

        var imported = 0
        var failed = 0
        var processed = 0
        val attempted = mutableSetOf<UUID>()
        val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))

        var total = podcastService.episodesInState(PodcastImportState.QUEUED, null).size.coerceAtLeast(1)

        while (true) {
            val batch = podcastService.episodesInState(PodcastImportState.QUEUED, BATCH_SIZE)
                .filter { it.id !in attempted }
            if (batch.isEmpty()) break

            batch.forEach { attempted += it.id }

            val results = coroutineScope {
                batch.map { episode -> async { semaphore.withPermit { import(episode) } } }.awaitAll()
            }

            results.forEach { if (it is ImportResult.Done) imported++ else failed++ }
            processed += batch.size
            if (processed > total) total = processed

            onProgress((processed * 90.0 / total).coerceAtMost(90.0), "Imported $imported of $processed episodes")
        }

        onProgress(95.0, "Cleaning up partial downloads")
        val sweptParts = sweepParts()

        return mapOf(
            "imported" to imported,
            "failed" to failed,
            "recovered" to recovered,
            "sweptParts" to sweptParts
        )
    }

    suspend fun importEpisode(episodeId: UUID) {
        val episode = podcastService.episodeById(episodeId)
        requireNotNull(episode) { "Unknown episode: $episodeId" }
        require(!episode.enclosureUrl.isNullOrBlank()) { "Episode has no audio to import" }

        podcastService.markImportState(episodeId, PodcastImportState.QUEUED, resetAttempts = true)
        scheduleService.triggerTask(TaskKeys.PODCAST_IMPORT)
    }

    suspend fun removeImport(episodeId: UUID) {
        val episode = podcastService.episodeById(episodeId)
        requireNotNull(episode) { "Unknown episode: $episodeId" }

        val show = podcastService.showById(episode.showId)
        require(show != null && show.source == PodcastSource.FEED) { "Only episodes of feed shows can be removed" }

        deleteFile(episode)
    }

    suspend fun deleteFile(episode: PodcastEpisodeRow) {
        val importsRoot = File(storageService.podcastImportsPath).absoluteFile.toPath()
        val path = episode.filePath

        if (path != null) {
            val file = File(path).absoluteFile
            if (file.toPath().isInside(importsRoot)) {
                withContext(Dispatchers.IO) {
                    file.delete()
                    val parent = file.parentFile
                    if (parent != null && parent.toPath().isInside(importsRoot) && parent.list()?.isEmpty() == true) {
                        parent.delete()
                    }
                }
            }
        }

        podcastService.clearImport(episode.id)
        storageService.invalidate(StorageCategory.PODCASTS)
        storageService.invalidate(StorageCategory.TOTAL)
    }

    private suspend fun recoverStale(): Int {
        val stale = podcastService.episodesInState(PodcastImportState.IMPORTING, null)

        stale.forEach { episode ->
            withContext(Dispatchers.IO) {
                runCatching { File(targetDir(episode.showId), "${episode.id}$PART_SUFFIX").delete() }
            }
            podcastService.markImportState(episode.id, PodcastImportState.QUEUED)
        }

        return stale.size
    }

    private suspend fun sweepParts(): Int = withContext(Dispatchers.IO) {
        val root = File(storageService.podcastImportsPath).absoluteFile
        if (!root.isDirectory) return@withContext 0

        val threshold = System.currentTimeMillis() - PART_MAX_AGE_MS

        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
            .filter { it.lastModified() < threshold }
            .count { runCatching { it.delete() }.getOrDefault(false) }
    }

    private suspend fun fail(
        episode: PodcastEpisodeRow,
        partFile: File,
        error: String,
        permanent: Boolean,
        diskFull: Boolean = false
    ): ImportResult.Failed {
        withContext(Dispatchers.IO) { runCatching { partFile.delete() } }
        podcastService.markImportState(
            episodeId = episode.id,
            state = PodcastImportState.FAILED,
            error = error,
            incrementAttempts = !diskFull
        )
        return ImportResult.Failed(error, permanent)
    }

    private suspend fun writeToFile(channel: ByteReadChannel, target: File): Long {
        var total = 0L

        withContext(Dispatchers.IO) {
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue

                    output.write(buffer, 0, read)
                    total += read
                    if (total > MAX_EPISODE_BYTES) {
                        throw FeedTooLargeException("Episode is larger than $MAX_EPISODE_BYTES bytes")
                    }
                }
            }
        }

        return total
    }

    companion object {
        const val MAX_EPISODE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ATTEMPTS = 3
        private const val PART_SUFFIX = ".part"
        private const val BATCH_SIZE = 20
        private const val BUFFER_SIZE = 64 * 1024
        private const val PART_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
