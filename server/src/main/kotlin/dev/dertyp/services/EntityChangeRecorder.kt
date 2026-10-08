package dev.dertyp.services

import dev.dertyp.core.db.Dialect
import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionPlaylistTable
import dev.dertyp.db.CollectionSongTable
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.EntityChangeTrackingTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserPlaylistSongTable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.time.Clock

class EntityChangeRecorder : EntityWriteSubscriber, HookSubscriber {
    private data class Scope(val type: EntityType, val id: UUID)

    private class CommitStamp : StatementInterceptor {
        val libraryRows = mutableSetOf<UUID>()
        val userRows = mutableSetOf<UUID>()

        override fun beforeCommit(transaction: Transaction) {
            if (libraryRows.isEmpty() && userRows.isEmpty()) return
            val libraryChunks = libraryRows.sorted().chunked(CHUNK_SIZE)
            val userChunks = userRows.sorted().chunked(CHUNK_SIZE)
            val now = Clock.System.now().toEpochMilliseconds()
            for (chunk in libraryChunks) {
                EntityChangeTable.update({ EntityChangeTable.id inList chunk }) {
                    it[EntityChangeTable.changedAt] = now
                }
            }
            for (chunk in userChunks) {
                UserEntityChangeTable.update({ UserEntityChangeTable.id inList chunk }) {
                    it[UserEntityChangeTable.changedAt] = now
                }
            }
            libraryRows.clear()
            userRows.clear()
        }

        override fun afterRollback(transaction: Transaction) {
            libraryRows.clear()
            userRows.clear()
        }

        override fun keepUserDataInTransactionStoreOnCommit(userData: Map<Key<*>, Any?>): Map<Key<*>, Any?> =
            mapOf(COMMIT_STAMP to this)
    }

    override fun subscribe(hooks: HookService) {
        hooks.inTransaction(this)
    }

    override fun created(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        val scopes = scopesOf(type, entities)
        upsertLibrary(type, entities, EntityChangeAspect.DATA, EntityChangeKind.CREATED, now, scopes)
        markMembers(containersOf(scopes), now)
    }

    override fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (!containersChanged) {
            upsertLibrary(type, entities, EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now, keepScopes = true)
            return
        }
        val scopes = scopesOf(type, entities)
        upsertLibrary(type, entities, EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now, scopes)
        markMembers(containersOf(scopes), now)
    }

    override fun relinked(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        upsertLibrary(
            type,
            entities,
            EntityChangeAspect.DATA,
            EntityChangeKind.UPDATED,
            Clock.System.now().toEpochMilliseconds()
        )
    }

    override fun leavingContainers(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        markMembers(containersOf(scopesOf(type, entities)), Clock.System.now().toEpochMilliseconds())
    }

    override fun membersChanged(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        upsertLibrary(
            type,
            entities,
            EntityChangeAspect.MEMBERS,
            EntityChangeKind.UPDATED,
            Clock.System.now().toEpochMilliseconds()
        )
    }

    override fun deleting(type: EntityType, ids: Collection<UUID>) {
        val entities = ids.toSet()
        if (entities.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        if (type == EntityType.ARTIST) {
            for ((dependentType, dependents) in dependentsOf(type, entities)) {
                upsertLibrary(dependentType, dependents, EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now)
            }
        }
        if (type == EntityType.ALBUM) {
            upsertLibrary(type, otherEditionsOf(entities), EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now)
        }
        recordDeleted(type, entities, now)
    }

    override fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) {
        val removed = removedIds.toSet() - keptId
        if (removed.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        for ((dependentType, dependents) in dependentsOf(type, removed)) {
            val scopes = scopesOf(dependentType, dependents).mapValues { (_, entityScopes) ->
                entityScopes.mapTo(mutableSetOf()) { if (it.type == type && it.id in removed) Scope(type, keptId) else it }
            }
            upsertLibrary(dependentType, dependents, EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now, scopes)
        }
        if (type == EntityType.ALBUM) {
            upsertLibrary(type, otherEditionsOf(removed), EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now)
        }
        recordDeleted(type, removed, now)

        val kept = setOf(keptId)
        val scopes = scopesOf(type, kept)
        upsertLibrary(type, kept, EntityChangeAspect.DATA, EntityChangeKind.UPDATED, now, scopes)
        if (type != EntityType.SONG) markMembers(mapOf(type to kept), now)
        markMembers(containersOf(scopes), now)
        markMembers(holdersOf(type, kept), now)
    }

    override fun likesChanged(userId: UUID, type: EntityType, ids: Collection<UUID>) =
        upsertUser(userId, type, ids.toSet(), EntityChangeAspect.LIKE)

    override fun timecodesChanged(userId: UUID, songIds: Collection<UUID>) =
        upsertUser(userId, EntityType.SONG, songIds.toSet(), EntityChangeAspect.TIMECODES)

    fun restartTracking() {
        EntityChangeTable.deleteAll()
        UserEntityChangeTable.deleteAll()
        EntityChangeTrackingTable.upsert(EntityChangeTrackingTable.id) {
            it[id] = ROW_ID
            it[startedAt] = Clock.System.now().toEpochMilliseconds()
        }
    }

    private fun recordDeleted(type: EntityType, entities: Set<UUID>, now: Long) {
        val alreadyDeleted = entities.sorted().chunked(CHUNK_SIZE).flatMapTo(mutableSetOf()) { chunk ->
            EntityChangeTable.select(EntityChangeTable.entityId)
                .where { EntityChangeTable.entityType eq type }
                .andWhere { EntityChangeTable.aspect eq EntityChangeAspect.DATA }
                .andWhere { EntityChangeTable.kind eq EntityChangeKind.DELETED }
                .andWhere { EntityChangeTable.entityId inList chunk }
                .map { it[EntityChangeTable.entityId] }
        }
        val scopes = scopesOf(type, entities)
        val holders = holdersOf(type, entities)
        upsertLibrary(type, entities, EntityChangeAspect.DATA, EntityChangeKind.DELETED, now, scopes - alreadyDeleted)
        for (chunk in entities.sorted().chunked(CHUNK_SIZE)) {
            EntityChangeTable.deleteWhere {
                (EntityChangeTable.entityType eq type) and
                    (EntityChangeTable.aspect neq EntityChangeAspect.DATA) and
                    (EntityChangeTable.entityId inList chunk)
            }
            UserEntityChangeTable.deleteWhere {
                (UserEntityChangeTable.entityType eq type) and (UserEntityChangeTable.entityId inList chunk)
            }
        }
        markMembers(containersOf(scopes), now)
        markMembers(holders, now)
    }

    private fun markMembers(containers: Map<EntityType, Set<UUID>>, now: Long) {
        for ((type, ids) in containers.toSortedMap()) {
            upsertLibrary(type, ids, EntityChangeAspect.MEMBERS, EntityChangeKind.UPDATED, now)
        }
    }

    private fun upsertLibrary(
        type: EntityType,
        entities: Set<UUID>,
        aspect: EntityChangeAspect,
        kind: EntityChangeKind,
        now: Long,
        knownScopes: Map<UUID, Set<Scope>>? = null,
        keepScopes: Boolean = false
    ) {
        if (entities.isEmpty()) return
        val stamp = commitStamp()
        val scoped = type == EntityType.SONG || type == EntityType.ALBUM
        val scopes = if (scoped && !keepScopes) knownScopes ?: scopesOf(type, entities) else emptyMap()
        val upsertReturnsRows = Dialect.current() == Dialect.POSTGRES
        for (chunk in entities.sorted().chunked(CHUNK_SIZE)) {
            val upserted = EntityChangeTable.batchUpsert(
                chunk,
                EntityChangeTable.entityType,
                EntityChangeTable.entityId,
                EntityChangeTable.aspect,
                onUpdate = {
                    it[EntityChangeTable.changedAt] = insertValue(EntityChangeTable.changedAt)
                    it[EntityChangeTable.kind] = if (kind == EntityChangeKind.UPDATED) {
                        Case()
                            .When(EntityChangeTable.kind eq EntityChangeKind.CREATED, EntityChangeTable.kind)
                            .Else(insertValue(EntityChangeTable.kind))
                    } else {
                        insertValue(EntityChangeTable.kind)
                    }
                },
                shouldReturnGeneratedValues = upsertReturnsRows
            ) { entity ->
                this[EntityChangeTable.entityType] = type
                this[EntityChangeTable.entityId] = entity
                this[EntityChangeTable.aspect] = aspect
                this[EntityChangeTable.kind] = kind
                this[EntityChangeTable.changedAt] = now
            }

            val rows = if (upsertReturnsRows) {
                upserted.map { it[EntityChangeTable.id].value to it[EntityChangeTable.entityId] }
            } else {
                EntityChangeTable
                    .select(EntityChangeTable.id, EntityChangeTable.entityId)
                    .where { EntityChangeTable.entityType eq type }
                    .andWhere { EntityChangeTable.aspect eq aspect }
                    .andWhere { EntityChangeTable.entityId inList chunk }
                    .orderBy(EntityChangeTable.id)
                    .map { it[EntityChangeTable.id].value to it[EntityChangeTable.entityId] }
            }
            rows.mapTo(stamp.libraryRows) { it.first }
            if (!scoped) continue

            val unscoped = if (keepScopes) {
                val alreadyScoped = EntityChangeScopeTable
                    .select(EntityChangeScopeTable.changeId)
                    .where { EntityChangeScopeTable.changeId inList rows.map { it.first } }
                    .withDistinct()
                    .mapTo(mutableSetOf()) { it[EntityChangeScopeTable.changeId].value }
                rows.filter { it.first !in alreadyScoped }
            } else {
                rows
            }
            if (unscoped.isEmpty()) continue
            val rowScopes = if (keepScopes) scopesOf(type, unscoped.mapTo(mutableSetOf()) { it.second }) else scopes
            val changes = unscoped
                .map { (change, entity) -> change to rowScopes[entity].orEmpty() }
                .filter { (_, entityScopes) -> kind != EntityChangeKind.DELETED || entityScopes.isNotEmpty() }
            if (changes.isEmpty()) continue

            if (!keepScopes) {
                EntityChangeScopeTable.deleteWhere { EntityChangeScopeTable.changeId inList changes.map { it.first } }
            }
            EntityChangeScopeTable.batchInsert(
                changes.flatMap { (change, entityScopes) -> entityScopes.map { change to it } },
                shouldReturnGeneratedValues = false
            ) { (change, scope) ->
                this[EntityChangeScopeTable.changeId] = change
                this[EntityChangeScopeTable.scopeType] = scope.type
                this[EntityChangeScopeTable.scopeId] = scope.id
            }
        }
    }

    private fun upsertUser(userId: UUID, type: EntityType, entities: Set<UUID>, aspect: EntityChangeAspect) {
        if (entities.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        val stamp = commitStamp()
        val upsertReturnsRows = Dialect.current() == Dialect.POSTGRES
        for (chunk in entities.sorted().chunked(CHUNK_SIZE)) {
            val upserted = UserEntityChangeTable.batchUpsert(
                chunk,
                UserEntityChangeTable.userId,
                UserEntityChangeTable.entityType,
                UserEntityChangeTable.entityId,
                UserEntityChangeTable.aspect,
                onUpdate = {
                    it[UserEntityChangeTable.changedAt] = insertValue(UserEntityChangeTable.changedAt)
                    it[UserEntityChangeTable.kind] = insertValue(UserEntityChangeTable.kind)
                },
                shouldReturnGeneratedValues = upsertReturnsRows
            ) { entity ->
                this[UserEntityChangeTable.userId] = userId
                this[UserEntityChangeTable.entityType] = type
                this[UserEntityChangeTable.entityId] = entity
                this[UserEntityChangeTable.aspect] = aspect
                this[UserEntityChangeTable.kind] = EntityChangeKind.UPDATED
                this[UserEntityChangeTable.changedAt] = now
            }
            if (upsertReturnsRows) {
                upserted.mapTo(stamp.userRows) { it[UserEntityChangeTable.id].value }
            } else {
                UserEntityChangeTable
                    .select(UserEntityChangeTable.id)
                    .where { UserEntityChangeTable.userId eq userId }
                    .andWhere { UserEntityChangeTable.entityType eq type }
                    .andWhere { UserEntityChangeTable.aspect eq aspect }
                    .andWhere { UserEntityChangeTable.entityId inList chunk }
                    .mapTo(stamp.userRows) { it[UserEntityChangeTable.id].value }
            }
        }
    }

    private fun commitStamp(): CommitStamp {
        val transaction = TransactionManager.current()
        return transaction.getUserData(COMMIT_STAMP) ?: CommitStamp().also {
            transaction.putUserData(COMMIT_STAMP, it)
            transaction.registerInterceptor(it)
        }
    }

    private fun otherEditionsOf(albums: Set<UUID>): Set<UUID> =
        albums.chunked(CHUNK_SIZE).flatMapTo(mutableSetOf()) { chunk ->
            AlbumTable
                .select(AlbumTable.id)
                .where {
                    AlbumTable.versionGroupId inSubQuery AlbumTable
                        .select(AlbumTable.versionGroupId)
                        .where { AlbumTable.id inList chunk }
                }
                .map { it[AlbumTable.id].value }
        } - albums

    private fun scopesOf(type: EntityType, entities: Set<UUID>): Map<UUID, Set<Scope>> {
        val scopes = mutableMapOf<UUID, MutableSet<Scope>>()
        fun collect(scopeType: EntityType, entity: Column<EntityID<UUID>>, container: Column<EntityID<UUID>>) {
            for (chunk in entities.chunked(CHUNK_SIZE)) {
                entity.table.select(entity, container).where { entity inList chunk }.forEach {
                    scopes.getOrPut(it[entity].value) { mutableSetOf() }.add(Scope(scopeType, it[container].value))
                }
            }
        }
        when (type) {
            EntityType.SONG -> {
                collect(EntityType.ALBUM, SongTable.id, SongTable.albumId)
                collect(EntityType.ARTIST, SongArtistTable.songId, SongArtistTable.artistId)
            }

            EntityType.ALBUM -> collect(EntityType.ARTIST, AlbumArtistTable.albumId, AlbumArtistTable.artistId)
            else -> {}
        }
        return scopes
    }

    private fun containersOf(scopes: Map<UUID, Set<Scope>>): Map<EntityType, Set<UUID>> =
        scopes.values.flatten().groupBy({ it.type }, { it.id }).mapValues { it.value.toSet() }

    private fun holdersOf(type: EntityType, entities: Set<UUID>): Map<EntityType, Set<UUID>> = when (type) {
        EntityType.SONG -> mapOf(
            EntityType.USER_PLAYLIST to linked(UserPlaylistSongTable.songId, UserPlaylistSongTable.playlistId, entities),
            EntityType.PLAYLIST to linked(PlaylistSongTable.songId, PlaylistSongTable.playlistId, entities),
            EntityType.COLLECTION to linked(CollectionSongTable.songId, CollectionSongTable.collectionId, entities),
        )

        EntityType.ALBUM -> mapOf(
            EntityType.COLLECTION to linked(CollectionAlbumTable.albumId, CollectionAlbumTable.collectionId, entities)
        )

        EntityType.ARTIST -> mapOf(
            EntityType.COLLECTION to linked(CollectionArtistTable.artistId, CollectionArtistTable.collectionId, entities)
        )

        EntityType.USER_PLAYLIST -> mapOf(
            EntityType.COLLECTION to
                linked(CollectionPlaylistTable.playlistId, CollectionPlaylistTable.collectionId, entities)
        )

        else -> emptyMap()
    }

    private fun dependentsOf(type: EntityType, entities: Set<UUID>): Map<EntityType, Set<UUID>> = when (type) {
        EntityType.ALBUM -> mapOf(EntityType.SONG to linked(SongTable.albumId, SongTable.id, entities))
        EntityType.ARTIST -> mapOf(
            EntityType.SONG to linked(SongArtistTable.artistId, SongArtistTable.songId, entities),
            EntityType.ALBUM to linked(AlbumArtistTable.artistId, AlbumArtistTable.albumId, entities),
        )

        else -> emptyMap()
    }

    private fun linked(
        key: Column<EntityID<UUID>>,
        value: Column<EntityID<UUID>>,
        keys: Set<UUID>
    ): Set<UUID> = keys.chunked(CHUNK_SIZE).flatMapTo(mutableSetOf()) { chunk ->
        key.table.select(value).where { key inList chunk }.withDistinct().map { it[value].value }
    }

    private companion object {
        const val CHUNK_SIZE = 5000
        val COMMIT_STAMP = Key<CommitStamp>()
    }
}
