package dev.dertyp.services.schedule

import dev.dertyp.services.AudioAnalysisService
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

class AudioAnalysisWorker : Worker("AudioAnalysisWorker") {
    private val audioAnalysisService by inject<AudioAnalysisService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        val unanalyzedIds = audioAnalysisService.getUnanalyzedSongIds()
        if (unanalyzedIds.isEmpty()) {
            logger.info("No songs to analyze")
            return mapOf("analyzedCount" to 0)
        }

        val baseThreads = Runtime.getRuntime().availableProcessors() / 4
        logger.info("Found ${unanalyzedIds.size} unanalyzed songs. Starting parallel analysis (max 6 hours)")
        val processedCount = AtomicInteger(0)

        withTimeoutOrNull(6.hours) {
            runParallel(
                items = unanalyzedIds,
                baseThreadCount = baseThreads,
                onItemProcessed = { currentCount ->
                    val progress = (currentCount.toDouble() / unanalyzedIds.size) * 100.0
                    onProgress(progress, "Analyzed $currentCount/${unanalyzedIds.size} songs")

                    if (currentCount % 10 == 0) {
                        logger.info("Analyzed $currentCount/${unanalyzedIds.size} songs")
                    }
                }
            ) { songId ->
                audioAnalysisService.analyzeSong(songId)
            }
        }

        return mapOf("analyzedCount" to processedCount.get())
    }
}
