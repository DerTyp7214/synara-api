package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.MBRecordingArtistCreditTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.CachedMusicBrainzService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject

@Migration("2.6")
class FulfillIncompleteRecordings : CustomMigration() {
    private val cachedMusicBrainzService by inject<CachedMusicBrainzService>()

    override suspend fun migrate() {
        logTask("Fulfill Incomplete MusicBrainz Recordings") {
            val incompleteIds = dbQuery {
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

            logger.info("Found ${incompleteIds.size} incomplete recordings in cache.")

            var updated = 0
            incompleteIds.forEachIndexed { index, id ->
                try {
                    cachedMusicBrainzService.getRecording(id, HttpClientPriority.LOW)
                    updated++
                } catch (e: Exception) {
                    logger.error("Failed to fulfill recording $id: ${e.message}")
                }

                if (index % 10 == 0 || index == incompleteIds.size - 1) {
                    updateProgress(
                        (index + 1).toDouble() / incompleteIds.size,
                        "Fulfilling recordings: ${index + 1}/${incompleteIds.size} | Updated: $updated"
                    )
                }
            }

            mapOf(
                "recordingsChecked" to incompleteIds.size,
                "recordingsUpdated" to updated
            )
        }
    }
}
