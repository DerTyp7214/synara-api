package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AudioAnalysisService
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@WorkerTask(TaskKeys.AUDIO_TIMELINE_BACKFILL, "Audio Timeline Backfill")
class AudioTimelineBackfillWorker : Worker("AudioTimelineBackfillWorker") {
    private val audioAnalysisService by inject<AudioAnalysisService>()

    internal var clock: () -> Long = System::currentTimeMillis
    internal var runBudget: Duration = RUN_BUDGET

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val songIds = audioAnalysisService.getSongIdsMissingTimeline()
        val staleIds = audioAnalysisService.getSongIdsWithStaleTimeline()
        if (songIds.isEmpty() && staleIds.isEmpty()) {
            logger.info("No songs without audio timeline")
            return mapOf("analyzedCount" to 0, "refreshedCount" to 0, "skippedCount" to 0)
        }

        val baseThreads = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
        logger.info("Found ${songIds.size} songs without audio timeline and ${staleIds.size} with an outdated one. Starting parallel extraction (budget: 6 hours)")
        val processedCount = AtomicInteger(0)
        val refreshedCount = AtomicInteger(0)
        val skippedCount = AtomicInteger(0)
        val deadline = clock() + runBudget.inWholeMilliseconds

        withTimeoutOrNull(runBudget + HARD_STOP_GRACE) {
            if (songIds.isNotEmpty()) {
                runParallel(
                    items = songIds,
                    baseThreadCount = baseThreads,
                    onItemProcessed = { currentCount ->
                        onProgress(currentCount.toDouble() / songIds.size * 100.0, "Extracted $currentCount/${songIds.size} timelines")
                    }
                ) { songId ->
                    if (clock() >= deadline) {
                        skippedCount.incrementAndGet()
                        return@runParallel
                    }
                    audioAnalysisService.analyzeSong(songId)
                    processedCount.incrementAndGet()
                }
            }

            if (staleIds.isNotEmpty()) {
                runParallel(
                    items = staleIds,
                    baseThreadCount = baseThreads,
                    onItemProcessed = { currentCount ->
                        onProgress(currentCount.toDouble() / staleIds.size * 100.0, "Refreshed $currentCount/${staleIds.size} envelopes")
                    }
                ) { songId ->
                    if (clock() >= deadline) {
                        skippedCount.incrementAndGet()
                        return@runParallel
                    }
                    audioAnalysisService.refreshEnvelopes(songId)
                    refreshedCount.incrementAndGet()
                }
            }
        }

        return mapOf(
            "analyzedCount" to processedCount.get(),
            "refreshedCount" to refreshedCount.get(),
            "skippedCount" to skippedCount.get()
        )
    }

    companion object {
        val RUN_BUDGET = 6.hours
        val HARD_STOP_GRACE = 15.minutes
    }
}
