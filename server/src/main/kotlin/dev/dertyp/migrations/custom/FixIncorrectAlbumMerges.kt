package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.services.LibraryMergeService
import org.koin.core.component.get

@Migration("2.1")
class FixIncorrectAlbumMerges : CustomMigration() {
    override suspend fun migrate() {
        val service = get<LibraryMergeService>()
        logger.info("Starting fix for incorrect album merges...")
        val fixedCount = service.fixIncorrectMerges { progress, message ->
            val p = progress.toInt()
            if (p % 10 == 0) {
                logger.info("[$p%] $message")
            }
        }
        logger.info("Finished fixing incorrect album merges. Total albums split: $fixedCount")
    }
}
