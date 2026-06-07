package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.MBMediaTable
import dev.dertyp.db.MBRecordingArtistCreditTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject

@Migration("2.7")
class FulfillIncompleteMusicBrainzCache : CustomMigration() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()

    override suspend fun migrate() {
        logTask("Fulfill Incomplete MusicBrainz Cache") {
            val incompleteReleaseIds = dbQuery {
                val releasesWithNoTitle = MBReleaseTable.select(MBReleaseTable.id)
                    .where { MBReleaseTable.title eq "" }
                    .map { it[MBReleaseTable.id].value }

                val releasesWithNoMedia = MBReleaseTable
                    .leftJoin(MBMediaTable, onColumn = { MBReleaseTable.id }, otherColumn = { MBMediaTable.releaseId })
                    .select(MBReleaseTable.id)
                    .where { MBMediaTable.releaseId.isNull() }
                    .map { it[MBReleaseTable.id].value }

                (releasesWithNoTitle + releasesWithNoMedia).distinct()
            }

            val incompleteRecordingIds = dbQuery {
                val recordingsWithNoArtists = MBRecordingTable
                    .leftJoin(MBRecordingArtistCreditTable, onColumn = { MBRecordingTable.id }, otherColumn = { MBRecordingArtistCreditTable.recordingId })
                    .select(MBRecordingTable.id)
                    .where { MBRecordingArtistCreditTable.recordingId.isNull() }
                    .map { it[MBRecordingTable.id].value }

                val recordingsWithNoData = MBRecordingTable
                    .select(MBRecordingTable.id)
                    .where { (MBRecordingTable.title eq "") or MBRecordingTable.length.isNull() }
                    .map { it[MBRecordingTable.id].value }

                (recordingsWithNoArtists + recordingsWithNoData).distinct()
            }

            logger.info("Found ${incompleteReleaseIds.size} incomplete releases and ${incompleteRecordingIds.size} incomplete recordings.")

            var releasesUpdated = 0
            incompleteReleaseIds.forEachIndexed { index, id ->
                try {
                    musicBrainzService.fetchReleaseById(id, HttpClientPriority.LOW)?.let {
                        musicBrainzCacheService.updateReleaseCache(it)
                        releasesUpdated++
                    }
                } catch (e: Exception) {
                    logger.error("Failed to fulfill release $id: ${e.message}")
                }

                if (index % 10 == 0 || index == incompleteReleaseIds.size - 1) {
                    updateProgress(
                        (index + 1).toDouble() / (incompleteReleaseIds.size + incompleteRecordingIds.size),
                        "Fulfilling releases: ${index + 1}/${incompleteReleaseIds.size} | Updated: $releasesUpdated"
                    )
                }
            }

            val remainingRecordingIds = dbQuery {
                val recordingsWithNoArtists = MBRecordingTable
                    .leftJoin(MBRecordingArtistCreditTable, onColumn = { MBRecordingTable.id }, otherColumn = { MBRecordingArtistCreditTable.recordingId })
                    .select(MBRecordingTable.id)
                    .where { (MBRecordingTable.id inList incompleteRecordingIds) and MBRecordingArtistCreditTable.recordingId.isNull() }
                    .map { it[MBRecordingTable.id].value }

                val recordingsWithNoData = MBRecordingTable
                    .select(MBRecordingTable.id)
                    .where { (MBRecordingTable.id inList incompleteRecordingIds) and ((MBRecordingTable.title eq "") or MBRecordingTable.length.isNull()) }
                    .map { it[MBRecordingTable.id].value }

                (recordingsWithNoArtists + recordingsWithNoData).distinct()
            }

            logger.info("${incompleteRecordingIds.size - remainingRecordingIds.size} recordings were fulfilled by release updates. ${remainingRecordingIds.size} remaining.")

            var recordingsUpdated = 0
            remainingRecordingIds.forEachIndexed { index, id ->
                try {
                    musicBrainzService.fetchRecordingById(id, HttpClientPriority.LOW)?.let {
                        musicBrainzCacheService.updateRecordingCache(it)
                        recordingsUpdated++
                    }
                } catch (e: Exception) {
                    logger.error("Failed to fulfill recording $id: ${e.message}")
                }

                if (index % 10 == 0 || index == remainingRecordingIds.size - 1) {
                    updateProgress(
                        (incompleteReleaseIds.size + index + 1).toDouble() / (incompleteReleaseIds.size + remainingRecordingIds.size),
                        "Fulfilling recordings: ${index + 1}/${remainingRecordingIds.size} | Updated: $recordingsUpdated"
                    )
                }
            }

            mapOf(
                "releasesChecked" to incompleteReleaseIds.size,
                "releasesUpdated" to releasesUpdated,
                "recordingsChecked" to incompleteRecordingIds.size,
                "recordingsFulfilledByReleases" to (incompleteRecordingIds.size - remainingRecordingIds.size),
                "recordingsUpdated" to recordingsUpdated
            )
        }
    }
}
