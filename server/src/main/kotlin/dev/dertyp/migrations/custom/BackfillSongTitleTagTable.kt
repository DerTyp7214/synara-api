package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.logTask
import dev.dertyp.db.SongTable
import dev.dertyp.db.syncSongTitleTags
import dev.dertyp.db.titleTags
import org.jetbrains.exposed.v1.jdbc.select

private const val CHUNK_SIZE = 1000

@Migration("3.26")
class BackfillSongTitleTagTable : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill song title tag table") {
            val rows = dbQuery {
                SongTable.select(SongTable.id, SongTable.titleTags).toList()
            }

            val total = rows.size
            var done = 0
            var tagged = 0

            for (chunk in rows.chunked(CHUNK_SIZE)) {
                val songs = chunk.map { it[SongTable.id].value to it.titleTags() }
                dbQuery { syncSongTitleTags(songs) }
                tagged += songs.count { it.second.isNotEmpty() }
                done += chunk.size
                updateProgress(if (total == 0) 1.0 else done.toDouble() / total, "Songs $done/$total, tagged $tagged")
            }

            logger.info("Backfilled title tag rows for $tagged song(s)")
            mapOf("songs" to total, "tagged" to tagged)
        }
    }
}
