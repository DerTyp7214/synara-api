package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.data.PlaylistEntry
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import java.util.UUID

class PlaylistService : IPlaylistService, Service() {
    companion object {
        fun mapPlaylist(resultRow: ResultRow): Playlist {
            val id = resultRow[PlaylistTable.id].value
            val name = resultRow[PlaylistTable.name]
            val imageId = resultRow[PlaylistTable.imageId]?.value

            return Playlist(
                id = id,
                name = name,
                songs = emptyList(),
                imageId = imageId,
            )
        }
    }

    fun map(resultRow: ResultRow): Playlist = mapPlaylist(resultRow)

    override suspend fun byId(id: UUID): Playlist? = querySingle {
        where { PlaylistTable.id eq id }
    }

    override suspend fun byIds(ids: List<UUID>): List<Playlist> = queryPlaylists(0, Int.MAX_VALUE) {
        where { PlaylistTable.id inList ids }
    }.data

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
                query,
                listOf(10),
                listOf(PlaylistTable.name),
                PlaylistTable.id
            )
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
        PlaylistTable.deleteWhere { PlaylistTable.id eq id } == 1
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryPlaylists(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryPlaylists(page: Int, pageSize: Int, query: Query.() -> Query = { this }) =
        dbQuery {
            val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
            val mainPlaylistRows = PlaylistTable
                .selectAll()
                .query()
                .paging(page, pageSize, offset)
                .toList()

            if (mainPlaylistRows.isEmpty()) return@dbQuery PaginatedResponse(
                data = listOf(),
                total = 0,
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
                data = data.drop(page * pageSize).take(pageSize),
                total = data.size,
                page = page,
                pageSize = pageSize,
                hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
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
                        name = row[SongTable.title],
                        duration = row[SongTable.duration]
                    )
                )
            }

        val sortedEntries = songEntriesWithPosition
            .sortedBy { it.first }
            .map { it.second }

        return Pair(playlistName, sortedEntries)
    }

    suspend fun createBatch(playlists: List<InsertablePlaylist>): List<UUID> {
        if (playlists.isEmpty()) return emptyList()

        val imageService = get<ImageService>()

        val allUniqueImageHashes = playlists.mapNotNull { it.imageHash }.distinct()
        val allUniqueSongPaths = playlists.flatMap { it.songPaths }.distinct()

        val existingRows = dbQuery {
            PlaylistTable
                .select(PlaylistTable.id, PlaylistTable.name)
                .where { PlaylistTable.name inList playlists.map { it.name } }
                .toList()
        }

        val existingNames = existingRows.map { it[PlaylistTable.name] }.toSet()
        val existingMap = existingRows.associate { it[PlaylistTable.name] to it[PlaylistTable.id].value }

        val existingPlaylists = playlists.filter { it.name in existingNames }.mapNotNull { existingMap[it.name] }

        dbQuery {
            PlaylistTable.deleteWhere {
                PlaylistTable.id inList existingPlaylists
            }
            PlaylistSongTable.deleteWhere {
                PlaylistSongTable.playlistId inList existingPlaylists
            }
        }

        val imageIdMap: Map<String, UUID> = imageService.getCoverHashes(allUniqueImageHashes)

        val songIdByPath: Map<String, UUID> = dbQuery {
            SongTable
                .select(SongTable.id, SongTable.filePath)
                .where { SongTable.filePath inList allUniqueSongPaths }
                .associate { it[SongTable.filePath] to it[SongTable.id].value }
        }

        val playlistInsertResults: List<ResultRow> = dbQuery {
            PlaylistTable.batchInsert(playlists) { playlist ->
                val imageId = playlist.imageHash?.let { imageIdMap[it] }

                this[PlaylistTable.name] = playlist.name
                this[PlaylistTable.imageId] = imageId
            }
        }

        val insertedPlaylistsWithData = playlistInsertResults
            .map { it[PlaylistTable.id].value to playlists[playlistInsertResults.indexOf(it)] }

        val insertedPlaylistIds = insertedPlaylistsWithData.map { it.first }

        val playlistSongLinks = insertedPlaylistsWithData.flatMap { (playlistId, playlistData) ->
            var position = 1
            playlistData.songPaths.mapNotNull { songPath ->
                val songId = songIdByPath[songPath]

                songId?.let {
                    val link = Triple(playlistId, it, position++)
                    link
                }
            }
        }.distinctBy { listOf(it.first, it.second) }

        dbQuery {
            PlaylistSongTable.batchInsert(playlistSongLinks) { (playlistId, songId, position) ->
                this[PlaylistSongTable.playlistId] = playlistId
                this[PlaylistSongTable.songId] = songId
                this[PlaylistSongTable.position] = position
            }
        }

        return insertedPlaylistIds
    }

    suspend fun upsertPlaylist(playlist: Playlist) = dbQuery {
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
    }
}