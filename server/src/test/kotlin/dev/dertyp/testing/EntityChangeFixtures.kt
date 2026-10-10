package dev.dertyp.testing

import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityType
import dev.dertyp.db.*
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.UUID

val entityChangeTables: Array<Table> = arrayOf(
    UserTable,
    ImageTable,
    AnimatedImageTable,
    AlbumVersionGroupTable,
    AlbumTable,
    ArtistTable,
    ArtistAliasTable,
    ArtistSplitAliasTable,
    ArtistMemberTable,
    SongTable,
    SongVariantTable,
    SongArtistTable,
    AlbumArtistTable,
    GenreTable,
    SongGenreTable,
    AlbumGenreTable,
    ArtistGenreTable,
    SongMusicBrainzTable,
    AlbumMusicBrainzTable,
    ArtistMusicBrainzTable,
    SongProviderTable,
    AlbumProviderTable,
    PlaylistTable,
    PlaylistSongTable,
    UserPlaylistTable,
    UserPlaylistSongTable,
    UserPlaylistShareTable,
    CollectionTable,
    CollectionSongTable,
    CollectionAlbumTable,
    CollectionArtistTable,
    CollectionPlaylistTable,
    EntityChangeTable,
    UserEntityChangeTable,
    EntityChangeScopeTable,
    EntityChangeTrackingTable,
    *allMusicBrainzTables,
)

data class RecordedChange(
    val type: EntityType,
    val entity: UUID,
    val part: EntityChangeAspect,
    val what: EntityChangeKind,
)

fun created(type: EntityType, entity: UUID) =
    RecordedChange(type, entity, EntityChangeAspect.DATA, EntityChangeKind.CREATED)

fun updated(type: EntityType, entity: UUID) =
    RecordedChange(type, entity, EntityChangeAspect.DATA, EntityChangeKind.UPDATED)

fun deleted(type: EntityType, entity: UUID) =
    RecordedChange(type, entity, EntityChangeAspect.DATA, EntityChangeKind.DELETED)

fun members(type: EntityType, entity: UUID) =
    RecordedChange(type, entity, EntityChangeAspect.MEMBERS, EntityChangeKind.UPDATED)

fun recordedChanges(database: Database): Set<RecordedChange> = transaction(database) {
    EntityChangeTable.selectAll().map {
        RecordedChange(
            it[EntityChangeTable.entityType],
            it[EntityChangeTable.entityId],
            it[EntityChangeTable.aspect],
            it[EntityChangeTable.kind]
        )
    }.toSet()
}

data class RecordedUserChange(
    val user: UUID,
    val type: EntityType,
    val entity: UUID,
    val part: EntityChangeAspect,
)

fun liked(user: UUID, type: EntityType, entity: UUID) =
    RecordedUserChange(user, type, entity, EntityChangeAspect.LIKE)

fun timecodes(user: UUID, song: UUID) =
    RecordedUserChange(user, EntityType.SONG, song, EntityChangeAspect.TIMECODES)

fun recordedUserChanges(database: Database): Set<RecordedUserChange> = transaction(database) {
    UserEntityChangeTable.selectAll().map {
        assertEquals(EntityChangeKind.UPDATED, it[UserEntityChangeTable.kind])
        RecordedUserChange(
            it[UserEntityChangeTable.userId].value,
            it[UserEntityChangeTable.entityType],
            it[UserEntityChangeTable.entityId],
            it[UserEntityChangeTable.aspect],
        )
    }.toSet()
}

fun recordedScopes(
    database: Database,
    type: EntityType,
    entity: UUID,
    part: EntityChangeAspect = EntityChangeAspect.DATA,
): Set<Pair<EntityType, UUID>> = transaction(database) {
    (EntityChangeTable innerJoin EntityChangeScopeTable)
        .selectAll()
        .where { EntityChangeTable.entityType eq type }
        .andWhere { EntityChangeTable.entityId eq entity }
        .andWhere { EntityChangeTable.aspect eq part }
        .map { it[EntityChangeScopeTable.scopeType] to it[EntityChangeScopeTable.scopeId] }
        .toSet()
}

fun clearRecordedChanges(database: Database) {
    transaction(database) {
        EntityChangeScopeTable.deleteAll()
        EntityChangeTable.deleteAll()
        UserEntityChangeTable.deleteAll()
    }
}
