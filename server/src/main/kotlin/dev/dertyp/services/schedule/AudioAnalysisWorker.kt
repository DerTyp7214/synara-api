package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AudioAnalysisService
import org.koin.core.component.inject
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

@WorkerTask(TaskKeys.AUDIO_ANALYSIS, "Audio Analysis", cron = "0 3 * * *")
class AudioAnalysisWorker : ItemWorker<UUID>("AudioAnalysisWorker") {
    private val audioAnalysisService by inject<AudioAnalysisService>()

    override val baseThreads: Int
        get() = Runtime.getRuntime().availableProcessors() / 4
    override val resultKey = "analyzedCount"
    override val emptyMessage = "No songs to analyze"
    override val logEvery = 10
    override val timeout: Duration = 6.hours

    override suspend fun loadItems(): List<UUID> = audioAnalysisService.getUnanalyzedSongIds()

    override suspend fun process(item: UUID): ItemOutcome {
        audioAnalysisService.analyzeSong(item)
        return ItemOutcome.SUCCEEDED
    }

    override fun startMessage(total: Int) = "Found $total unanalyzed songs. Starting parallel analysis (max 6 hours)"

    override fun progressMessage(current: Int, total: Int) = "Analyzed $current/$total songs"
}
