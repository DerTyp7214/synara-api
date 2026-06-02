package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

@Migration("2.4")
class FillIsrcAndBarcode : CustomMigration() {
    override suspend fun migrate() {
        logTask("Fill ISRC and Barcode") {
            val songsToUpdate: List<Pair<UUID, String>> = dbQuery {
                SongTable
                    .innerJoin(SongMusicBrainzTable, onColumn = { SongTable.id }, otherColumn = { SongMusicBrainzTable.songId })
                    .innerJoin(MBRecordingIsrcTable, onColumn = { SongMusicBrainzTable.musicBrainzId }, otherColumn = { MBRecordingIsrcTable.recordingId })
                    .select(SongTable.id, MBRecordingIsrcTable.isrc)
                    .where { SongTable.isrc.isNull() }
                    .map { it[SongTable.id].value to it[MBRecordingIsrcTable.isrc] }
                    .distinctBy { it.first }
            }

            songsToUpdate.forEachIndexed { index, pair ->
                val (songId, mbIsrc) = pair
                dbQuery {
                    SongTable.update({ SongTable.id eq songId }) {
                        it[isrc] = mbIsrc
                    }
                }
                if (index % 100 == 0 || index == songsToUpdate.lastIndex) {
                    val progress = if (songsToUpdate.isNotEmpty()) (index.toDouble() / songsToUpdate.size / 2.0) else 0.5
                    updateProgress(progress, "Updating ISRCs: ${index + 1}/${songsToUpdate.size}")
                }
            }

            val albumsToUpdate: List<Pair<UUID, String>> = dbQuery {
                AlbumTable
                    .innerJoin(AlbumMusicBrainzTable, onColumn = { AlbumTable.id }, otherColumn = { AlbumMusicBrainzTable.albumId })
                    .innerJoin(MBReleaseTable, onColumn = { AlbumMusicBrainzTable.musicBrainzId }, otherColumn = { MBReleaseTable.id })
                    .select(AlbumTable.id, MBReleaseTable.barcode)
                    .where { AlbumTable.barcode.isNull() and MBReleaseTable.barcode.isNotNull() }
                    .mapNotNull { row ->
                        val barcode = row[MBReleaseTable.barcode]
                        if (barcode != null) row[AlbumTable.id].value to barcode else null
                    }
                    .distinctBy { it.first }
            }

            albumsToUpdate.forEachIndexed { index, pair ->
                val (albumId, mbBarcode) = pair
                dbQuery {
                    AlbumTable.update({ AlbumTable.id eq albumId }) {
                        it[barcode] = mbBarcode.take(32)
                    }
                }
                if (index % 10 == 0 || index == albumsToUpdate.lastIndex) {
                    val progress = if (albumsToUpdate.isNotEmpty()) 0.5 + (index.toDouble() / albumsToUpdate.size / 2.0) else 1.0
                    updateProgress(progress, "Updating Barcodes: ${index + 1}/${albumsToUpdate.size}")
                }
            }

            mapOf("songsUpdated" to songsToUpdate.size, "albumsUpdated" to albumsToUpdate.size)
        }
    }
}
