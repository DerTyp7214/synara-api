package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.cover.CoverGenerationService
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger

@WorkerTask(TaskKeys.COVER_BACKFILL, "Cover Backfill")
class CoverBackfillWorker : Worker("CoverBackfillWorker") {
    private val coverGenerationService by inject<CoverGenerationService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val targets = coverGenerationService.missingTargets(null)
        if (targets.isEmpty()) {
            logger.info("No playlists or collections without cover")
            return mapOf("generated" to 0, "failed" to 0)
        }

        val generated = AtomicInteger(0)
        val failed = AtomicInteger(0)
        runParallel(
            items = targets,
            baseThreadCount = 2,
            onItemProcessed = { count ->
                onProgress(count.toDouble() / targets.size * 100.0, "Generated $count/${targets.size} covers")
            }
        ) { target ->
            try {
                if (coverGenerationService.autoGenerate(target) != null) generated.incrementAndGet()
            } catch (e: Exception) {
                failed.incrementAndGet()
                logger.warn("Cover generation failed for ${target.type} ${target.id}: ${e.message}")
            }
        }

        return mapOf("generated" to generated.get(), "failed" to failed.get())
    }
}
