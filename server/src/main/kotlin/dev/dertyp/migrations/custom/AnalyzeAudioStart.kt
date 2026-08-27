package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.services.schedule.AudioStartAnalysisWorker
import org.koin.core.component.inject

@Migration("3.9")
class AnalyzeAudioStart : CustomMigration() {
    private val audioStartAnalysisWorker by inject<AudioStartAnalysisWorker>()

    override suspend fun migrate() {
        logTask("Audio Start Analysis") {
            audioStartAnalysisWorker.run { p, l -> updateProgress(p, l) }
        }
    }
}
