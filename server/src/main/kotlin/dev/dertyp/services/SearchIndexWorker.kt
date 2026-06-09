package dev.dertyp.services

import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.dbQuery
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SearchIndexWorker {
    private val batchSize = 100
    private val logger = KtorSimpleLogger("SearchIndexWorker")

    fun startService(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            logger.info("SearchIndexWorker background service started")
            while (isActive) {
                try {
                    val processedCount = processBatch()
                    if (processedCount == 0) {
                        delay(2.seconds)
                    }
                } catch (e: Exception) {
                    logger.error("Error in SearchIndexWorker loop: ${e.message}")
                    delay(5.seconds)
                }
            }
        }
    }

    internal suspend fun processBatch(): Int = dbQuery {
        val items = SearchIndexQueueTable
            .selectAll()
            .limit(batchSize)
            .toList()

        if (items.isEmpty()) return@dbQuery 0

        for (item in items) {
            val entityType = item[SearchIndexQueueTable.entityType]
            val entityId = item[SearchIndexQueueTable.entityId]

            try {
                when (entityType) {
                    SearchIndexEntityType.SONG -> rebuildSongSearchVector(entityId)
                    SearchIndexEntityType.ALBUM -> rebuildAlbumSearchVector(entityId)
                    SearchIndexEntityType.ARTIST -> rebuildArtistSearchVector(entityId)
                }
            } catch (e: Exception) {
                logger.error("Failed to index $entityType ($entityId): ${e.message}")
            }
        }

        val idsToDelete = items.map { it[SearchIndexQueueTable.id] }
        SearchIndexQueueTable.deleteWhere { SearchIndexQueueTable.id inList idsToDelete }
        
        idsToDelete.size
    }

    private fun rebuildSongSearchVector(songId: UUID) {
        if (currentDialect !is PostgreSQLDialect) return
        val query = """
            UPDATE song s
            SET search_vector = (
                WITH song_data AS (
                    SELECT 
                        s_inner.id,
                        s_inner.title AS song_title,
                        coalesce(alb.name, '') AS album_name,
                        coalesce(string_agg(DISTINCT art.name, ' '), '') AS artist_names,
                        coalesce(string_agg(DISTINCT art_alias.name, ' '), '') AS artist_aliases,
                        coalesce(string_agg(DISTINCT mb_rec.title, ' '), '') AS mb_rec_titles,
                        coalesce(string_agg(DISTINCT mb_rel.title, ' '), '') AS mb_rel_titles,
                        coalesce(string_agg(DISTINCT mb_rel.disambiguation, ' '), '') AS mb_rel_disambig,
                        coalesce(string_agg(DISTINCT mb_art.name, ' '), '') AS mb_art_names,
                        coalesce(string_agg(DISTINCT mb_art_alias.name, ' '), '') AS mb_art_aliases,
                        coalesce(string_agg(DISTINCT grp.name, ' '), '') AS group_names,
                        coalesce(string_agg(DISTINCT mem.name, ' '), '') AS member_names
                    FROM song s_inner
                    LEFT JOIN album alb ON s_inner."albumId" = alb.id
                    LEFT JOIN songartist sa ON s_inner.id = sa."songId"
                    LEFT JOIN artist art ON sa."artistId" = art.id
                    LEFT JOIN artistalias art_alias ON art.id = art_alias."artistId"
                    LEFT JOIN artist_member am_grp ON art.id = am_grp."artistId"
                    LEFT JOIN artist grp ON am_grp."groupId" = grp.id
                    LEFT JOIN artist_member am_mem ON art.id = am_mem."groupId"
                    LEFT JOIN artist mem ON am_mem."artistId" = mem.id
                    LEFT JOIN song_musicbrainz smb ON s_inner.id = smb."songId"
                    LEFT JOIN mb_recording mb_rec ON smb."musicBrainzId" = mb_rec.id
                    LEFT JOIN album_musicbrainz amb ON alb.id = amb."albumId"
                    LEFT JOIN mb_release mb_rel ON amb."musicBrainzId" = mb_rel.id
                    LEFT JOIN artist_musicbrainz art_mb ON art.id = art_mb."artistId"
                    LEFT JOIN mb_artist mb_art ON art_mb."musicBrainzId" = mb_art.id
                    LEFT JOIN mb_artist_alias mb_art_alias ON mb_art.id = mb_art_alias."artistId"
                    WHERE s_inner.id = s.id
                    GROUP BY s_inner.id, s_inner.title, alb.name
                )
                SELECT 
                    setweight(to_tsvector('simple', song_title), 'A') ||
                    setweight(to_tsvector('simple', artist_names), 'B') ||
                    setweight(to_tsvector('simple', album_name), 'C') ||
                    setweight(to_tsvector('simple', 
                        artist_aliases || ' ' || 
                        mb_rec_titles || ' ' || 
                        mb_rel_titles || ' ' || 
                        mb_rel_disambig || ' ' || 
                        mb_art_names || ' ' || 
                        mb_art_aliases || ' ' || 
                        group_names || ' ' || 
                        member_names
                    ), 'D')
                FROM song_data
            )
            WHERE s.id = ?
        """.trimIndent()
        
        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to songId))
    }

    private fun rebuildArtistSearchVector(artistId: UUID) {
        if (currentDialect !is PostgreSQLDialect) return
        val query = """
            UPDATE artist a
            SET search_vector = (
                SELECT 
                    setweight(to_tsvector('simple', coalesce(a_inner.name, '')), 'A') ||
                    setweight(to_tsvector('simple', coalesce(string_agg(DISTINCT art_alias.name, ' '), '')), 'B')
                FROM artist a_inner
                LEFT JOIN artistalias art_alias ON a_inner.id = art_alias."artistId"
                WHERE a_inner.id = a.id
                GROUP BY a_inner.id, a_inner.name
            )
            WHERE a.id = ?
        """.trimIndent()

        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to artistId))
    }

    private fun rebuildAlbumSearchVector(albumId: UUID) {
        if (currentDialect !is PostgreSQLDialect) return
        val query = """
            UPDATE album alb
            SET search_vector = (
                SELECT 
                    setweight(to_tsvector('simple', coalesce(alb_inner.name, '')), 'A') ||
                    setweight(to_tsvector('simple', coalesce(string_agg(DISTINCT art.name, ' '), '')), 'B')
                FROM album alb_inner
                LEFT JOIN albumartist aa ON alb_inner.id = aa."albumId"
                LEFT JOIN artist art ON aa."artistId" = art.id
                WHERE alb_inner.id = alb.id
                GROUP BY alb_inner.id, alb_inner.name
            )
            WHERE alb.id = ?
        """.trimIndent()

        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to albumId))
    }
}
