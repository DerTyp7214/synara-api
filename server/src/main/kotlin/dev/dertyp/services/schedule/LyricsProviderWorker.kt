package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.TaskKeys
import dev.dertyp.services.lyrics.LyricsCandidateService
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject

@WorkerTask(TaskKeys.LYRICS_PROVIDER_WORKER, "Lyrics Provider Worker", cron = "0 5 * * *")
class LyricsProviderWorker : Worker("LyricsProviderWorker") {
    private val lyricsCandidateService by inject<LyricsCandidateService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val songs = lyricsCandidateService.songsDue()
        if (songs.isEmpty()) {
            logger.info("No songs are due for a lyrics provider lookup.")
            return emptyMap()
        }

        var withLyrics = 0
        var withoutLyrics = 0
        var pending = 0
        var failed = 0
        val total = songs.size.toDouble()

        songs.forEachIndexed { index, songId ->
            onProgress((index / total) * 100, "Looking up lyrics for song ${index + 1}/${songs.size}")
            try {
                val result = lyricsCandidateService.findLyrics(songId, priority = HttpClientPriority.LOW)
                if (result.candidates.isEmpty()) withoutLyrics++ else withLyrics++
                if (result.pending.isNotEmpty()) pending++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                logger.error("Failed to look up lyrics for song $songId: ${e.message}")
            }
        }

        return mapOf("withLyrics" to withLyrics, "withoutLyrics" to withoutLyrics, "pending" to pending, "failed" to failed)
    }
}
