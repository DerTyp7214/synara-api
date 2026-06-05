package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.ImageService
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

@WorkerTask(TaskKeys.IMAGE_ANALYSIS, "Image Analysis")
class ImageAnalysisWorker : Worker("ImageAnalysisWorker") {
    private val imageService by inject<ImageService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val unanalyzedIds = imageService.getUnanalyzedImageIds()
        if (unanalyzedIds.isEmpty()) {
            logger.info("No images to analyze")
            return mapOf("analyzedCount" to 0)
        }

        val baseThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        logger.info("Found ${unanalyzedIds.size} unanalyzed images. Starting parallel analysis (max 6 hours)")
        val processedCount = AtomicInteger(0)

        withTimeoutOrNull(6.hours) {
            runParallel(
                items = unanalyzedIds,
                baseThreadCount = baseThreads,
                onItemProcessed = { currentCount ->
                    val progress = (currentCount.toDouble() / unanalyzedIds.size) * 100.0
                    onProgress(progress, "Analyzed $currentCount/${unanalyzedIds.size} images")

                    if (currentCount % 50 == 0) {
                        logger.info("Analyzed $currentCount/${unanalyzedIds.size} images")
                    }
                }
            ) { imageId ->
                try {
                    imageService.analyzeImage(imageId)
                } catch (e: Exception) {
                    logger.error("Failed to analyze image $imageId", e)
                }
            }
        }

        return mapOf("analyzedCount" to processedCount.get())
    }
}
