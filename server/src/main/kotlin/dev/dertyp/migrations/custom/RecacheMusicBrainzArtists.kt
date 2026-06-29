package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.MBArtistTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject

@Migration("3.2")
class RecacheMusicBrainzArtists : CustomMigration() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()

    override suspend fun migrate() {
        logTask("Re-cache MusicBrainz Artists") {
            val artistIds = dbQuery {
                MBArtistTable.select(MBArtistTable.id).map { it[MBArtistTable.id].value }
            }

            logger.info("Found ${artistIds.size} MusicBrainz artists to re-cache.")

            var updated = 0
            var failed = 0
            artistIds.forEachIndexed { index, id ->
                try {
                    musicBrainzService.fetchArtistById(id, HttpClientPriority.LOW)?.let {
                        musicBrainzCacheService.updateArtistCache(it)
                        updated++
                    }
                } catch (e: Exception) {
                    failed++
                    logger.error("Failed to re-cache artist $id: ${e.message}")
                }

                updateProgress(
                    (index + 1).toDouble() / artistIds.size,
                    "Re-caching artists: ${index + 1}/${artistIds.size} | Updated: $updated | Failed: $failed"
                )
            }

            logger.info("Re-cached $updated/${artistIds.size} MusicBrainz artists ($failed failed).")

            mapOf(
                "artistsChecked" to artistIds.size,
                "artistsUpdated" to updated,
                "artistsFailed" to failed
            )
        }
    }
}
