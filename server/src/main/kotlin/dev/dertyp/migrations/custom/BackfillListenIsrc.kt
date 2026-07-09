package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.ListenTable
import dev.dertyp.db.MBRecordingIsrcTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

@Migration("3.4")
class BackfillListenIsrc : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill listen ISRCs") {
            val mbids = dbQuery {
                ListenTable
                    .select(ListenTable.recordingMbid)
                    .where { ListenTable.recordingMbid.isNotNull() }
                    .andWhere { ListenTable.isrcs.isNull() }
                    .mapNotNull { it[ListenTable.recordingMbid] }
                    .distinct()
            }
            if (mbids.isEmpty()) {
                updateProgress(1.0, "No listens need ISRC backfill")
                return@logTask mapOf("listensUpdated" to 0)
            }

            val mbidToIsrcs = dbQuery {
                mbids.chunked(QUERY_CHUNK).flatMap { chunk ->
                    MBRecordingIsrcTable
                        .select(MBRecordingIsrcTable.recordingId, MBRecordingIsrcTable.isrc)
                        .where { MBRecordingIsrcTable.recordingId inList chunk }
                        .map { it[MBRecordingIsrcTable.recordingId].value to it[MBRecordingIsrcTable.isrc] }
                }.groupBy({ it.first }, { it.second })
                    .mapNotNull { (mbid, isrcs) -> ListenTable.joinIsrcs(isrcs)?.let { mbid to it } }
                    .toMap()
            }

            var updated = 0
            dbQuery {
                mbidToIsrcs.entries.forEachIndexed { index, (mbid, isrcs) ->
                    updated += ListenTable.update({
                        (ListenTable.recordingMbid eq mbid) and ListenTable.isrcs.isNull()
                    }) { it[ListenTable.isrcs] = isrcs }
                    if (index % 100 == 0 || index == mbidToIsrcs.size - 1) {
                        updateProgress((index + 1).toDouble() / mbidToIsrcs.size, "Backfilling listen ISRCs: ${index + 1}/${mbidToIsrcs.size}")
                    }
                }
            }

            mapOf("listensUpdated" to updated)
        }
    }

    companion object {
        private const val QUERY_CHUNK = 1000
    }
}
