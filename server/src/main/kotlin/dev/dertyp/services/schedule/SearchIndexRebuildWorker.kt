package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.db.SongTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insertIgnore

@WorkerTask(TaskKeys.SEARCH_INDEX_REBUILD_WORKER, "Search Index Rebuild Worker")
class SearchIndexRebuildWorker : Worker("SearchIndexRebuildWorker") {
    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        onProgress(0.0, "Fetching existing songs, albums, and artists...")

        val (songs, albums, artists) = dbQuery {
            Triple(
                SongTable.selectAll().map { it[SongTable.id].value },
                AlbumTable.selectAll().map { it[AlbumTable.id].value },
                ArtistTable.selectAll().map { it[ArtistTable.id].value }
            )
        }

        val total = (songs.size + albums.size + artists.size).toDouble()
        if (total == 0.0) {
            onProgress(100.0, "No entries found to index.")
            return mapOf("queuedSongs" to 0, "queuedAlbums" to 0, "queuedArtists" to 0)
        }

        onProgress(10.0, "Queueing ${songs.size} songs, ${albums.size} albums, and ${artists.size} artists...")

        dbQuery {
            songs.forEach { songId ->
                SearchIndexQueueTable.insertIgnore {
                    it[entityType] = SearchIndexEntityType.SONG
                    it[entityId] = songId
                }
            }
            albums.forEach { albumId ->
                SearchIndexQueueTable.insertIgnore {
                    it[entityType] = SearchIndexEntityType.ALBUM
                    it[entityId] = albumId
                }
            }
            artists.forEach { artistId ->
                SearchIndexQueueTable.insertIgnore {
                    it[entityType] = SearchIndexEntityType.ARTIST
                    it[entityId] = artistId
                }
            }
        }

        onProgress(100.0, "Successfully queued $total items for indexing.")
        return mapOf(
            "queuedSongs" to songs.size,
            "queuedAlbums" to albums.size,
            "queuedArtists" to artists.size
        )
    }
}
