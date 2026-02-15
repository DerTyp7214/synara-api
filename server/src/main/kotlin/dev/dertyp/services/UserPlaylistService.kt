package dev.dertyp.services

import dev.dertyp.core.*
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.time.Instant
import java.util.*

class UserPlaylistService : IUserPlaylistService, Service() {
    companion object {
        fun mapPlaylist(resultRow: ResultRow): UserPlaylist {
            val id = resultRow[UserPlaylistTable.id].value
            val name = resultRow[UserPlaylistTable.name]
            val imageId = resultRow[UserPlaylistTable.imageId]?.value
            val creator = resultRow[UserPlaylistTable.creator].value
            val description = resultRow[UserPlaylistTable.description]
            val origin = resultRow[UserPlaylistTable.origin]

            return UserPlaylist(
                id = id,
                name = name,
                songs = emptyList(),
                imageId = imageId,
                creator = creator,
                description = description,
                origin = origin,
            )
        }
    }

    fun map(resultRow: ResultRow) = mapPlaylist(resultRow)

    override suspend fun byId(id: UUID): UserPlaylist? = querySingle {
        where { UserPlaylistTable.id eq id }
    }

    override suspend fun byIds(ids: List<UUID>): List<UserPlaylist> = queryPlaylists(0, Int.MAX_VALUE) {
        where { UserPlaylistTable.id inList ids }
    }.data

    override suspend fun rankedSearch(creator: UUID?, page: Int, pageSize: Int, query: String): PaginatedResponse<UserPlaylist> =
        queryPlaylists(page, pageSize) {
            rankedSearchQuery(
                query,
                listOf(10),
                listOf(UserPlaylistTable.name),
                UserPlaylistTable.id
            )
            if (creator != null) andWhere { UserPlaylistTable.creator eq creator }
            else this
        }

    override suspend fun allPlaylists(creator: UUID?, page: Int, pageSize: Int): PaginatedResponse<UserPlaylist> =
        queryPlaylists(page, pageSize) {
            if (creator != null) where { UserPlaylistTable.creator eq creator } else this
        }

    override suspend fun delete(id: UUID): Boolean = dbQuery {
        UserPlaylistTable.deleteWhere { UserPlaylistTable.id eq id } == 1
    }

    override suspend fun getOrAddPlaylist(user: User, customIdentifier: String?, playlist: InsertablePlaylist): UUID = dbQuery {
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
            this[UserPlaylistTable.origin] = playlist.origin
        }.first()[UserPlaylistTable.id].value
    }

    override suspend fun addToPlaylist(id: UUID, songIds: List<Pair<Long, UUID>>): List<UUID> = dbQuery {
        val existing = UserPlaylistSongTable
            .select(UserPlaylistSongTable.playlistId, UserPlaylistSongTable.songId, UserPlaylistSongTable.addedAt)
            .where { UserPlaylistSongTable.playlistId eq id }
            .andWhere { UserPlaylistSongTable.songId inList songIds.values }
            .map { Pair(it[UserPlaylistSongTable.addedAt], it[UserPlaylistSongTable.songId].value) }

        UserPlaylistSongTable.batchInsert(songIds.minusOnce(existing.toSet())) { (addedAt, songId) ->
            this[UserPlaylistSongTable.playlistId] = id
            this[UserPlaylistSongTable.songId] = songId
            this[UserPlaylistSongTable.addedAt] = addedAt
        }.map { it[UserPlaylistSongTable.songId].value }
    }

    override suspend fun removeFromPlaylist(id: UUID, songIds: List<UUID>): Int = dbQuery {
        UserPlaylistSongTable.deleteWhere {
            UserPlaylistSongTable.playlistId eq id and (UserPlaylistSongTable.songId inList songIds)
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
                .orderBy(UserPlaylistTable.name)
                .paging(page, pageSize, offset)
                .toList()

            if (mainPlaylistRows.isEmpty()) return@dbQuery PaginatedResponse(
                data = listOf(),
                total = 0,
                page = page,
                pageSize = pageSize
            )

            val playlistIds = mainPlaylistRows.map { it[UserPlaylistTable.id].value }

            val songLinkRows = UserPlaylistSongTable
                .select(UserPlaylistSongTable.playlistId, UserPlaylistSongTable.songId, UserPlaylistSongTable.addedAt)
                .where { UserPlaylistSongTable.playlistId inList playlistIds }
                .toList()

            val songIds = songLinkRows.map { it[UserPlaylistSongTable.songId].value }
            val distinctSongIds = songIds.distinct()

            val songDurationsById = if (distinctSongIds.isNotEmpty()) {
                getSongDurations(distinctSongIds)
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
    ): List<UserPlaylist> {
        val songsByPlaylistId = songLinkRows
            .map { row ->
                row[UserPlaylistSongTable.playlistId].value to
                        Pair(row[UserPlaylistSongTable.songId].value, row[UserPlaylistSongTable.addedAt])
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
                ?: listOf()

            playlist.copy(
                songs = songs.map { it.first },
                totalDuration = totalDuration,
                modifiedAt = songs.lastOrNull()?.second.date ?: Date.from(Instant.EPOCH)
            )
        }.sortedByDescending { it.modifiedAt }
    }
}