package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.services.schedule.SearchIndexRebuildWorker
import org.koin.core.component.inject

@Migration("2.9")
class RebuildArtistAndAlbumSearchIndices : CustomMigration() {
    private val searchIndexRebuildWorker by inject<SearchIndexRebuildWorker>()

    override suspend fun migrate() {
        logTask("Search Index Rebuild Worker") {
            searchIndexRebuildWorker.run { p, l -> updateProgress(p, l) }
        }
    }
}
