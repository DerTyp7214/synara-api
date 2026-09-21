package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.services.ImageService
import org.koin.core.component.inject

@Migration("3.19")
class PurgeNonImageCoverFiles : CustomMigration() {
    private val imageService by inject<ImageService>()

    override suspend fun migrate() {
        logTask("Purge Non-Image Cover Files") {
            val result = imageService.purgeNonImageFiles(listOf("https://coverartarchive.org/")) { progress, message ->
                updateProgress(progress, message)
            }
            mapOf(
                "scanned" to result.scanned,
                "bogus" to result.bogus,
                "unlinked" to result.unlinked,
                "deleted" to result.deleted
            )
        }
    }
}
