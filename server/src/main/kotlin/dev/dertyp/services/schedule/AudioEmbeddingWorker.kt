package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AudioEmbeddingService
import dev.dertyp.services.RecommendationService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.AUDIO_EMBEDDING, "Audio Embedding")
class AudioEmbeddingWorker : Worker("AudioEmbeddingWorker") {
    private val audioEmbeddingService by inject<AudioEmbeddingService>()
    private val recommendationService by inject<RecommendationService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        if (!audioEmbeddingService.isConfigured()) {
            logger.info("audio-embed not configured, skipping audio embedding")
            return mapOf("skipped" to true)
        }

        val todo = audioEmbeddingService.getUnembeddedSongs()
        if (todo.isEmpty()) {
            logger.info("No songs to embed")
            return mapOf("embedded" to 0)
        }

        logger.info("Embedding ${todo.size} songs")
        var done = 0
        todo.chunked(BATCH).forEach { batch ->
            done += audioEmbeddingService.embedAndStore(batch)
            onProgress(done.toDouble() / todo.size * 100.0, "Embedded $done/${todo.size} songs")
        }
        if (done > 0) recommendationService.markDirty()
        return mapOf("embedded" to done, "total" to todo.size)
    }

    companion object {
        private const val BATCH = 16
    }
}
