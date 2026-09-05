package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AudioAnalysisService
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

@WorkerTask(TaskKeys.AUDIO_TIMELINE_BACKFILL, "Audio Timeline Backfill")
class AudioTimelineBackfillWorker : Worker("AudioTimelineBackfillWorker") {
    private val audioAnalysisService by inject<AudioAnalysisService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val songIds = audioAnalysisService.getSongIdsMissingTimeline(BATCH_LIMIT)
        if (songIds.isEmpty()) {
            logger.info("No songs without audio timeline")
            return mapOf("analyzedCount" to 0)
        }

        val baseThreads = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
        logger.info("Found ${songIds.size} songs without audio timeline. Starting parallel extraction (max 6 hours)")
        val processedCount = AtomicInteger(0)

        withTimeoutOrNull(6.hours) {
            runParallel(
                items = songIds,
                baseThreadCount = baseThreads,
                onItemProcessed = { currentCount ->
                    processedCount.set(currentCount)
                    onProgress(currentCount.toDouble() / songIds.size * 100.0, "Extracted $currentCount/${songIds.size} timelines")
                }
            ) { songId ->
                audioAnalysisService.analyzeSong(songId)
            }
        }

        return mapOf("analyzedCount" to processedCount.get())
    }

    companion object {
        const val BATCH_LIMIT = 2000
    }
}
