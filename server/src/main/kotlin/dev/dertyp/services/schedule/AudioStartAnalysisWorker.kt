package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AudioStartAnalysisService
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger

@WorkerTask(TaskKeys.AUDIO_START_ANALYSIS, "Audio Start Analysis")
class AudioStartAnalysisWorker : Worker("AudioStartAnalysisWorker") {
    private val audioStartAnalysisService by inject<AudioStartAnalysisService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val unanalyzedIds = audioStartAnalysisService.getUnanalyzedSongIds()

        if (unanalyzedIds.isEmpty()) {
            logger.info("No songs to analyze for audio start")
            return mapOf("processedCount" to 0, "failedCount" to 0)
        }

        logger.info("Analyzing audio start of ${unanalyzedIds.size} songs")
        val processedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)

        runParallel(
            items = unanalyzedIds,
            baseThreadCount = Runtime.getRuntime().availableProcessors(),
            onItemProcessed = { _ ->
                val currentCount = processedCount.incrementAndGet()
                if (currentCount % 50 == 0 || currentCount == unanalyzedIds.size) {
                    onProgress((currentCount.toDouble() / unanalyzedIds.size) * 100.0, "Analyzed $currentCount/${unanalyzedIds.size} songs")
                }
            }
        ) { songId ->
            try {
                audioStartAnalysisService.analyze(songId)
            } catch (e: Exception) {
                failedCount.incrementAndGet()
                logger.error("Audio start analysis failed for song $songId: ${e.message}")
            }
        }

        return mapOf("processedCount" to processedCount.get(), "failedCount" to failedCount.get())
    }
}
