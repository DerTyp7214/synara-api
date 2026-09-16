package dev.dertyp.services.podcast

import dev.dertyp.core.isInside
import dev.dertyp.data.PodcastScanResult
import dev.dertyp.services.ImageService
import dev.dertyp.services.Service
import dev.dertyp.services.StorageCategory
import dev.dertyp.services.StorageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import java.util.UUID

class PodcastLocalScanService(
    private val podcastService: PodcastService,
    private val imageService: ImageService,
    private val storageService: StorageService
) : Service() {
    private val scanMutex = Mutex()

    suspend fun scan(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): PodcastScanResult = scanMutex.withLock {
        val root = File(storageService.podcastLibraryPath).absoluteFile
        if (!root.exists()) root.mkdirs()

        val realRoot = runCatching { root.toPath().toRealPath() }.getOrElse { root.toPath() }

        val directories = withContext(Dispatchers.IO) {
            root.listFiles()
                ?.filter { it.isDirectory && !it.isHidden && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
        }

        var added = 0
        var updated = 0
        var removed = 0
        var showsRemoved = 0

        directories.forEachIndexed { index, directory ->
            onProgress(index * 90.0 / directories.size.coerceAtLeast(1), "Scanning ${directory.name}")

            val localPath = directory.name

            val coverBytes = withContext(Dispatchers.IO) {
                coverFile(directory)?.let { file -> runCatching { file.readBytes() }.getOrNull() }
            }?.takeIf { it.isNotEmpty() }

            var showArtwork = coverBytes
            var showImageId = coverBytes?.let { bytes ->
                runCatching { imageService.createImage(bytes, "podcast-local:$localPath") }.getOrNull()
            }

            val showId = podcastService.upsertLocalShow(localPath, localPath, showImageId)
            val existing = podcastService.episodesOfShow(showId).associateBy { it.guidKey }
            val seenKeys = mutableSetOf<String>()
            val episodes = mutableListOf<LocalEpisode>()

            val files = withContext(Dispatchers.IO) { audioFiles(directory, realRoot) }

            for (file in files) {
                val guid = relativeGuid(directory, file)
                val key = PodcastKeys.guidKey(guid)
                seenKeys += key

                val row = existing[key]
                val size = file.length()
                if (row != null && row.fileSize == size && row.updatedAt >= file.lastModified()) continue

                val probe = withContext(Dispatchers.IO) { PodcastMediaProbe.probe(file) }

                var imageId: UUID? = null
                val artwork = probe.artwork
                if (artwork != null) {
                    if (showArtwork == null) {
                        showArtwork = artwork
                        showImageId = runCatching { imageService.createImage(artwork, "podcast-local:$localPath") }.getOrNull()
                        podcastService.upsertLocalShow(localPath, localPath, showImageId)
                    } else if (!artwork.contentEquals(showArtwork)) {
                        imageId = runCatching { imageService.createImage(artwork, "podcast-local-episode:$guid") }.getOrNull()
                    }
                }

                episodes += LocalEpisode(
                    guid = guid,
                    title = probe.title ?: file.nameWithoutExtension,
                    description = probe.comment,
                    publishedAt = probe.date ?: file.lastModified(),
                    durationMs = probe.durationMs,
                    filePath = file.absolutePath,
                    fileSize = size,
                    format = file.extension.lowercase(),
                    episodeNumber = probe.track,
                    imageId = imageId,
                    transcripts = transcriptsFor(file, probe.lyrics)
                )
            }

            if (episodes.isNotEmpty()) {
                val (inserted, changed) = podcastService.upsertLocalEpisodes(showId, episodes)
                added += inserted
                updated += changed
            }

            val stale = existing.filterKeys { it !in seenKeys }.values.map { it.id }
            if (stale.isNotEmpty()) removed += podcastService.deleteEpisodes(stale)
        }

        onProgress(95.0, "Removing gone shows")

        podcastService.localShows().forEach { show ->
            val localPath = show.localPath
            val directory = localPath?.let { File(root, it) }
            if (directory == null || !directory.isDirectory) {
                podcastService.deleteShow(show.id)
                showsRemoved++
            }
        }

        storageService.invalidate(StorageCategory.PODCASTS)
        storageService.invalidate(StorageCategory.TOTAL)

        PodcastScanResult(
            shows = directories.size,
            episodesAdded = added,
            episodesUpdated = updated,
            episodesRemoved = removed,
            showsRemoved = showsRemoved
        )
    }

    private fun coverFile(directory: File): File? {
        val names = directory.listFiles()?.filter { it.isFile } ?: return null
        return COVER_NAMES.firstNotNullOfOrNull { candidate ->
            names.firstOrNull { it.name.equals(candidate, ignoreCase = true) }
        }
    }

    private fun audioFiles(directory: File, realRoot: Path): List<File> = directory.walkTopDown()
        .onEnter { !it.isHidden && !it.name.startsWith(".") }
        .filter { it.isFile }
        .filter { !it.isHidden && !it.name.startsWith(".") }
        .filter { !it.name.endsWith(PART_SUFFIX) }
        .filter { it.extension.lowercase() in PodcastMediaProbe.AUDIO_EXTENSIONS }
        .filter { file ->
            val real = runCatching { file.toPath().toRealPath() }.getOrNull() ?: return@filter false
            real.isInside(realRoot)
        }
        .sortedBy { it.absolutePath.lowercase() }
        .toList()

    private fun relativeGuid(directory: File, file: File): String =
        file.absoluteFile.relativeTo(directory.absoluteFile).path.replace(File.separatorChar, '/')

    private fun transcriptsFor(file: File, lyrics: String?): List<LocalTranscript> {
        val transcripts = mutableListOf<LocalTranscript>()
        val base = file.nameWithoutExtension
        val siblings = file.parentFile?.listFiles()?.filter { it.isFile } ?: emptyList()

        for (sibling in siblings) {
            val extension = sibling.extension.lowercase()
            val type = TRANSCRIPT_TYPES[extension] ?: continue

            val stem = sibling.nameWithoutExtension
            val language = when {
                stem == base -> null
                stem.startsWith("$base.") -> stem.removePrefix("$base.").takeIf { it.isNotBlank() && !it.contains('.') } ?: continue
                else -> continue
            }

            transcripts += LocalTranscript.Sidecar(
                filePath = sibling.absolutePath,
                type = type,
                language = language?.take(16)
            )
        }

        if (!lyrics.isNullOrBlank()) {
            transcripts += LocalTranscript.Embedded(lyrics, PodcastMediaProbe.transcriptTypeOf(lyrics))
        }

        return transcripts
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private val COVER_NAMES = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png")
        private val TRANSCRIPT_TYPES = mapOf(
            "vtt" to "text/vtt",
            "srt" to "application/srt",
            "json" to "application/json",
            "txt" to "text/plain"
        )
    }
}
