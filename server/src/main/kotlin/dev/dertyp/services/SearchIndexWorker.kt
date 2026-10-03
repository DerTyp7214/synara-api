package dev.dertyp.services

import dev.dertyp.core.logTask
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.core.db.Dialect
import dev.dertyp.core.db.dbQuery
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SearchIndexWorker : KoinComponent {
    private val batchSize = 100
    private val logger = KtorSimpleLogger("SearchIndexWorker")
    private val redisSearchService by inject<RedisSearchService>()

    fun startService(scope: CoroutineScope) {
        scope.launch(Dispatchers.Default) {
            logger.info("SearchIndexWorker background service started")
            while (isActive) {
                try {
                    val queueSize = dbQuery { SearchIndexQueueTable.selectAll().count() }
                    val firstBatch = if (queueSize > 0) fetchBatch() else emptyList()
                    if (firstBatch.isNotEmpty()) {
                        logTask("Search Index Worker") {
                            var processed = 0
                            val total = queueSize.toDouble()
                            var batch = firstBatch
                            while (isActive) {
                                val processedInBatch = processItems(batch)
                                if (processedInBatch == 0) break
                                processed += processedInBatch
                                val progress = (processed / total * 100.0).coerceAtMost(100.0)
                                updateProgress(progress, "Processed $processed / ${total.toInt()} items")
                                batch = fetchBatch()
                            }
                            emptyMap()
                        }
                    } else {
                        delay(2.seconds)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error in SearchIndexWorker loop: ${e.message}")
                    delay(5.seconds)
                }
            }
        }
    }

    private data class QueueItem(val id: Int, val entityType: SearchIndexEntityType, val entityId: UUID)

    internal suspend fun processBatch(): Int = processItems(fetchBatch())

    private suspend fun fetchBatch(): List<QueueItem> = dbQuery {
        SearchIndexQueueTable
            .selectAll()
            .orderBy(SearchIndexQueueTable.id, SortOrder.ASC)
            .limit(batchSize)
            .map {
                QueueItem(
                    it[SearchIndexQueueTable.id],
                    it[SearchIndexQueueTable.entityType],
                    it[SearchIndexQueueTable.entityId]
                )
            }
    }

    private suspend fun processItems(items: List<QueueItem>): Int {
        if (items.isEmpty()) return 0

        val redisEnabled = redisSearchService.isEnabled()

        for (item in items) {
            try {
                val data = dbQuery {
                    when (item.entityType) {
                        SearchIndexEntityType.SONG -> rebuildSongSearchVector(item.entityId, redisEnabled)
                        SearchIndexEntityType.ALBUM -> rebuildAlbumSearchVector(item.entityId, redisEnabled)
                        SearchIndexEntityType.ARTIST -> rebuildArtistSearchVector(item.entityId, redisEnabled)
                    }
                }
                if (data != null) writeToRedis(item.entityType, item.entityId, data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to index ${item.entityType} (${item.entityId}): ${e.message}")
            }
        }

        val idsToDelete = items.map { it.id }
        dbQuery {
            SearchIndexQueueTable.deleteWhere { SearchIndexQueueTable.id inList idsToDelete }
        }

        return idsToDelete.size
    }

    private fun writeToRedis(entityType: SearchIndexEntityType, entityId: UUID, data: Map<String, String>) {
        when (entityType) {
            SearchIndexEntityType.SONG -> redisSearchService.indexSong(
                id = entityId,
                title = data["title"] ?: "",
                artist = data["artist"] ?: "",
                album = data["album"] ?: "",
                metadata = data["metadata"] ?: ""
            )

            SearchIndexEntityType.ARTIST -> redisSearchService.indexArtist(
                id = entityId,
                name = data["name"] ?: "",
                aliases = data["aliases"] ?: "",
                groups = data["groups"] ?: "",
                metadata = data["metadata"] ?: ""
            )

            SearchIndexEntityType.ALBUM -> redisSearchService.indexAlbum(
                entityId,
                data["name"] ?: "",
                data["artists"] ?: "",
                data["groups"] ?: ""
            )
        }
    }

    private fun rebuildSongSearchVector(songId: UUID, redisEnabled: Boolean): Map<String, String>? {
        if (Dialect.current() != Dialect.POSTGRES) return null
        val query = """
            UPDATE song s
            SET search_vector = (
                WITH song_data AS (
                    SELECT 
                        s_inner.id,
                        s_inner.title AS song_title,
                        coalesce((SELECT string_agg(t->>'label', ' ') FROM jsonb_array_elements(coalesce(nullif(s_inner.title_tags, ''), '[]')::jsonb) t), '') AS title_tags,
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
                    GROUP BY s_inner.id, s_inner.title, s_inner.title_tags, alb.name
                )
                SELECT
                    setweight(to_tsvector('simple', song_title), 'A') ||
                    setweight(to_tsvector('simple', title_tags), 'B') ||
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

        return if (redisEnabled) fetchSongData(songId) else null
    }

    private fun rebuildArtistSearchVector(artistId: UUID, redisEnabled: Boolean): Map<String, String>? {
        if (Dialect.current() != Dialect.POSTGRES) return null
        val query = """
            UPDATE artist a
            SET search_vector = (
                WITH artist_data AS (
                    SELECT 
                        a_inner.id,
                        a_inner.name AS artist_name,
                        coalesce(string_agg(DISTINCT art_alias.name, ' '), '') AS artist_aliases,
                        coalesce(string_agg(DISTINCT grp.name, ' '), '') AS group_names,
                        coalesce(string_agg(DISTINCT mem.name, ' '), '') AS member_names,
                        coalesce(string_agg(DISTINCT mb_art.name, ' '), '') AS mb_art_names,
                        coalesce(string_agg(DISTINCT mb_art_alias.name, ' '), '') AS mb_art_aliases,
                        coalesce(string_agg(DISTINCT mb_art.disambiguation, ' '), '') AS mb_art_disambig
                    FROM artist a_inner
                    LEFT JOIN artistalias art_alias ON a_inner.id = art_alias."artistId"
                    LEFT JOIN artist_member am_grp ON a_inner.id = am_grp."artistId"
                    LEFT JOIN artist grp ON am_grp."groupId" = grp.id
                    LEFT JOIN artist_member am_mem ON a_inner.id = am_mem."groupId"
                    LEFT JOIN artist mem ON am_mem."artistId" = mem.id
                    LEFT JOIN artist_musicbrainz art_mb ON a_inner.id = art_mb."artistId"
                    LEFT JOIN mb_artist mb_art ON art_mb."musicBrainzId" = mb_art.id
                    LEFT JOIN mb_artist_alias mb_art_alias ON mb_art.id = mb_art_alias."artistId"
                    WHERE a_inner.id = a.id
                    GROUP BY a_inner.id, a_inner.name
                )
                SELECT 
                    setweight(to_tsvector('simple', artist_name), 'A') ||
                    setweight(to_tsvector('simple', artist_aliases), 'B') ||
                    setweight(to_tsvector('simple', group_names || ' ' || member_names), 'C') ||
                    setweight(to_tsvector('simple', 
                        mb_art_names || ' ' || 
                        mb_art_aliases || ' ' || 
                        mb_art_disambig
                    ), 'D')
                FROM artist_data
            )
            WHERE a.id = ?
        """.trimIndent()

        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to artistId))

        return if (redisEnabled) fetchArtistData(artistId) else null
    }

    private fun rebuildAlbumSearchVector(albumId: UUID, redisEnabled: Boolean): Map<String, String>? {
        if (Dialect.current() != Dialect.POSTGRES) return null
        val query = """
            UPDATE album alb
            SET search_vector = (
                WITH album_data AS (
                    SELECT 
                        alb_inner.id,
                        alb_inner.name AS album_name,
                        coalesce(string_agg(DISTINCT art.name, ' '), '') AS artist_names,
                        coalesce(string_agg(DISTINCT art_alias.name, ' '), '') AS artist_aliases,
                        coalesce(string_agg(DISTINCT grp.name, ' '), '') AS group_names,
                        coalesce(string_agg(DISTINCT mem.name, ' '), '') AS member_names,
                        coalesce(string_agg(DISTINCT mb_rel.title, ' '), '') AS mb_rel_titles,
                        coalesce(string_agg(DISTINCT mb_rel.disambiguation, ' '), '') AS mb_rel_disambig,
                        coalesce(string_agg(DISTINCT mb_art.name, ' '), '') AS mb_art_names,
                        coalesce(string_agg(DISTINCT mb_art_alias.name, ' '), '') AS mb_art_aliases,
                        coalesce(string_agg(DISTINCT mb_art.disambiguation, ' '), '') AS mb_art_disambig
                    FROM album alb_inner
                    LEFT JOIN albumartist aa ON alb_inner.id = aa."albumId"
                    LEFT JOIN artist art ON aa."artistId" = art.id
                    LEFT JOIN artistalias art_alias ON art.id = art_alias."artistId"
                    LEFT JOIN artist_member am_grp ON art.id = am_grp."artistId"
                    LEFT JOIN artist grp ON am_grp."groupId" = grp.id
                    LEFT JOIN artist_member am_mem ON art.id = am_mem."groupId"
                    LEFT JOIN artist mem ON am_mem."artistId" = mem.id
                    LEFT JOIN album_musicbrainz amb ON alb_inner.id = amb."albumId"
                    LEFT JOIN mb_release mb_rel ON amb."musicBrainzId" = mb_rel.id
                    LEFT JOIN artist_musicbrainz art_mb ON art.id = art_mb."artistId"
                    LEFT JOIN mb_artist mb_art ON art_mb."musicBrainzId" = mb_art.id
                    LEFT JOIN mb_artist_alias mb_art_alias ON mb_art.id = mb_art_alias."artistId"
                    WHERE alb_inner.id = alb.id
                    GROUP BY alb_inner.id, alb_inner.name
                )
                SELECT 
                    setweight(to_tsvector('simple', album_name), 'A') ||
                    setweight(to_tsvector('simple', artist_names || ' ' || artist_aliases || ' ' || mb_rel_titles || ' ' || mb_art_names || ' ' || mb_art_aliases), 'B') ||
                    setweight(to_tsvector('simple', group_names || ' ' || member_names || ' ' || mb_rel_disambig || ' ' || mb_art_disambig), 'C')
                FROM album_data
            )
            WHERE alb.id = ?
        """.trimIndent()

        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to albumId))

        return if (redisEnabled) fetchAlbumData(albumId) else null
    }

    private fun fetchSongData(songId: UUID): Map<String, String>? {
        val query = """
            SELECT 
                s_inner.title AS song_title,
                coalesce((SELECT string_agg(t->>'label', ' ') FROM jsonb_array_elements(coalesce(nullif(s_inner.title_tags, ''), '[]')::jsonb) t), '') AS title_tags,
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
            WHERE s_inner.id = ?
            GROUP BY s_inner.id, s_inner.title, s_inner.title_tags, alb.name
        """.trimIndent()

        var result: Map<String, String>? = null
        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to songId)) { rs ->
            if (rs.next()) {
                val metadataParts = listOf(
                    rs.getString("artist_aliases"),
                    rs.getString("mb_rec_titles"),
                    rs.getString("mb_rel_titles"),
                    rs.getString("mb_rel_disambig"),
                    rs.getString("mb_art_names"),
                    rs.getString("mb_art_aliases"),
                    rs.getString("group_names"),
                    rs.getString("member_names")
                ).filter { !it.isNullOrBlank() }

                val titleParts = listOf(
                    rs.getString("song_title"),
                    rs.getString("title_tags")
                ).filter { !it.isNullOrBlank() }

                result = mapOf(
                    "title" to titleParts.joinToString(" "),
                    "artist" to (rs.getString("artist_names") ?: ""),
                    "album" to (rs.getString("album_name") ?: ""),
                    "metadata" to metadataParts.joinToString(" ")
                )
            }
        }
        return result
    }

    private fun fetchArtistData(artistId: UUID): Map<String, String>? {
        val query = """
            SELECT 
                a_inner.name AS artist_name,
                coalesce(string_agg(DISTINCT art_alias.name, ' '), '') AS artist_aliases,
                coalesce(string_agg(DISTINCT grp.name, ' '), '') AS group_names,
                coalesce(string_agg(DISTINCT mem.name, ' '), '') AS member_names,
                coalesce(string_agg(DISTINCT mb_art.name, ' '), '') AS mb_art_names,
                coalesce(string_agg(DISTINCT mb_art_alias.name, ' '), '') AS mb_art_aliases,
                coalesce(string_agg(DISTINCT mb_art.disambiguation, ' '), '') AS mb_art_disambig
            FROM artist a_inner
            LEFT JOIN artistalias art_alias ON a_inner.id = art_alias."artistId"
            LEFT JOIN artist_member am_grp ON a_inner.id = am_grp."artistId"
            LEFT JOIN artist grp ON am_grp."groupId" = grp.id
            LEFT JOIN artist_member am_mem ON a_inner.id = am_mem."groupId"
            LEFT JOIN artist mem ON am_mem."artistId" = mem.id
            LEFT JOIN artist_musicbrainz art_mb ON a_inner.id = art_mb."artistId"
            LEFT JOIN mb_artist mb_art ON art_mb."musicBrainzId" = mb_art.id
            LEFT JOIN mb_artist_alias mb_art_alias ON mb_art.id = mb_art_alias."artistId"
            WHERE a_inner.id = ?
            GROUP BY a_inner.id, a_inner.name
        """.trimIndent()

        var result: Map<String, String>? = null
        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to artistId)) { rs ->
            if (rs.next()) {
                val metadataParts = listOf(
                    rs.getString("mb_art_names"),
                    rs.getString("mb_art_aliases"),
                    rs.getString("mb_art_disambig")
                ).filter { !it.isNullOrBlank() }

                result = mapOf(
                    "name" to (rs.getString("artist_name") ?: ""),
                    "aliases" to (rs.getString("artist_aliases") ?: ""),
                    "groups" to listOf(
                        rs.getString("group_names"),
                        rs.getString("member_names")
                    ).filter { !it.isNullOrBlank() }.joinToString(" "),
                    "metadata" to metadataParts.joinToString(" ")
                )
            }
        }
        return result
    }

    private fun fetchAlbumData(albumId: UUID): Map<String, String>? {
        val query = """
            SELECT 
                alb_inner.name AS album_name,
                coalesce(string_agg(DISTINCT art.name, ' '), '') AS artist_names,
                coalesce(string_agg(DISTINCT art_alias.name, ' '), '') AS artist_aliases,
                coalesce(string_agg(DISTINCT grp.name, ' '), '') AS group_names,
                coalesce(string_agg(DISTINCT mem.name, ' '), '') AS member_names,
                coalesce(string_agg(DISTINCT mb_rel.title, ' '), '') AS mb_rel_titles,
                coalesce(string_agg(DISTINCT mb_rel.disambiguation, ' '), '') AS mb_rel_disambig,
                coalesce(string_agg(DISTINCT mb_art.name, ' '), '') AS mb_art_names,
                coalesce(string_agg(DISTINCT mb_art_alias.name, ' '), '') AS mb_art_aliases,
                coalesce(string_agg(DISTINCT mb_art.disambiguation, ' '), '') AS mb_art_disambig
            FROM album alb_inner
            LEFT JOIN albumartist aa ON alb_inner.id = aa."albumId"
            LEFT JOIN artist art ON aa."artistId" = art.id
            LEFT JOIN artistalias art_alias ON art.id = art_alias."artistId"
            LEFT JOIN artist_member am_grp ON art.id = am_grp."artistId"
            LEFT JOIN artist grp ON am_grp."groupId" = grp.id
            LEFT JOIN artist_member am_mem ON art.id = am_mem."groupId"
            LEFT JOIN artist mem ON am_mem."artistId" = mem.id
            LEFT JOIN album_musicbrainz amb ON alb_inner.id = amb."albumId"
            LEFT JOIN mb_release mb_rel ON amb."musicBrainzId" = mb_rel.id
            LEFT JOIN artist_musicbrainz art_mb ON art.id = art_mb."artistId"
            LEFT JOIN mb_artist mb_art ON art_mb."musicBrainzId" = mb_art.id
            LEFT JOIN mb_artist_alias mb_art_alias ON mb_art.id = mb_art_alias."artistId"
            WHERE alb_inner.id = ?
            GROUP BY alb_inner.id, alb_inner.name
        """.trimIndent()

        var result: Map<String, String>? = null
        TransactionManager.current().exec(query, args = listOf(UUIDColumnType() to albumId)) { rs ->
            if (rs.next()) {
                val artistParts = listOf(
                    rs.getString("artist_names"),
                    rs.getString("artist_aliases"),
                    rs.getString("mb_rel_titles"),
                    rs.getString("mb_art_names"),
                    rs.getString("mb_art_aliases")
                ).filter { !it.isNullOrBlank() }

                val groupParts = listOf(
                    rs.getString("group_names"),
                    rs.getString("member_names"),
                    rs.getString("mb_rel_disambig"),
                    rs.getString("mb_art_disambig")
                ).filter { !it.isNullOrBlank() }

                result = mapOf(
                    "name" to (rs.getString("album_name") ?: ""),
                    "artists" to artistParts.joinToString(" "),
                    "groups" to groupParts.joinToString(" ")
                )
            }
        }
        return result
    }
}
