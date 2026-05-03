package dev.dertyp.services.schedule

import dev.dertyp.services.FlacAnalysisService
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger

class FlacAnalysisWorker : Worker("FlacAnalysisWorker") {
    private val flacAnalysisService by inject<FlacAnalysisService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        val unanalyzedIds = flacAnalysisService.getUnanalyzedSongIds()
        val needingFixIds = flacAnalysisService.getIdsNeedingFix(2.1)

        val totalToProcess = unanalyzedIds.size + needingFixIds.size

        if (totalToProcess == 0) {
            logger.info("No FLAC files to analyze or fix")
            return mapOf("processedCount" to 0)
        }

        logger.info("Found ${unanalyzedIds.size} unanalyzed and ${needingFixIds.size} needing fix. Total: $totalToProcess")
        val processedCount = AtomicInteger(0)

        if (unanalyzedIds.isNotEmpty()) {
            logger.info("Analyzing ${unanalyzedIds.size} files")

            runParallel(
                items = unanalyzedIds,
                baseThreadCount = Runtime.getRuntime().availableProcessors(),
                onItemProcessed = { currentCount ->
                    val progress = (currentCount.toDouble() / totalToProcess) * 100.0
                    onProgress(progress, "Analyzed $currentCount/$totalToProcess files")

                    if (currentCount % 100 == 0) {
                        logger.info("Analyzed $currentCount/${unanalyzedIds.size} files")
                    }
                }
            ) { songId ->
                flacAnalysisService.analyze(songId)
            }
        }

        if (needingFixIds.isNotEmpty()) {
            logger.info("Fixing ${needingFixIds.size} files sequentially")
            for (songId in needingFixIds) {
                try {
                    flacAnalysisService.fixSeekpoints(songId)
                    val currentCount = processedCount.incrementAndGet()
                    val progress = (currentCount.toDouble() / totalToProcess) * 100.0
                    onProgress(progress, "Fixed $currentCount/$totalToProcess files")

                    if (currentCount % 10 == 0) {
                        logger.info("Fixed $currentCount/${needingFixIds.size} files")
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) logger.error("Failed to fix FLAC $songId: ${e.message}")
                }
            }
        }

        return mapOf("processedCount" to processedCount.get())
    }
}
