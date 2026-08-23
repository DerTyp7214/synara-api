package dev.dertyp.migrations.custom

import dev.dertyp.audio.LosslessFormat
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

@Migration("3.8")
class BackfillSongFormat : CustomMigration() {
    override suspend fun migrate() {
        val rows = dbQuery {
            SongTable.select(SongTable.id, SongTable.filePath, SongTable.format).toList()
        }

        var updated = 0
        dbQuery {
            for (row in rows) {
                val ext = row[SongTable.filePath].substringAfterLast('.', "").lowercase()
                val format = LosslessFormat.fromExtension(ext)?.extension ?: ext.take(8)
                if (format.isBlank() || format == row[SongTable.format]) continue
                SongTable.update({ SongTable.id eq row[SongTable.id] }) { it[SongTable.format] = format }
                updated++
            }
        }
        logger.info("Backfilled format for $updated song(s)")
    }
}
