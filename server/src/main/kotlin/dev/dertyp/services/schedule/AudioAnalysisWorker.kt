package dev.dertyp.services.schedule

import dev.dertyp.PlatformUUID
import dev.dertyp.services.AudioAnalysisService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        val threadCount = (Runtime.getRuntime().availableProcessors() / 4).coerceAtLeast(1)
        logger.info("Found ${unanalyzedIds.size} unanalyzed songs. Starting parallel analysis with $threadCount threads (max 6 hours)")
        val processedCount = AtomicInteger(0)

        withTimeoutOrNull(6.hours) {
            coroutineScope {
                val songChannel = Channel<PlatformUUID>(Channel.UNLIMITED)

                repeat(threadCount) {
                    launch {
                        for (songId in songChannel) {
                            try {
                                audioAnalysisService.analyzeSong(songId)
                                val currentCount = processedCount.incrementAndGet()

                                val progress = (currentCount.toDouble() / unanalyzedIds.size) * 100.0
                                onProgress(progress, "Analyzed $currentCount/${unanalyzedIds.size} songs")

                                if (currentCount % 10 == 0) {
                                    logger.info("Analyzed $currentCount/${unanalyzedIds.size} songs")
                                }
                            } catch (e: Exception) {
                                if (e !is CancellationException) logger.error("Failed to analyze song $songId: ${e.message}")
                            }
                        }
                    }
                }

                for (songId in unanalyzedIds) {
                    songChannel.send(songId)
                }
                songChannel.close()
            }
        }

        return mapOf("analyzedCount" to processedCount.get())
    }
}
