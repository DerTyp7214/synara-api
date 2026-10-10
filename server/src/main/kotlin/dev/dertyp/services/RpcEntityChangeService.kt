package dev.dertyp.services

import dev.dertyp.data.EntityChange
import dev.dertyp.data.EntityChangeWindow
import dev.dertyp.data.User
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcEntityChangeService(
    private val user: User,
    private val entityChangeService: EntityChangeService
) : IEntityChangeService {
    override suspend fun getWindow(): EntityChangeWindow = entityChangeService.getWindow()

    override fun allChanges(since: Long): Flow<EntityChange> = entityChangeService.allChanges(user.id, since)

    override fun byArtist(artistId: UUID, since: Long): Flow<EntityChange> =
        entityChangeService.byArtist(user.id, artistId, since)

    override fun byAlbum(albumId: UUID, since: Long): Flow<EntityChange> =
        entityChangeService.byAlbum(user.id, albumId, since)

    override fun byPlaylist(playlistId: UUID, since: Long): Flow<EntityChange> =
        entityChangeService.byPlaylist(user.id, playlistId, since, user.id.takeUnless { user.isAdmin })

    override fun byCollection(collectionId: UUID, since: Long): Flow<EntityChange> =
        entityChangeService.byCollection(user.id, collectionId, since)
}
