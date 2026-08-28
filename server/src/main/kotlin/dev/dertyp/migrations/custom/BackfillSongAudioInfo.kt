package dev.dertyp.migrations.custom

import dev.dertyp.audio.AudioProbe
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.FlacInfoTable
import dev.dertyp.db.PcmInfoTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File

@Migration("3.12")
class BackfillSongAudioInfo : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill song audio info") {
            val songs = dbQuery {
                SongTable
                    .leftJoin(FlacInfoTable)
                    .leftJoin(PcmInfoTable)
                    .select(SongTable.id, SongTable.filePath, FlacInfoTable.channels, PcmInfoTable.channels)
                    .where { SongTable.channels eq 0 }
                    .toList()
            }
            val variants = dbQuery {
                SongVariantTable
                    .select(SongVariantTable.songId, SongVariantTable.kind, SongVariantTable.path)
                    .where { SongVariantTable.channels eq 0 }
                    .toList()
            }
            val total = songs.size + variants.size
            var done = 0
            var probed = 0
            var failed = 0

            songs.forEach { row ->
                val known = row.getOrNull(FlacInfoTable.channels) ?: row.getOrNull(PcmInfoTable.channels)
                val channels = known ?: AudioProbe.probeChannels(File(row[SongTable.filePath])).also { probed++ }
                if (channels > 0) {
                    dbQuery { SongTable.update({ SongTable.id eq row[SongTable.id] }) { it[SongTable.channels] = channels } }
                } else failed++
                done++
                updateProgress(done.toDouble() / total, "Songs $done/$total, probed $probed, unresolved $failed")
            }

            variants.forEach { row ->
                val info = AudioProbe.probe(File(row[SongVariantTable.path]))
                probed++
                if (info != null) {
                    dbQuery {
                        SongVariantTable.update({
                            (SongVariantTable.songId eq row[SongVariantTable.songId]) and (SongVariantTable.kind eq row[SongVariantTable.kind])
                        }) {
                            it[codec] = info.codec
                            it[sampleRate] = info.sampleRate
                            it[bitsPerSample] = info.bitsPerSample
                            it[channels] = info.channels
                            it[bitRate] = info.bitRate
                            it[fileSize] = info.fileSize
                        }
                    }
                } else failed++
                done++
                updateProgress(done.toDouble() / total, "Variants $done/$total, probed $probed, unresolved $failed")
            }

            mapOf("songs" to songs.size, "variants" to variants.size, "probed" to probed, "unresolved" to failed)
        }
    }
}
