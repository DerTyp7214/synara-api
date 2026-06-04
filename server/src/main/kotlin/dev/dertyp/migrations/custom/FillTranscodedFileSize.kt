package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.TranscodedSongTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File

@Migration("1.45")
class FillTranscodedFileSize : CustomMigration() {
    override suspend fun migrate() {
        val transcodedSongs = dbQuery {
            TranscodedSongTable.selectAll().toList()
        }

        dbQuery {
            for (row in transcodedSongs) {
                val path = row[TranscodedSongTable.path]
                val file = File(path)
                if (file.exists() && file.isFile) {
                    val size = file.length()
                    TranscodedSongTable.update({
                        (TranscodedSongTable.songId eq row[TranscodedSongTable.songId]) and
                                (TranscodedSongTable.bitrate eq row[TranscodedSongTable.bitrate]) and
                                (TranscodedSongTable.format eq row[TranscodedSongTable.format])
                    }) {
                        it[fileSize] = size
                    }
                }
            }
        }
    }
}
