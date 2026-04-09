package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.services.schedule.MusicBrainzCacheWorker
import org.koin.core.component.inject

@Migration("1.3")
class FillMusicBrainzCache : CustomMigration() {
    private val musicBrainzCacheWorker by inject<MusicBrainzCacheWorker>()

    override suspend fun migrate() {
        logTask("MusicBrainz Cache Worker") {
            musicBrainzCacheWorker.run { p, l -> updateProgress(p, l) }
        }
    }
}
