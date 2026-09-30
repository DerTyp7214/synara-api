package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.ImageService
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@WorkerTask(TaskKeys.IMAGE_ANALYSIS, "Image Analysis", afterTask = TaskKeys.DELETE_UNREFERENCED_IMAGES)
class ImageAnalysisWorker : ItemWorker<UUID>("ImageAnalysisWorker") {
    private val imageService by inject<ImageService>()

    override val baseThreads: Int
        get() = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    override val resultKey = "analyzedCount"
    override val emptyMessage = "No images to analyze"
    override val logEvery = 50
    override val timeout: Duration = 6.hours

    override suspend fun loadItems(): List<UUID> = imageService.getUnanalyzedImageIds()

    override suspend fun process(item: UUID): ItemOutcome = try {
        imageService.analyzeImage(item)
        ItemOutcome.SUCCEEDED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to analyze image $item", e)
        ItemOutcome.FAILED
    }

    override fun startMessage(total: Int) = "Found $total unanalyzed images. Starting parallel analysis (max 6 hours)"

    override fun progressMessage(current: Int, total: Int) = "Analyzed $current/$total images"

    override fun results(counts: ItemCounts): Map<String, Any?> = mapOf(resultKey to counts.succeeded)
}
