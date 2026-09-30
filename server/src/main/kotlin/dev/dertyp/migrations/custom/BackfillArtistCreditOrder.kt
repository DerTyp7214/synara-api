package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.TaskContext
import dev.dertyp.core.applyCachedAlbumCreditOrder
import dev.dertyp.core.applyCachedSongCreditOrder
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.logTask
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.SongMusicBrainzTable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select

@Migration("3.24")
class BackfillArtistCreditOrder : CustomMigration() {
    override suspend fun migrate() {
        logTask("Backfill artist credit order") {
            val songIds = dbQuery {
                SongMusicBrainzTable
                    .select(SongMusicBrainzTable.songId)
                    .where { SongMusicBrainzTable.musicBrainzId.isNotNull() }
                    .map { it[SongMusicBrainzTable.songId].value }
            }
            val albumIds = dbQuery {
                AlbumMusicBrainzTable
                    .select(AlbumMusicBrainzTable.albumId)
                    .where { AlbumMusicBrainzTable.musicBrainzId.isNotNull() }
                    .map { it[AlbumMusicBrainzTable.albumId].value }
            }

            val total = songIds.size + albumIds.size
            var processed = 0
            var songCredits = 0
            var albumCredits = 0

            suspend fun TaskContext.advance(count: Int) {
                processed += count
                updateProgress(
                    if (total == 0) 100.0 else processed * 100.0 / total,
                    "Ordered credits of $processed of $total songs and albums"
                )
            }

            songIds.chunked(CHUNK_SIZE).forEach { chunk ->
                songCredits += dbQuery { applyCachedSongCreditOrder(chunk) }
                advance(chunk.size)
            }

            albumIds.chunked(CHUNK_SIZE).forEach { chunk ->
                albumCredits += dbQuery { applyCachedAlbumCreditOrder(chunk) }
                advance(chunk.size)
            }

            log("Ordered $songCredits song and $albumCredits album artist credits from the MusicBrainz cache")

            mapOf("songCredits" to songCredits, "albumCredits" to albumCredits)
        }
    }

    companion object {
        private const val CHUNK_SIZE = 5000
    }
}
