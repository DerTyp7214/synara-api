package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.koin.core.component.inject
import java.util.*

class UserPlaylistService : Service() {
    companion object {
        fun mapPlaylist(resultRow: ResultRow): UserPlaylist {
            val id = resultRow[UserPlaylistTable.id].value
            val name = resultRow[UserPlaylistTable.name]
            val imageId = resultRow[UserPlaylistTable.imageId]?.value
            val creator = resultRow[UserPlaylistTable.creator].value
            val description = resultRow[UserPlaylistTable.description]

            return UserPlaylist(
                id = id,
                name = name,
                songs = emptyList(),
                imageId = imageId,
                creator = creator,
                description = description,
            )
        }
    }

    fun map(resultRow: ResultRow) = mapPlaylist(resultRow)

    suspend fun byId(id: UUID): UserPlaylist? = querySingle {
        where { UserPlaylistTable.id eq id }
    }

    suspend fun rankedSearch(creator: UUID?, page: Int, pageSize: Int, query: String): PaginatedResponse<UserPlaylist> =
        queryPlaylists(page, pageSize) {
            rankedSearchQuery(
                query,
                listOf(10),
                listOf(UserPlaylistTable.name)
            )
            if (creator != null) andWhere { UserPlaylistTable.creator eq creator }
            else this
        }

    suspend fun allPlaylists(creator: UUID?, page: Int, pageSize: Int): PaginatedResponse<UserPlaylist> =
        queryPlaylists(page, pageSize) {
            if (creator != null) where { UserPlaylistTable.creator eq creator } else this
        }

    suspend fun delete(id: UUID): Boolean = dbQuery {
        UserPlaylistTable.deleteWhere { UserPlaylistTable.id eq id } == 1
    }

    suspend fun getOrAddPlaylist(user: User, customIdentifier: String?, playlist: InsertablePlaylist) = dbQuery {
        if (customIdentifier != null) querySingle {
            where { UserPlaylistTable.creator eq user.id and (UserPlaylistTable.customIdentifier eq customIdentifier) }
        }.let { result ->
            if (result != null) return@dbQuery result.id
        }

        val imageService by inject<ImageService>()

        val coverImageId = playlist.imageHash?.let { imageService.byHash(it) }

        UserPlaylistTable.batchInsert(listOf(playlist)) { playlist ->
            this[UserPlaylistTable.name] = playlist.name
            this[UserPlaylistTable.customIdentifier] = customIdentifier
            this[UserPlaylistTable.description] = playlist.description
            this[UserPlaylistTable.creator] = user.id
            this[UserPlaylistTable.imageId] = coverImageId?.id
        }.first()[UserPlaylistTable.id].value
    }

    suspend fun addToPlaylist(id: UUID, songIds: List<UUID>) = dbQuery {
        var highestPosition = dbQuery {
            UserPlaylistSongTable.select(UserPlaylistSongTable.playlistId, UserPlaylistSongTable.position)
                .where { UserPlaylistSongTable.playlistId eq id }
                .orderBy(UserPlaylistSongTable.position, SortOrder.DESC)
                .limit(1)
                .map { it[UserPlaylistSongTable.position] }
                .singleOrNull()
        } ?: 0

        UserPlaylistSongTable.batchInsert(songIds) { songId ->
            this[UserPlaylistSongTable.playlistId] = id
            this[UserPlaylistSongTable.songId] = songId
            this[UserPlaylistSongTable.position] = ++highestPosition
        }
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryPlaylists(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryPlaylists(page: Int, pageSize: Int, query: Query.() -> Query = { this }) =
        dbQuery {
            val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
            val mainPlaylistRows = UserPlaylistTable
                .selectAll()
                .query()
                .paging(page, pageSize, offset)
                .toList()

            if (mainPlaylistRows.isEmpty()) return@dbQuery PaginatedResponse(
                data = listOf(),
                page = page,
                pageSize = pageSize
            )

            val playlistIds = mainPlaylistRows.map { it[UserPlaylistTable.id].value }

            val songLinkRows = UserPlaylistSongTable
                .select(UserPlaylistSongTable.playlistId, UserPlaylistSongTable.songId, UserPlaylistSongTable.position)
                .where { UserPlaylistSongTable.playlistId inList playlistIds }
                .toList()

            val songIds = songLinkRows.map { it[UserPlaylistSongTable.songId].value }.distinct()

            val songDurationsById = if (songIds.isNotEmpty()) {
                getSongDurations(songIds)
            } else {
                emptyMap()
            }

            val data = mapEagerly(mainPlaylistRows, songLinkRows, songDurationsById)

            PaginatedResponse(
                data = data.take(pageSize),
                page = page,
                pageSize = pageSize,
                hasNextPage = data.size == pageSize + offset
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
    ): List<UserPlaylist> {
        val songsByPlaylistId = songLinkRows
            .map { row ->
                row[UserPlaylistSongTable.playlistId].value to
                        Pair(row[UserPlaylistSongTable.songId].value, row[UserPlaylistSongTable.position])
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
}