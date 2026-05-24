package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.services.LibraryMergeService
import org.koin.core.component.get

@Migration("2.2")
class FixIncorrectAlbumMergesV2 : CustomMigration() {
    override suspend fun migrate() {
        val service = get<LibraryMergeService>()
        logger.info("Starting refined fix for incorrect album merges (V2)...")
        var lastLoggedP = -1
        val fixedCount = service.fixIncorrectMerges { progress, message ->
            val p = progress.toInt()
            if (p != lastLoggedP) {
                lastLoggedP = p
                logger.info("[$p%] $message")
            }
        }
        logger.info("Finished fixing incorrect album merges. Total albums split: $fixedCount")
    }
}
