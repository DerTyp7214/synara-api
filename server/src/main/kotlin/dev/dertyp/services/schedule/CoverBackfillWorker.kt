package dev.dertyp.services.schedule

import dev.dertyp.data.CoverTarget
import dev.dertyp.data.TaskKeys
import dev.dertyp.services.cover.CoverGenerationService
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject

@WorkerTask(TaskKeys.COVER_BACKFILL, "Cover Backfill", afterTask = TaskKeys.IMAGE_ANALYSIS)
class CoverBackfillWorker : ItemWorker<CoverTarget>("CoverBackfillWorker") {
    private val coverGenerationService by inject<CoverGenerationService>()

    override val baseThreads = 2
    override val resultKey = "generated"
    override val emptyMessage = "No playlists or collections without cover"

    override suspend fun loadItems(): List<CoverTarget> = coverGenerationService.missingTargets(null)

    override suspend fun process(item: CoverTarget): ItemOutcome = try {
        if (coverGenerationService.autoGenerate(item) != null) ItemOutcome.SUCCEEDED else ItemOutcome.SKIPPED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Cover generation failed for ${item.type} ${item.id}: ${e.message}")
        ItemOutcome.FAILED
    }

    override fun progressMessage(current: Int, total: Int) = "Generated $current/$total covers"

    override fun results(counts: ItemCounts): Map<String, Any?> =
        mapOf(resultKey to counts.succeeded, "failed" to counts.failed)
}
