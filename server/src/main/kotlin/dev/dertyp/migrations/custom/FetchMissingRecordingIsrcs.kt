package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.MBRecordingIsrcTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject

@Migration("2.5")
class FetchMissingRecordingIsrcs : CustomMigration() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()

    override suspend fun migrate() {
        logTask("Fetch Missing Recording ISRCs") {
            val recordingIds = dbQuery {
                MBRecordingTable.leftJoin(
                    MBRecordingIsrcTable,
                    onColumn = { MBRecordingTable.id },
                    otherColumn = { MBRecordingIsrcTable.recordingId })
                    .select(MBRecordingTable.id)
                    .where { MBRecordingIsrcTable.recordingId.isNull() }
                    .map { it[MBRecordingTable.id].value }
            }

            logger.info("Found ${recordingIds.size} recordings without cached ISRCs.")

            var recordingsUpdated = 0
            recordingIds.forEachIndexed { index, id ->
                try {
                    musicBrainzService.fetchRecordingById(id, HttpClientPriority.LOW)?.let { recording ->
                        musicBrainzCacheService.updateRecordingCache(recording)
                        if (recording.isrcs?.isNotEmpty() == true) {
                            recordingsUpdated++
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to fetch recording $id: ${e.message}")
                }

                if (index % 10 == 0 || index == recordingIds.lastIndex) {
                    val progress = if (recordingIds.isNotEmpty()) index.toDouble() / recordingIds.size else 1.0
                    updateProgress(progress, "Fetching ISRCs: ${index + 1}/${recordingIds.size}")
                }
            }
            mapOf("recordingsChecked" to recordingIds.size, "recordingsUpdated" to recordingsUpdated)
        }
    }
}
