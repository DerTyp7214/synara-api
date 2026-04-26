package dev.dertyp.services.schedule

import dev.dertyp.services.AudioAnalysisService
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.hours

class AudioAnalysisWorker : Worker("AudioAnalysisWorker") {
    private val audioAnalysisService by inject<AudioAnalysisService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        val unanalyzedIds = audioAnalysisService.getUnanalyzedSongIds()
        if (unanalyzedIds.isEmpty()) {
            logger.info("No songs to analyze")
            return mapOf("analyzedCount" to 0)
        }

        logger.info("Found ${unanalyzedIds.size} unanalyzed songs. Starting analysis (max 3 hours)")
        var processedCount = 0

        withTimeoutOrNull(3.hours) {
            for (songId in unanalyzedIds) {
                try {
                    audioAnalysisService.analyzeSong(songId)
                    processedCount++
                    
                    val progress = (processedCount.toDouble() / unanalyzedIds.size) * 100.0
                    onProgress(progress, "Analyzed $processedCount/${unanalyzedIds.size} songs")
                    
                    if (processedCount % 10 == 0) {
                        logger.info("Analyzed $processedCount/${unanalyzedIds.size} songs")
                    }
                } catch (e: Exception) {
                    logger.error("Failed to analyze song $songId: ${e.message}")
                }
            }
        }

        return mapOf("analyzedCount" to processedCount)
    }
}
