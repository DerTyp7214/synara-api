package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.core.mergeTitleTags
import dev.dertyp.core.splitTitleTags
import dev.dertyp.db.SongTable
import dev.dertyp.db.encodeTitleTags
import dev.dertyp.db.titleTags
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

private const val EXPLICIT_MARKER = "🅴"
private const val CHUNK_SIZE = 1000

@Migration("3.17")
class BackfillSongTitleTags : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill song title tags") {
            val rows = dbQuery {
                SongTable.select(SongTable.id, SongTable.title, SongTable.titleTags).toList()
            }

            val total = rows.size
            var done = 0
            var updated = 0

            for (chunk in rows.chunked(CHUNK_SIZE)) {
                dbQuery {
                    for (row in chunk) {
                        val raw = row[SongTable.title]
                        val existing = row.titleTags()
                        val stripped = raw.replace(EXPLICIT_MARKER, "").trim()
                        val split = stripped.splitTitleTags()
                        val tags = existing.mergeTitleTags(split.tags)
                        if (split.title == raw && tags == existing) continue

                        SongTable.update({ SongTable.id eq row[SongTable.id] }) {
                            it[title] = split.title
                            it[titleTags] = encodeTitleTags(tags)
                        }
                        updated++
                    }
                }
                done += chunk.size
                updateProgress(if (total == 0) 1.0 else done.toDouble() / total, "Songs $done/$total, updated $updated")
            }

            logger.info("Backfilled title tags for $updated song(s)")
            mapOf("songs" to total, "updated" to updated)
        }
    }
}
