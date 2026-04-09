package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.inject

@Migration("1.3")
class FillMusicBrainzCache : CustomMigration() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()

    override suspend fun migrate() {
        val totalArtists = dbQuery { MBArtistTable.selectAll().where { MBArtistTable.lastUpdate eq 0L }.count() }
        logger.info("Filling MusicBrainz cache for $totalArtists artists")
        musicBrainzCacheService.staleArtistIdsFlow(1L).collect { id ->
            try {
                musicBrainzService.fetchArtistById(id)?.let {
                    musicBrainzCacheService.updateArtistCache(it)
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch artist $id: ${e.message}")
            }
        }

        val totalReleaseGroups = dbQuery { MBReleaseGroupTable.selectAll().where { MBReleaseGroupTable.lastUpdate eq 0L }.count() }
        logger.info("Filling MusicBrainz cache for $totalReleaseGroups release groups")
        musicBrainzCacheService.staleReleaseGroupIdsFlow(1L).collect { id ->
            try {
                musicBrainzService.fetchReleaseGroupById(id)?.let {
                    musicBrainzCacheService.updateReleaseGroupCache(it)
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch release group $id: ${e.message}")
            }
        }

        val totalReleases = dbQuery { MBReleaseTable.selectAll().where { MBReleaseTable.lastUpdate eq 0L }.count() }
        logger.info("Filling MusicBrainz cache for $totalReleases releases")
        musicBrainzCacheService.staleReleaseIdsFlow(1L).collect { id ->
            try {
                musicBrainzService.fetchReleaseById(id)?.let {
                    musicBrainzCacheService.updateReleaseCache(it)
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch release $id: ${e.message}")
            }
        }

        val totalRecordings = dbQuery { MBRecordingTable.selectAll().where { MBRecordingTable.lastUpdate eq 0L }.count() }
        logger.info("Filling MusicBrainz cache for $totalRecordings recordings")
        musicBrainzCacheService.staleRecordingIdsFlow(1L).collect { id ->
            try {
                musicBrainzService.fetchRecordingById(id)?.let {
                    musicBrainzCacheService.updateRecordingCache(it)
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch recording $id: ${e.message}")
            }
        }
    }
}
