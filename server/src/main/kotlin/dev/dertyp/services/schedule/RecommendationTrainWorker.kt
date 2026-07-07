package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.RecommendationService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.RECOMMENDATION_TRAINING, "Recommendation Model Training")
class RecommendationTrainWorker : Worker("RecommendationTrainWorker") {
    private val recommendationService by inject<RecommendationService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        if (!recommendationService.isConfigured()) {
            logger.info("recsys not configured, skipping recommendation training")
            return mapOf("skipped" to true)
        }
        val embeddings = recommendationService.trainIfDirty(onProgress)
        return mapOf("embeddings" to embeddings)
    }
}
