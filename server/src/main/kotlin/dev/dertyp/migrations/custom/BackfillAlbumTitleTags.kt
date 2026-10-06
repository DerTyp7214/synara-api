package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.classifyAlbumTitleTag
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.logTask
import dev.dertyp.core.mergeTitleTags
import dev.dertyp.core.splitAlbumTitleTags
import dev.dertyp.data.TitleTag
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.db.albumTitleTags
import dev.dertyp.db.encodeTitleTags
import dev.dertyp.db.syncAlbumTitleTags
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update

private const val CHUNK_SIZE = 1000

@Migration("3.29")
class BackfillAlbumTitleTags : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill album title tags") {
            val rows = dbQuery {
                AlbumTable
                    .leftJoin(
                        AlbumMusicBrainzTable,
                        onColumn = { AlbumTable.id },
                        otherColumn = { AlbumMusicBrainzTable.albumId })
                    .leftJoin(
                        MBReleaseTable,
                        onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                        otherColumn = { MBReleaseTable.id })
                    .select(AlbumTable.id, AlbumTable.name, AlbumTable.titleTags, MBReleaseTable.disambiguation)
                    .toList()
                    .distinctBy { it[AlbumTable.id] }
            }

            val total = rows.size
            var done = 0
            var updated = 0

            for (chunk in rows.chunked(CHUNK_SIZE)) {
                dbQuery {
                    for (row in chunk) {
                        val stored = row[AlbumTable.name]
                        val existing = row.albumTitleTags()
                        val disambiguation = row.getOrNull(MBReleaseTable.disambiguation)?.trim().orEmpty()
                        val editionTags = listOfNotNull(
                            classifyAlbumTitleTag(disambiguation)?.let { TitleTag(it, disambiguation) }
                        )
                        val split = stored.splitAlbumTitleTags()
                        val tags = existing.mergeTitleTags(editionTags).mergeTitleTags(split.tags)
                        if (split.title == stored && tags == existing) continue

                        AlbumTable.update({ AlbumTable.id eq row[AlbumTable.id] }) {
                            it[name] = split.title
                            it[titleTags] = encodeTitleTags(tags)
                        }
                        syncAlbumTitleTags(row[AlbumTable.id].value, tags)
                        updated++
                    }
                }
                done += chunk.size
                updateProgress(if (total == 0) 1.0 else done.toDouble() / total, "Albums $done/$total, updated $updated")
            }

            logger.info("Backfilled title tags for $updated album(s)")
            mapOf("albums" to total, "updated" to updated)
        }
    }
}
