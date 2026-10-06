package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.paging
import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.data.EntityType
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.data.PlaylistEntry
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.fullSongTitle
import dev.dertyp.core.db.dbQuery
import dev.dertyp.plugins.PlaylistLibrary
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.UUID

class PlaylistService : PlaylistLibrary, IPlaylistService, Service() {
    private val imageService by inject<ImageService>()
    private val redisSearchService by inject<RedisSearchService>()
    private val entityChangeRecorder by inject<EntityChangeRecorder>()

    companion object {
        fun mapPlaylist(resultRow: ResultRow): Playlist {
            val id = resultRow[PlaylistTable.id].value
            val name = resultRow[PlaylistTable.name]
            val imageId = resultRow[PlaylistTable.imageId]?.value
            val blurHash = resultRow.getOrNull(ImageTable.blurHash)

            return Playlist(
                id = id,
                name = name,
                songs = emptyList(),
                imageId = imageId,
                blurHash = blurHash,
            )
        }
    }

    fun map(resultRow: ResultRow): Playlist = mapPlaylist(resultRow)

    override suspend fun byId(id: UUID): Playlist? = querySingle {
        where { PlaylistTable.id eq id }
    }

    override suspend fun byIds(@LogParam("size") ids: List<UUID>): List<Playlist> = queryPlaylists(0, Int.MAX_VALUE) {
        where { PlaylistTable.id inList ids }
    }.let { response ->
        val playlistMap = response.data.associateBy { it.id }
        ids.mapNotNull { playlistMap[it] }
    }

    override suspend fun byIdFull(id: UUID): Pair<String, List<PlaylistEntry>>? = dbQuery {
        val rows = PlaylistTable
            .leftJoin(
                PlaylistSongTable,
                onColumn = { PlaylistTable.id },
                otherColumn = { PlaylistSongTable.playlistId })
            .leftJoin(
                SongTable,
                onColumn = { PlaylistSongTable.songId },
                otherColumn = { SongTable.id }
            )
            .select(
                PlaylistTable.name,
                PlaylistSongTable.position,
                PlaylistSongTable.songId,
                SongTable.title,
                SongTable.titleTags,
                SongTable.duration
            )
            .where { PlaylistTable.id eq id }
            .toList()

        if (rows.isEmpty()) return@dbQuery null

        mapFullEagerly(rows)
    }

    override suspend fun byName(name: String): Playlist? = querySingle {
        where { PlaylistTable.name eq name }
    }

    override suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Playlist> =
        queryPlaylists(page, pageSize) {
            rankedSearchQuery(
                redisSearchService,
                query,
                listOf(10),
                listOf(PlaylistTable.name),
                PlaylistTable.id
            ).query
        }

    override suspend fun allPlaylists(page: Int, pageSize: Int): PaginatedResponse<Playlist> =
        queryPlaylists(page, pageSize)

    fun allPlaylistsFlow(): Flow<Playlist> = flow {
        val total = allPlaylists(0, 0).total
        var page = 0
        val pageSize = 100
        while (page * pageSize < total) {
            allPlaylists(page, pageSize).data.forEach { emit(it) }
            page++
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun delete(id: UUID): Boolean = dbQuery {
        if (PlaylistTable.select(PlaylistTable.id).where { PlaylistTable.id eq id }.empty()) return@dbQuery false
        entityChangeRecorder.deleting(EntityType.PLAYLIST, listOf(id))
        PlaylistTable.deleteWhere { PlaylistTable.id eq id } == 1
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryPlaylists(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryPlaylists(page: Int, pageSize: Int, query: Query.() -> Query = { this }) =
        dbQuery {
            val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
            val mainQuery = PlaylistTable
                .leftJoin(ImageTable, onColumn = { PlaylistTable.imageId }, otherColumn = { ImageTable.id })
                .selectAll()
                .query()
            val countExpression = PlaylistTable.id.countDistinct()
            val total = if (pageSize == Int.MAX_VALUE) null else Query(
                Slice(mainQuery.set.source, listOf(countExpression)),
                mainQuery.where
            )
                .first()[countExpression]
                .toInt()
            val mainPlaylistRows = mainQuery
                .paging(page, pageSize, offset)
                .toList()

            if (mainPlaylistRows.isEmpty()) return@dbQuery PaginatedResponse(
                data = listOf(),
                total = total ?: 0,
                page = page,
                pageSize = pageSize
            )

            val playlistIds = mainPlaylistRows.map { it[PlaylistTable.id].value }

            val songLinkRows = PlaylistSongTable
                .select(PlaylistSongTable.playlistId, PlaylistSongTable.songId, PlaylistSongTable.position)
                .where { PlaylistSongTable.playlistId inList playlistIds }
                .toList()

            val songIds = songLinkRows.map { it[PlaylistSongTable.songId].value }.distinct()

            val songDurationsById = if (songIds.isNotEmpty()) {
                getSongDurations(songIds)
            } else {
                emptyMap()
            }

            val data = mapEagerly(mainPlaylistRows, songLinkRows, songDurationsById)

            PaginatedResponse(
                data = data.take(pageSize),
                total = total ?: data.size,
                page = page,
                pageSize = pageSize,
                hasNextPage = data.size >= pageSize + offset,
            )
        }

    private suspend fun getSongDurations(songIds: List<UUID>): Map<UUID, Long> = dbQuery {
        SongTable
            .select(SongTable.id, SongTable.duration)
            .where { SongTable.id inList songIds }
            .associate { row ->
                row[SongTable.id].value to row[SongTable.duration]
            }
    }

    private fun mapEagerly(
        mainRows: List<ResultRow>,
        songLinkRows: List<ResultRow>,
        songDurationsById: Map<UUID, Long>
    ): List<Playlist> {
        val songsByPlaylistId = songLinkRows
            .map { row ->
                row[PlaylistSongTable.playlistId].value to
                        Pair(row[PlaylistSongTable.songId].value, row[PlaylistSongTable.position])
            }
            .groupBy({ it.first }, { it.second })

        return mainRows.map { playlistRow ->
            val playlist = map(playlistRow)
            val links = songsByPlaylistId[playlist.id] ?: listOf()

            val totalDuration = links
                .sumOf { (songId, _) ->
                    songDurationsById[songId] ?: 0L
                }.takeIf { it > 0L } ?: -1L

            val songs = songsByPlaylistId[playlist.id]
                ?.sortedBy { it.second }
                ?.map { it.first }
                ?: listOf()

            playlist.copy(
                songs = songs,
                totalDuration = totalDuration,
            )
        }
    }

    private fun mapFullEagerly(rows: List<ResultRow>): Pair<String, List<PlaylistEntry>> {
        val playlistName = rows.first()[PlaylistTable.name]

        val songEntriesWithPosition = rows
            .mapNotNull { row ->
                val songId = row.getOrNull(PlaylistSongTable.songId)?.value ?: return@mapNotNull null

                Pair(
                    row[PlaylistSongTable.position],
                    PlaylistEntry(
                        id = songId,
                        name = row.fullSongTitle(),
                        duration = row[SongTable.duration]
                    )
                )
            }

        val sortedEntries = songEntriesWithPosition
            .sortedBy { it.first }
            .map { it.second }

        return Pair(playlistName, sortedEntries)
    }

    override suspend fun createBatch(playlists: List<InsertablePlaylist>, userId: PlatformUUID?): List<PlatformUUID> =
        dbQuery {
            if (playlists.isEmpty()) return@dbQuery emptyList()

            val allUniqueImageHashes = playlists.mapNotNull { it.imageHash }.distinct()
            val allUniqueSongPaths = playlists.flatMap { it.songPaths }.distinct()

            val idsByName = PlaylistTable
                .select(PlaylistTable.id, PlaylistTable.name)
                .where { PlaylistTable.name inList playlists.map { it.name } }
                .groupBy({ it[PlaylistTable.name] }, { it[PlaylistTable.id].value })
            val idByName = idsByName.mapValuesTo(mutableMapOf()) { (_, ids) -> ids.minBy(UUID::toString) }
            val duplicates = idsByName.flatMap { (name, ids) -> ids - idByName.getValue(name) }
            val playlistIds = playlists.map { idByName.getOrPut(it.name) { UUID.randomUUID() } }
            val incoming = playlists.associateBy { idByName.getValue(it.name) }

            val imageIdMap: Map<String, UUID> = imageService.getCoverHashes(allUniqueImageHashes)

            val songIdByPath: Map<String, UUID> = SongTable
                .select(SongTable.id, SongTable.filePath)
                .where { SongTable.filePath inList allUniqueSongPaths }
                .associate { it[SongTable.filePath] to it[SongTable.id].value }

            val playlistSongLinks = incoming.flatMap { (playlistId, playlistData) ->
                var position = 1
                playlistData.songPaths.mapNotNull { songPath ->
                    songIdByPath[songPath]?.let { Triple(playlistId, it, position++) }
                }
            }.distinctBy { listOf(it.first, it.second) }

            if (duplicates.isNotEmpty()) {
                entityChangeRecorder.deleting(EntityType.PLAYLIST, duplicates)
                PlaylistTable.deleteWhere { PlaylistTable.id inList duplicates }
            }

            val before = entityStates(EntityType.PLAYLIST, incoming.keys)
            PlaylistTable.batchUpsert(
                incoming.entries,
                PlaylistTable.id,
                shouldReturnGeneratedValues = false
            ) { (playlistId, playlist) ->
                this[PlaylistTable.id] = playlistId
                this[PlaylistTable.name] = playlist.name
                this[PlaylistTable.imageId] = playlist.imageHash?.let { imageIdMap[it] }
            }
            PlaylistSongTable.deleteWhere { PlaylistSongTable.playlistId inList incoming.keys }
            PlaylistSongTable.batchInsert(playlistSongLinks) { (playlistId, songId, position) ->
                this[PlaylistSongTable.playlistId] = playlistId
                this[PlaylistSongTable.songId] = songId
                this[PlaylistSongTable.position] = position
            }
            entityChangeRecorder.recordChanges(before)

            playlistIds
        }

    override suspend fun getOrAddPlaylist(
        userId: PlatformUUID,
        customIdentifier: String?,
        playlist: InsertablePlaylist
    ): UUID {
        val image = playlist.imageHash?.let { hash -> imageService.byHash(hash)?.id }
        return dbQuery {
            val existingId = PlaylistTable
                .select(PlaylistTable.id)
                .where { PlaylistTable.name eq playlist.name }
                .singleOrNull()?.get(PlaylistTable.id)?.value

            if (existingId != null) return@dbQuery existingId

            PlaylistTable.insertAndGetId {
                it[name] = playlist.name
                it[imageId] = image
            }.value.also { entityChangeRecorder.created(EntityType.PLAYLIST, listOf(it)) }
        }
    }

    override suspend fun addToPlaylist(id: UUID, songIds: List<Pair<Long, UUID>>): Unit = dbQuery {
        val lastPosition = PlaylistSongTable
            .select(PlaylistSongTable.position)
            .where { PlaylistSongTable.playlistId eq id }
            .maxOfOrNull { it[PlaylistSongTable.position] } ?: 0

        var currentPosition = lastPosition + 1
        PlaylistSongTable.batchInsert(songIds) { (_, songId) ->
            this[PlaylistSongTable.playlistId] = id
            this[PlaylistSongTable.songId] = songId
            this[PlaylistSongTable.position] = currentPosition++
        }
        if (songIds.isNotEmpty()) entityChangeRecorder.membersChanged(EntityType.PLAYLIST, listOf(id))
    }

    suspend fun upsertPlaylist(playlist: Playlist) = dbQuery {
        val before = entityStates(EntityType.PLAYLIST, listOf(playlist.id))
        PlaylistTable.upsert(PlaylistTable.id) {
            it[id] = playlist.id
            it[name] = playlist.name
            it[imageId] = playlist.imageId?.let { imgId -> EntityID(imgId, ImageTable) }
        }

        PlaylistSongTable.deleteWhere { PlaylistSongTable.playlistId eq playlist.id }
        var position = 1
        PlaylistSongTable.batchInsert(playlist.songs) { songId ->
            this[PlaylistSongTable.playlistId] = playlist.id
            this[PlaylistSongTable.songId] = songId
            this[PlaylistSongTable.position] = position++
        }
        entityChangeRecorder.recordChanges(before)
    }
}
