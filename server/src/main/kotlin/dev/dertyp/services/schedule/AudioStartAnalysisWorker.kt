package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AudioStartAnalysisService
import kotlinx.coroutines.CancellationException
import org.koin.core.component.inject
import java.util.UUID

@WorkerTask(TaskKeys.AUDIO_START_ANALYSIS, "Audio Start Analysis", cron = "40 5 * * *")
class AudioStartAnalysisWorker : ItemWorker<UUID>("AudioStartAnalysisWorker") {
    private val audioStartAnalysisService by inject<AudioStartAnalysisService>()

    override val baseThreads: Int
        get() = Runtime.getRuntime().availableProcessors()
    override val resultKey = "processedCount"
    override val emptyMessage = "No songs to analyze for audio start"
    override val progressEvery = 50

    override suspend fun loadItems(): List<UUID> = audioStartAnalysisService.getUnanalyzedSongIds()

    override suspend fun process(item: UUID): ItemOutcome = try {
        audioStartAnalysisService.analyze(item)
        ItemOutcome.SUCCEEDED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Audio start analysis failed for song $item: ${e.message}")
        ItemOutcome.FAILED
    }

    override fun startMessage(total: Int) = "Analyzing audio start of $total songs"

    override fun progressMessage(current: Int, total: Int) = "Analyzed $current/$total songs"

    override fun results(counts: ItemCounts): Map<String, Any?> =
        mapOf(resultKey to counts.processed, "failedCount" to counts.failed)
}
