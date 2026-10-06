package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.services.AlbumService
import org.koin.core.component.inject

@Migration("3.30")
class BuildAlbumVersionGroups : CustomMigration() {
    private val albumService by inject<AlbumService>()

    override suspend fun migrate() {
        logTask("Build album version groups") {
            updateProgress(0.0, "Grouping album editions")
            val updated = albumService.rebuildVersionGroups()
            updateProgress(1.0, "Updated $updated album(s)")

            logger.info("Built album version groups, updated $updated album(s)")
            mapOf("updated" to updated)
        }
    }
}
