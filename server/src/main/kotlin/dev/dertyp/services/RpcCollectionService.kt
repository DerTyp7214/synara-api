package dev.dertyp.services

import dev.dertyp.data.Collection
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.User
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcCollectionService(
    private val user: User,
    private val collectionService: CollectionService
) : ICollectionService {
    override suspend fun byId(id: UUID): Collection? = collectionService.byId(id)

    override suspend fun allCollections(): List<Collection> = collectionService.allCollections(user.id)

    override suspend fun createCollection(collection: InsertableCollection): UUID =
        collectionService.createCollection(user.id, collection)

    override suspend fun updateCollection(id: UUID, collection: InsertableCollection): Boolean =
        collectionService.updateCollection(id, collection)

    override suspend fun addItem(id: UUID, itemType: CollectionItemType, itemId: UUID): Boolean =
        collectionService.addItem(id, itemType, itemId)

    override suspend fun removeItem(id: UUID, itemType: CollectionItemType, itemId: UUID): Boolean =
        collectionService.removeItem(id, itemType, itemId)

    override suspend fun setCollectionImage(id: UUID, imageId: UUID?): Boolean =
        collectionService.setCollectionImage(id, imageId)

    override suspend fun delete(id: UUID): Boolean = collectionService.delete(id)

    override fun songIds(collectionId: UUID): Flow<UUID> = collectionService.songIds(collectionId)
    override fun albumIds(collectionId: UUID): Flow<UUID> = collectionService.albumIds(collectionId)
    override fun artistIds(collectionId: UUID): Flow<UUID> = collectionService.artistIds(collectionId)
    override fun playlistIds(collectionId: UUID): Flow<UUID> = collectionService.playlistIds(collectionId)
}
