package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.services.sync.ListenBrainzService
import org.koin.core.component.inject

@Migration("3.7")
class BackfillListenMsids : CustomMigration() {
    private val listenBrainzService by inject<ListenBrainzService>()

    override suspend fun migrate() {
        logTask("Backfill listen MSIDs") {
            val updated = listenBrainzService.backfillRecordingMsids { progress, message ->
                updateProgress(progress, message)
            }
            mapOf("listensUpdated" to updated)
        }
    }
}
