package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.PlaylistLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.time.Instant
import java.util.Date
import java.util.UUID

class UserPlaylistService : PlaylistLibrary, IUserPlaylistService, Service() {
    companion object {
        fun mapPlaylist(resultRow: ResultRow): UserPlaylist {
            val id = resultRow[UserPlaylistTable.id].value
            val name = resultRow[UserPlaylistTable.name]
            val imageId = resultRow[UserPlaylistTable.imageId]?.value
            val blurHash = resultRow.getOrNull(ImageTable.blurHash)
            val creator = resultRow[UserPlaylistTable.creator].value
            val description = resultRow[UserPlaylistTable.description]
            val origin = resultRow[UserPlaylistTable.origin]

            return UserPlaylist(
                id = id,
                name = name,
                songs = emptyList(),
                imageId = imageId,
                blurHash = blurHash,
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

    suspend fun byName(name: String, creator: UUID): UserPlaylist? = querySingle {
        where { (UserPlaylistTable.name eq name) and (UserPlaylistTable.creator eq creator) }
    }

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
            if (creator != null) where { UserPlaylistTable.creator eq creator }
            this
        }

    override suspend fun byColor(
        creator: UUID?,
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int
    ): PaginatedResponse<UserPlaylist> {
        val (l, a, b) = dev.dertyp.utils.ColorUtils.rgbToLab((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        return queryPlaylists(page, pageSize, columnSet = {
            leftJoin(ImageMetadataTable, onColumn = { UserPlaylistTable.imageId }, otherColumn = { ImageMetadataTable.imageId })
        }) {
            filterByColor(l, a, b, range)
            if (creator != null) andWhere { UserPlaylistTable.creator eq creator }
            else this
        }
    }

    fun allPlaylistsFlow(creator: UUID?): Flow<UserPlaylist> = flow {
        val total = allPlaylists(creator, 0, 0).total
        var page = 0
        val pageSize = 100
        while (page * pageSize < total) {
            allPlaylists(creator, page, pageSize).data.forEach { emit(it) }
            page++
        }
    }.flowOn(Dispatchers.IO)

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
            this[UserPlaylistTable.creator] = EntityID(user.id, UserTable)
            this[UserPlaylistTable.imageId] = coverImageId?.id?.let { EntityID(it, ImageTable) }
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

    override suspend fun addSongsToPlaylist(id: UUID, songIds: List<UUID>) {
        val now = System.currentTimeMillis()
        addToPlaylist(id, songIds.mapIndexed { index, it -> (now + index) to it })
    }

    override suspend fun addAlbumToPlaylist(id: UUID, albumId: UUID) {
        val songService by inject<SongService>()
        val songIds = songService.songIdsByAlbum(albumId).toList()
        addSongsToPlaylist(id, songIds)
    }

    override suspend fun addPlaylistToPlaylist(id: UUID, sourcePlaylistId: UUID) {
        val songService by inject<SongService>()
        val songIds = songService.songIdsByPlaylist(sourcePlaylistId).toList()
        addSongsToPlaylist(id, songIds)
    }

    override suspend fun addUserPlaylistToPlaylist(id: UUID, sourcePlaylistId: UUID) {
        val songService by inject<SongService>()
        val songIds = songService.songIdsByUserPlaylist(sourcePlaylistId).toList()
        addSongsToPlaylist(id, songIds)
    }

    override suspend fun removeFromPlaylist(id: UUID, songIds: List<UUID>): Int = dbQuery {
        UserPlaylistSongTable.deleteWhere {
            (UserPlaylistSongTable.playlistId eq id) and (UserPlaylistSongTable.songId inList songIds)
        }
    }

    override suspend fun setPlaylistImage(id: UUID, imageId: UUID?): Boolean = dbQuery {
        UserPlaylistTable.update({ UserPlaylistTable.id eq id }) {
            it[UserPlaylistTable.imageId] = imageId
        } == 1
    }

    override suspend fun createBatch(playlists: List<InsertablePlaylist>, userId: PlatformUUID?): List<PlatformUUID> {
        if (playlists.isEmpty() || userId == null) return emptyList()

        val imageService by inject<ImageService>()

        val allUniqueImageHashes = playlists.mapNotNull { it.imageHash }.distinct()
        val allUniqueSongPaths = playlists.flatMap { it.songPaths }.distinct()

        val imageIdMap: Map<String, UUID> = imageService.getCoverHashes(allUniqueImageHashes)

        val songIdByPath: Map<String, UUID> = dbQuery {
            SongTable
                .select(SongTable.id, SongTable.filePath)
                .where { SongTable.filePath inList allUniqueSongPaths }
                .associate { it[SongTable.filePath] to it[SongTable.id].value }
        }

        return playlists.map { playlist ->
            val existingId = dbQuery {
                UserPlaylistTable
                    .select(UserPlaylistTable.id)
                    .where { (UserPlaylistTable.name eq playlist.name) and (UserPlaylistTable.creator eq userId) }
                    .firstOrNull()?.get(UserPlaylistTable.id)?.value ?: UUID.randomUUID()
            }

            val imageId = playlist.imageHash?.let { imageIdMap[it] }
            val resolvedSongIds = playlist.songPaths.mapNotNull { songIdByPath[it] }

            val userPlaylist = UserPlaylist(
                id = existingId,
                name = playlist.name,
                songs = resolvedSongIds,
                imageId = imageId,
                creator = userId,
                description = playlist.description,
                origin = playlist.origin
            )

            upsertUserPlaylist(userPlaylist)
            existingId
        }
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryPlaylists(0, Int.MAX_VALUE, query = query).data.singleOrNull()

    private suspend fun queryPlaylists(
        page: Int,
        pageSize: Int,
        columnSet: ColumnSet.() -> ColumnSet = { this },
        query: Query.() -> Query = { this }
    ) =
        dbQuery {
            val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
            val mainPlaylistRows = UserPlaylistTable
                .leftJoin(ImageTable, onColumn = { UserPlaylistTable.imageId }, otherColumn = { ImageTable.id })
                .columnSet()
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

            val songIds = songLinkRows.map { it[UserPlaylistSongTable.songId].value }.distinct()

            val songInfoById = if (songIds.isNotEmpty()) {
                getSongInfoForPlaylist(songIds)
            } else {
                emptyMap()
            }

            val data = mapEagerly(mainPlaylistRows, songLinkRows, songInfoById)

            PaginatedResponse(
                data = data.drop(page * pageSize).take(pageSize),
                total = data.size,
                page = page,
                pageSize = pageSize,
                hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
            )
        }

    private suspend fun getSongInfoForPlaylist(songIds: List<UUID>): Map<UUID, Pair<Long, UUID?>> = dbQuery {
        SongTable
            .leftJoin(SongMusicBrainzTable)
            .select(SongTable.id, SongTable.duration, SongMusicBrainzTable.musicBrainzId)
            .where { SongTable.id inList songIds }
            .associate { row ->
                row[SongTable.id].value to Pair(
                    row[SongTable.duration],
                    row.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value
                )
            }
    }

    private fun mapEagerly(
        mainRows: List<ResultRow>,
        songLinkRows: List<ResultRow>,
        songInfoById: Map<UUID, Pair<Long, UUID?>>
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
                    songInfoById[songId]?.first ?: 0L
                }.takeIf { it > 0L } ?: -1L

            val songs = songsByPlaylistId[playlist.id]
                ?.sortedBy { it.second }
                ?: listOf()

            playlist.copy(
                songs = songs.map { it.first },
                songEntries = songs.map { UserPlaylistSong(it.first, it.second, songInfoById[it.first]?.second) },
                totalDuration = totalDuration,
                modifiedAt = songs.lastOrNull()?.second.date ?: Date.from(Instant.EPOCH)
            )
        }.sortedByDescending { it.modifiedAt }
    }

    suspend fun upsertUserPlaylist(playlist: UserPlaylist, creatorOverride: UUID? = null) = dbQuery {
        UserPlaylistTable.upsert(UserPlaylistTable.id) {
            it[id] = playlist.id
            it[name] = playlist.name
            it[imageId] = playlist.imageId?.let { imgId -> EntityID(imgId, ImageTable) }
            it[creator] = EntityID(creatorOverride ?: playlist.creator, UserTable)
            it[description] = playlist.description
            it[origin] = playlist.origin
        }

        UserPlaylistSongTable.deleteWhere { UserPlaylistSongTable.playlistId eq playlist.id }

        val allRequestedSongIds = (playlist.songEntries?.map { it.songId } ?: playlist.songs).distinct()
        val existingSongIds = SongTable.select(SongTable.id)
            .where { SongTable.id inList allRequestedSongIds }
            .map { it[SongTable.id].value }
            .toSet()

        if (playlist.songEntries != null) {
            val mbIdToSongId = playlist.songEntries!!
                .filter { it.songId !in existingSongIds && it.musicBrainzId != null }
                .mapNotNull { it.musicBrainzId }
                .takeIf { it.isNotEmpty() }
                ?.let { mbIds ->
                    SongMusicBrainzTable
                        .select(SongMusicBrainzTable.songId, SongMusicBrainzTable.musicBrainzId)
                        .where { SongMusicBrainzTable.musicBrainzId inList mbIds }
                        .associate { it[SongMusicBrainzTable.musicBrainzId]!!.value to it[SongMusicBrainzTable.songId].value }
                } ?: emptyMap()

            val entriesWithResolvedSongs = playlist.songEntries!!.mapNotNull { entry ->
                val songId = if (entry.songId in existingSongIds) entry.songId else mbIdToSongId[entry.musicBrainzId]
                if (songId != null) entry.copy(songId = songId) else null
            }

            UserPlaylistSongTable.batchInsert(entriesWithResolvedSongs) { entry ->
                this[UserPlaylistSongTable.playlistId] = playlist.id
                this[UserPlaylistSongTable.songId] = entry.songId
                this[UserPlaylistSongTable.addedAt] = entry.addedAt
            }
        } else {
            val existingSongsToInsert = playlist.songs.filter { it in existingSongIds }
            val now = System.currentTimeMillis()
            UserPlaylistSongTable.batchInsert(existingSongsToInsert.mapIndexed { index, songId -> index to songId }) { (index, songId) ->
                this[UserPlaylistSongTable.playlistId] = playlist.id
                this[UserPlaylistSongTable.songId] = songId
                this[UserPlaylistSongTable.addedAt] = now + index
            }
        }
    }
}
