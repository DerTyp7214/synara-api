package dev.dertyp.services

import dev.dertyp.core.*
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.CollectionSearchResults
import dev.dertyp.data.CollectionSongMatch
import dev.dertyp.data.ImageSource
import dev.dertyp.data.MediaCollection
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.UUID

class CollectionService : Service() {
    private val songService by inject<SongService>()
    private val artistService by inject<ArtistService>()
    private val albumService by inject<AlbumService>()
    private val userPlaylistService by inject<UserPlaylistService>()
    private val hooks by inject<HookBus>()

    companion object {
        fun mapCollection(resultRow: ResultRow): MediaCollection = MediaCollection(
            id = resultRow[CollectionTable.id].value,
            name = resultRow[CollectionTable.name],
            description = resultRow[CollectionTable.description],
            imageId = resultRow[CollectionTable.imageId]?.value,
            blurHash = resultRow.getOrNull(ImageTable.blurHash),
            creator = resultRow[CollectionTable.creator].value,
            imageSource = resultRow.getOrNull(CollectionTable.imageSource)?.let { ImageSource.valueOf(it) },
        )
    }

    suspend fun byId(id: UUID): MediaCollection? {
        val base = dbQuery {
            CollectionTable
                .leftJoin(ImageTable, onColumn = { CollectionTable.imageId }, otherColumn = { ImageTable.id })
                .selectAll()
                .where { CollectionTable.id eq id }
                .firstOrNull()
                ?.let { mapCollection(it) }
        } ?: return null

        return base.withComputedStats()
    }

    suspend fun allCollections(creator: UUID?): List<MediaCollection> {
        val bases = dbQuery {
            val query = CollectionTable
                .leftJoin(ImageTable, onColumn = { CollectionTable.imageId }, otherColumn = { ImageTable.id })
                .selectAll()
            if (creator != null) query.where { CollectionTable.creator eq creator }
            query.orderBy(CollectionTable.name).map { mapCollection(it) }
        }

        return bases.map { it.withComputedStats() }
    }

    suspend fun createCollection(userId: UUID, collection: InsertableCollection): UUID {
        val id = dbQuery {
            CollectionTable.insertAndGetId {
                it[name] = collection.name
                it[description] = collection.description
                it[creator] = EntityID(userId, UserTable)
                it[imageId] = collection.imageId?.let { img -> EntityID(img, ImageTable) }
                it[imageSource] = collection.imageId?.let { ImageSource.USER.name }
            }.value
        }
        if (collection.imageId == null) hooks.emit(HookEvent.CollectionChanged(id))
        return id
    }

    suspend fun updateCollection(id: UUID, collection: InsertableCollection): Boolean {
        var imageCleared = false
        val updated = dbQuery {
            val current = CollectionTable
                .select(CollectionTable.imageId, CollectionTable.imageSource)
                .where { CollectionTable.id eq id }
                .firstOrNull() ?: return@dbQuery false
            val currentImageId = current[CollectionTable.imageId]?.value
            val currentSource = current[CollectionTable.imageSource]
            val newSource = when {
                collection.imageId == null -> null
                collection.imageId != currentImageId -> ImageSource.USER.name
                else -> currentSource ?: ImageSource.USER.name
            }
            imageCleared = collection.imageId == null && currentImageId != null
            CollectionTable.update({ CollectionTable.id eq id }) {
                it[name] = collection.name
                it[description] = collection.description
                it[imageId] = collection.imageId
                it[imageSource] = newSource
                if (collection.imageId == null) {
                    it[coverStyle] = null
                    it[coverSeed] = null
                }
            } == 1
        }
        if (updated && imageCleared) hooks.emit(HookEvent.CollectionChanged(id))
        return updated
    }

    suspend fun setCollectionImage(id: UUID, imageId: UUID?): Boolean {
        val updated = dbQuery {
            CollectionTable.update({ CollectionTable.id eq id }) {
                it[CollectionTable.imageId] = imageId
                it[imageSource] = imageId?.let { ImageSource.USER.name }
                if (imageId == null) {
                    it[coverStyle] = null
                    it[coverSeed] = null
                }
            } == 1
        }
        if (updated && imageId == null) hooks.emit(HookEvent.CollectionChanged(id))
        return updated
    }

    suspend fun addItem(id: UUID, itemType: CollectionItemType, itemId: UUID): Boolean {
        val added = insertItem(id, itemType, itemId)
        if (added) hooks.emit(HookEvent.CollectionChanged(id))
        return added
    }

    private suspend fun insertItem(id: UUID, itemType: CollectionItemType, itemId: UUID): Boolean = dbQuery {
        when (itemType) {
            CollectionItemType.SONG -> {
                if (SongTable.select(SongTable.id).where { SongTable.id eq itemId }.empty()) return@dbQuery false
                CollectionSongTable.insertIgnore { it[collectionId] = id; it[songId] = itemId }.insertedCount > 0
            }
            CollectionItemType.ALBUM -> {
                if (AlbumTable.select(AlbumTable.id).where { AlbumTable.id eq itemId }.empty()) return@dbQuery false
                CollectionAlbumTable.insertIgnore { it[collectionId] = id; it[albumId] = itemId }.insertedCount > 0
            }
            CollectionItemType.ARTIST -> {
                if (ArtistTable.select(ArtistTable.id).where { ArtistTable.id eq itemId }.empty()) return@dbQuery false
                CollectionArtistTable.insertIgnore { it[collectionId] = id; it[artistId] = itemId }.insertedCount > 0
            }
            CollectionItemType.PLAYLIST -> {
                if (UserPlaylistTable.select(UserPlaylistTable.id).where { UserPlaylistTable.id eq itemId }.empty()) return@dbQuery false
                CollectionPlaylistTable.insertIgnore { it[collectionId] = id; it[playlistId] = itemId }.insertedCount > 0
            }
        }
    }

    suspend fun removeItem(id: UUID, itemType: CollectionItemType, itemId: UUID): Boolean {
        val removed = dbQuery {
            when (itemType) {
                CollectionItemType.SONG ->
                    CollectionSongTable.deleteWhere { (collectionId eq id) and (songId eq itemId) } > 0
                CollectionItemType.ALBUM ->
                    CollectionAlbumTable.deleteWhere { (collectionId eq id) and (albumId eq itemId) } > 0
                CollectionItemType.ARTIST ->
                    CollectionArtistTable.deleteWhere { (collectionId eq id) and (artistId eq itemId) } > 0
                CollectionItemType.PLAYLIST ->
                    CollectionPlaylistTable.deleteWhere { (collectionId eq id) and (playlistId eq itemId) } > 0
            }
        }
        if (removed) hooks.emit(HookEvent.CollectionChanged(id))
        return removed
    }

    suspend fun delete(id: UUID): Boolean = dbQuery {
        CollectionTable.deleteWhere { CollectionTable.id eq id } == 1
    }

    suspend fun rankedSearch(
        collectionId: UUID,
        query: String,
        explicit: Boolean,
        page: Int,
        pageSize: Int,
        userId: UUID,
    ): CollectionSearchResults = coroutineScope {
        val songsDeferred = async {
            val songPage = songService.rankedSearchInCollection(collectionId, page, pageSize, query, explicit, userId)

            val explicitIds = if (songPage.data.isEmpty()) emptySet() else dbQuery {
                songPage.data.map { it.id }.chunked(maxBatchSize).flatMap { chunk ->
                    CollectionSongTable
                        .select(CollectionSongTable.songId)
                        .where { CollectionSongTable.collectionId eq collectionId }
                        .andWhere { CollectionSongTable.songId inList chunk }
                        .map { it[CollectionSongTable.songId].value }
                }.toSet()
            }

            PaginatedResponse(
                data = songPage.data.map { CollectionSongMatch(it, explicitMember = it.id in explicitIds) },
                page = songPage.page,
                total = songPage.total,
                pageSize = songPage.pageSize,
                hasNextPage = songPage.hasNextPage,
            )
        }
        val artistsDeferred = async { artistService.rankedSearchInCollection(collectionId, page, pageSize, query, userId) }
        val albumsDeferred = async { albumService.rankedSearchInCollection(collectionId, page, pageSize, query, userId) }
        val playlistsDeferred = async { userPlaylistService.rankedSearchInCollection(collectionId, page, pageSize, query) }

        CollectionSearchResults(
            songs = songsDeferred.await(),
            artists = artistsDeferred.await(),
            albums = albumsDeferred.await(),
            playlists = playlistsDeferred.await(),
        )
    }

    fun songIds(collectionId: UUID): Flow<UUID> =
        linkedIdFlow(collectionId, CollectionSongTable.collectionId, CollectionSongTable.songId, CollectionSongTable.addedAt)
    fun albumIds(collectionId: UUID): Flow<UUID> =
        linkedIdFlow(collectionId, CollectionAlbumTable.collectionId, CollectionAlbumTable.albumId, CollectionAlbumTable.addedAt)
    fun artistIds(collectionId: UUID): Flow<UUID> =
        linkedIdFlow(collectionId, CollectionArtistTable.collectionId, CollectionArtistTable.artistId, CollectionArtistTable.addedAt)
    fun playlistIds(collectionId: UUID): Flow<UUID> =
        linkedIdFlow(collectionId, CollectionPlaylistTable.collectionId, CollectionPlaylistTable.playlistId, CollectionPlaylistTable.addedAt)

    private fun linkedIdFlow(
        collectionId: UUID,
        collectionColumn: Column<EntityID<UUID>>,
        idColumn: Column<EntityID<UUID>>,
        addedAtColumn: Column<Long>,
    ): Flow<UUID> = flow {
        idColumn.table
            .select(idColumn)
            .where { collectionColumn eq collectionId }
            .orderBy(addedAtColumn, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach { emit(it[idColumn].value) }
            }
    }.flowOn(Dispatchers.IO)

    private suspend fun MediaCollection.withComputedStats(): MediaCollection = dbQuery {
        val songItemIds = linkedIds(id, CollectionSongTable.collectionId, CollectionSongTable.songId)
        val albumItemIds = linkedIds(id, CollectionAlbumTable.collectionId, CollectionAlbumTable.albumId)
        val artistItemIds = linkedIds(id, CollectionArtistTable.collectionId, CollectionArtistTable.artistId)
        val playlistItemIds = linkedIds(id, CollectionPlaylistTable.collectionId, CollectionPlaylistTable.playlistId)

        val artistSongIds = artistItemIds.chunked(maxBatchSize).flatMap { chunk ->
            SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId inList chunk }
                .map { it[SongArtistTable.songId].value }
        }
        val artistAlbumIds = artistItemIds.chunked(maxBatchSize).flatMap { chunk ->
            AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId inList chunk }
                .map { it[AlbumArtistTable.albumId].value }
        }
        val playlistSongIds = playlistItemIds.chunked(maxBatchSize).flatMap { chunk ->
            UserPlaylistSongTable
                .select(UserPlaylistSongTable.songId)
                .where { UserPlaylistSongTable.playlistId inList chunk }
                .map { it[UserPlaylistSongTable.songId].value }
        }

        val songIds = (songItemIds + artistSongIds + playlistSongIds).distinct()
        val albumIds = (albumItemIds + artistAlbumIds).distinct()

        val seenSongIds = HashSet<UUID>()
        var totalSize = 0L
        fun consume(row: ResultRow) {
            if (seenSongIds.add(row[SongTable.id].value)) totalSize += row[SongTable.fileSize]
        }
        songIds.chunked(maxBatchSize).forEach { chunk ->
            SongTable
                .select(SongTable.id, SongTable.fileSize)
                .where { SongTable.id inList chunk }
                .forEach { consume(it) }
        }
        albumIds.chunked(maxBatchSize).forEach { chunk ->
            SongTable
                .select(SongTable.id, SongTable.fileSize)
                .where { SongTable.albumId inList chunk }
                .forEach { consume(it) }
        }

        copy(
            totalSizeBytes = totalSize,
            songCount = seenSongIds.size,
            songItemCount = songItemIds.size,
            albumCount = albumItemIds.size,
            artistCount = artistItemIds.size,
            playlistCount = playlistItemIds.size,
        )
    }

    private fun linkedIds(
        collectionId: UUID,
        collectionColumn: Column<EntityID<UUID>>,
        idColumn: Column<EntityID<UUID>>,
    ): List<UUID> =
        idColumn.table
            .select(idColumn)
            .where { collectionColumn eq collectionId }
            .map { it[idColumn].value }
}
