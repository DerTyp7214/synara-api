package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ArtistPlaylistSortStrategy
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.utils.LogParam
import java.util.UUID

class RpcUserPlaylistService(
    private val user: User,
    private val userPlaylistService: UserPlaylistService
) : IUserPlaylistService {
    override suspend fun byId(id: UUID): UserPlaylist? =
        userPlaylistService.byId(id, user.id.takeUnless { user.isAdmin })?.let(::withVisibleShares)

    override suspend fun byIds(@LogParam("size") ids: List<UUID>): List<UserPlaylist> =
        userPlaylistService.byIds(ids, user.id.takeUnless { user.isAdmin }).map(::withVisibleShares)

    override suspend fun rankedSearch(
        creator: UUID?,
        page: Int,
        pageSize: Int,
        query: String
    ): PaginatedResponse<UserPlaylist> =
        withVisibleShares(userPlaylistService.rankedSearch(creator, page, pageSize, query, user.id))

    override suspend fun allPlaylists(creator: UUID?, page: Int, pageSize: Int): PaginatedResponse<UserPlaylist> =
        withVisibleShares(userPlaylistService.allPlaylists(creator, page, pageSize, user.id))

    override suspend fun sharedPlaylists(page: Int, pageSize: Int): PaginatedResponse<UserPlaylist> =
        withVisibleShares(userPlaylistService.sharedPlaylists(user.id, page, pageSize))

    override suspend fun byColor(
        creator: UUID?,
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int
    ): PaginatedResponse<UserPlaylist> =
        withVisibleShares(userPlaylistService.byColor(creator, page, pageSize, color, range, user.id))

    override suspend fun delete(id: UUID): Boolean {
        requireOwner(id)
        return userPlaylistService.delete(id)
    }

    override suspend fun getOrAddPlaylist(userId: UUID, customIdentifier: String?, playlist: InsertablePlaylist): UUID {
        requireSelf(userId)
        return userPlaylistService.getOrAddPlaylist(userId, customIdentifier, playlist)
    }

    override suspend fun addToPlaylist(id: UUID, songIds: List<Pair<Long, UUID>>) {
        requireWrite(id)
        userPlaylistService.addToPlaylist(id, songIds)
    }

    override suspend fun addSongsToPlaylist(id: UUID, songIds: List<UUID>) {
        requireWrite(id)
        userPlaylistService.addSongsToPlaylist(id, songIds)
    }

    override suspend fun addAlbumToPlaylist(id: UUID, albumId: UUID) {
        requireWrite(id)
        userPlaylistService.addAlbumToPlaylist(id, albumId)
    }

    override suspend fun addPlaylistToPlaylist(id: UUID, sourcePlaylistId: UUID) {
        requireWrite(id)
        userPlaylistService.addPlaylistToPlaylist(id, sourcePlaylistId)
    }

    override suspend fun addUserPlaylistToPlaylist(id: UUID, sourcePlaylistId: UUID) {
        requireWrite(id)
        requireRead(sourcePlaylistId)
        userPlaylistService.addUserPlaylistToPlaylist(id, sourcePlaylistId)
    }

    override suspend fun removeFromPlaylist(id: UUID, songIds: List<UUID>): Int {
        requireWrite(id)
        return userPlaylistService.removeFromPlaylist(id, songIds)
    }

    override suspend fun setPlaylistImage(id: UUID, imageId: UUID?): Boolean {
        requireOwner(id)
        return userPlaylistService.setPlaylistImage(id, imageId)
    }

    override suspend fun setPublic(id: UUID, isPublic: Boolean): Boolean {
        requireOwner(id)
        return userPlaylistService.setPublic(id, isPublic)
    }

    override suspend fun setShare(id: UUID, userId: UUID, access: PlaylistAccess) {
        requireOwner(id)
        userPlaylistService.setShare(id, userId, access)
    }

    override suspend fun removeShare(id: UUID, userId: UUID): Boolean {
        if (userId != user.id) requireOwner(id)
        return userPlaylistService.removeShare(id, userId)
    }

    override suspend fun transferOwnership(id: UUID, newOwnerId: UUID): Boolean {
        requireOwner(id)
        return userPlaylistService.transferOwnership(id, newOwnerId)
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

    private suspend fun requireWrite(id: UUID) {
        if (user.isAdmin) return
        val access = userPlaylistService.accessOf(id, user.id) ?: return
        if (!access.canWrite) throw UnauthorizedException("No write access to user playlist $id")
    }

    private suspend fun requireRead(id: UUID) {
        if (user.isAdmin) return
        val access = userPlaylistService.accessOf(id, user.id) ?: return
        if (!access.canRead) throw UnauthorizedException("No access to user playlist $id")
    }

    private fun withVisibleShares(playlist: UserPlaylist): UserPlaylist =
        if (user.isAdmin || playlist.creator == user.id || playlist.shares.any { it.userId == user.id }) playlist
        else playlist.copy(shares = emptyList())

    private fun withVisibleShares(page: PaginatedResponse<UserPlaylist>): PaginatedResponse<UserPlaylist> =
        page.copy(data = page.data.map(::withVisibleShares))

    private fun requireSelf(userId: UUID) {
        if (userId != user.id && !user.isAdmin) throw UnauthorizedException("Cannot act on behalf of user $userId")
    }
}
