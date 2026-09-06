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
        val staleIds = audioAnalysisService.getSongIdsWithStaleTimeline(BATCH_LIMIT)
        if (songIds.isEmpty() && staleIds.isEmpty()) {
            logger.info("No songs without audio timeline")
            return mapOf("analyzedCount" to 0, "refreshedCount" to 0)
        }

        val baseThreads = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
        logger.info("Found ${songIds.size} songs without audio timeline and ${staleIds.size} with an outdated one. Starting parallel extraction (max 6 hours)")
        val processedCount = AtomicInteger(0)
        val refreshedCount = AtomicInteger(0)

        withTimeoutOrNull(6.hours) {
            if (songIds.isNotEmpty()) {
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

            if (staleIds.isNotEmpty()) {
                runParallel(
                    items = staleIds,
                    baseThreadCount = baseThreads,
                    onItemProcessed = { currentCount ->
                        refreshedCount.set(currentCount)
                        onProgress(currentCount.toDouble() / staleIds.size * 100.0, "Refreshed $currentCount/${staleIds.size} envelopes")
                    }
                ) { songId ->
                    audioAnalysisService.refreshEnvelopes(songId)
                }
            }
        }

        return mapOf("analyzedCount" to processedCount.get(), "refreshedCount" to refreshedCount.get())
    }

    companion object {
        const val BATCH_LIMIT = 2000
    }
}
