package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ArtistPlaylistSortStrategy
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.utils.LogParam
import java.util.UUID

class RpcUserPlaylistService(
    private val user: User,
    private val userPlaylistService: UserPlaylistService
) : IUserPlaylistService {
    override suspend fun byId(id: UUID): UserPlaylist? = userPlaylistService.byId(id)

    override suspend fun byIds(@LogParam("size") ids: List<UUID>): List<UserPlaylist> = userPlaylistService.byIds(ids)

    override suspend fun rankedSearch(
        creator: UUID?,
        page: Int,
        pageSize: Int,
        query: String
    ): PaginatedResponse<UserPlaylist> = userPlaylistService.rankedSearch(creator, page, pageSize, query)

    override suspend fun allPlaylists(creator: UUID?, page: Int, pageSize: Int): PaginatedResponse<UserPlaylist> =
        userPlaylistService.allPlaylists(creator, page, pageSize)

    override suspend fun byColor(
        creator: UUID?,
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int
    ): PaginatedResponse<UserPlaylist> = userPlaylistService.byColor(creator, page, pageSize, color, range)

    override suspend fun delete(id: UUID): Boolean {
        requireOwner(id)
        return userPlaylistService.delete(id)
    }

    override suspend fun getOrAddPlaylist(userId: UUID, customIdentifier: String?, playlist: InsertablePlaylist): UUID {
        requireSelf(userId)
        return userPlaylistService.getOrAddPlaylist(userId, customIdentifier, playlist)
    }

    override suspend fun addToPlaylist(id: UUID, songIds: List<Pair<Long, UUID>>) {
        requireOwner(id)
        userPlaylistService.addToPlaylist(id, songIds)
    }

    override suspend fun addSongsToPlaylist(id: UUID, songIds: List<UUID>) {
        requireOwner(id)
        userPlaylistService.addSongsToPlaylist(id, songIds)
    }

    override suspend fun addAlbumToPlaylist(id: UUID, albumId: UUID) {
        requireOwner(id)
        userPlaylistService.addAlbumToPlaylist(id, albumId)
    }

    override suspend fun addPlaylistToPlaylist(id: UUID, sourcePlaylistId: UUID) {
        requireOwner(id)
        userPlaylistService.addPlaylistToPlaylist(id, sourcePlaylistId)
    }

    override suspend fun addUserPlaylistToPlaylist(id: UUID, sourcePlaylistId: UUID) {
        requireOwner(id)
        userPlaylistService.addUserPlaylistToPlaylist(id, sourcePlaylistId)
    }

    override suspend fun removeFromPlaylist(id: UUID, songIds: List<UUID>): Int {
        requireOwner(id)
        return userPlaylistService.removeFromPlaylist(id, songIds)
    }

    override suspend fun setPlaylistImage(id: UUID, imageId: UUID?): Boolean {
        requireOwner(id)
        return userPlaylistService.setPlaylistImage(id, imageId)
    }

    override suspend fun createPlaylistFromArtists(
        userId: UUID,
        name: String,
        artistIds: List<UUID>,
        maxSongsPerArtist: Int,
        sortStrategy: ArtistPlaylistSortStrategy
    ): UUID {
        requireSelf(userId)
        return userPlaylistService.createPlaylistFromArtists(userId, name, artistIds, maxSongsPerArtist, sortStrategy)
    }

    private suspend fun requireOwner(id: UUID) {
        val playlist = userPlaylistService.byId(id) ?: return
        if (playlist.creator != user.id && !user.isAdmin) throw UnauthorizedException("Not the owner of user playlist $id")
    }

    private fun requireSelf(userId: UUID) {
        if (userId != user.id && !user.isAdmin) throw UnauthorizedException("Cannot act on behalf of user $userId")
    }
}
