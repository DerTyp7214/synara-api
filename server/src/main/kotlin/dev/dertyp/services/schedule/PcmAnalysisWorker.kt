package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.PcmAnalysisService
import org.koin.core.component.inject
import java.util.UUID

@WorkerTask(TaskKeys.PCM_ANALYSIS, "WAV/AIFF Analysis", cron = "20 5 * * *")
class PcmAnalysisWorker : ItemWorker<UUID>("PcmAnalysisWorker") {
    private val pcmAnalysisService by inject<PcmAnalysisService>()

    override val baseThreads: Int
        get() = Runtime.getRuntime().availableProcessors()
    override val resultKey = "processedCount"
    override val emptyMessage = "No WAV/AIFF files to analyze"
    override val progressEvery = 50

    override suspend fun loadItems(): List<UUID> = pcmAnalysisService.getUnanalyzedSongIds()

    override suspend fun process(item: UUID): ItemOutcome {
        pcmAnalysisService.analyze(item)
        return ItemOutcome.SUCCEEDED
    }

    override fun startMessage(total: Int) = "Analyzing $total WAV/AIFF files"

    override fun progressMessage(current: Int, total: Int) = "Analyzed $current/$total files"
}
