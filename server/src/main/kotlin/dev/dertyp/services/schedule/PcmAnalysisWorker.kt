package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.PcmAnalysisService
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger

@WorkerTask(TaskKeys.PCM_ANALYSIS, "WAV/AIFF Analysis")
class PcmAnalysisWorker : Worker("PcmAnalysisWorker") {
    private val pcmAnalysisService by inject<PcmAnalysisService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val unanalyzedIds = pcmAnalysisService.getUnanalyzedSongIds()

        if (unanalyzedIds.isEmpty()) {
            logger.info("No WAV/AIFF files to analyze")
            return mapOf("processedCount" to 0)
        }

        logger.info("Analyzing ${unanalyzedIds.size} WAV/AIFF files")
        val processedCount = AtomicInteger(0)

        runParallel(
            items = unanalyzedIds,
            baseThreadCount = Runtime.getRuntime().availableProcessors(),
            onItemProcessed = { _ ->
                val currentCount = processedCount.incrementAndGet()
                if (currentCount % 50 == 0 || currentCount == unanalyzedIds.size) {
                    onProgress((currentCount.toDouble() / unanalyzedIds.size) * 100.0, "Analyzed $currentCount/${unanalyzedIds.size} files")
                }
            }
        ) { songId ->
            pcmAnalysisService.analyze(songId)
        }

        return mapOf("processedCount" to processedCount.get())
    }
}
