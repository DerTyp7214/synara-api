package dev.dertyp.migrations.custom

import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.CachedMusicBrainzService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.get

@Migration("2.3")
class UpdateAlbumSongCounts : CustomMigration() {
    override suspend fun migrate() {
        val cachedMbService = get<CachedMusicBrainzService>()

        val albumData = dbQuery {
            AlbumMusicBrainzTable
                .select(AlbumMusicBrainzTable.albumId, AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.musicBrainzId.isNotNull() }
                .map { it[AlbumMusicBrainzTable.albumId].value to it[AlbumMusicBrainzTable.musicBrainzId]!!.value }
        }

        logger.info("Found ${albumData.size} albums with MusicBrainz ID to update.")

        albumData.forEachIndexed { index, (albumId, mbId) ->
            if (index % 100 == 0) {
                val progress = (index.toDouble() / albumData.size) * 100
                logger.info("[${progress.toInt()}%] Processing album $index/${albumData.size}")
            }
            try {
                val mbRelease = cachedMbService.getRelease(mbId)
                val trackCount = mbRelease?.media?.sumOf { it.trackCount ?: 0 } ?: 0
                if (trackCount > 0) {
                    dbQuery {
                        AlbumTable.update({ AlbumTable.id eq albumId }) {
                            it[songCount] = trackCount
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to update song count for album $albumId ($mbId)", e)
            }
        }

        logger.info("Finished updating album song counts.")
    }
}
