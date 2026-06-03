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
            val batchSize = 50
            recordingIds.chunked(batchSize).forEachIndexed { batchIndex, chunk ->
                try {
                    val recordings = musicBrainzService.fetchRecordingsMetadataLB(chunk, HttpClientPriority.LOW)
                    recordings.forEach { recording ->
                        if (recording.isrcs?.isNotEmpty() == true) {
                            musicBrainzCacheService.updateRecordingIsrcs(recording.id,
                                recording.isrcs!!
                            )
                            recordingsUpdated++
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to fetch recordings batch ${batchIndex + 1}: ${e.message}")
                }

                val processedCount = (batchIndex + 1) * batchSize
                val progress =
                    if (recordingIds.isNotEmpty()) processedCount.toDouble() / recordingIds.size else 1.0
                updateProgress(
                    progress.coerceAtMost(1.0),
                    "Fetching ISRCs: ${processedCount.coerceAtMost(recordingIds.size)}/${recordingIds.size} | Updated: $recordingsUpdated"
                )
            }
            mapOf(
                "recordingsChecked" to recordingIds.size,
                "recordingsUpdated" to recordingsUpdated
            )
        }
    }
}
