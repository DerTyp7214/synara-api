package dev.dertyp.services

import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumGenreTable
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumProviderTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistAliasTable
import dev.dertyp.db.ArtistGenreTable
import dev.dertyp.db.ArtistMemberTable
import dev.dertyp.db.ArtistMusicBrainzTable
import dev.dertyp.db.ArtistSplitAliasTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionPlaylistTable
import dev.dertyp.db.CollectionSongTable
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongGenreTable
import dev.dertyp.db.SongMusicBrainzTable
import dev.dertyp.db.SongProviderTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID

class EntityStates internal constructor(
    internal val type: EntityType,
    internal val ids: Set<UUID>,
    internal val states: Map<UUID, EntityState>,
)

internal data class EntityState(
    val fields: List<Any?>,
    val links: Set<List<Any?>>,
    val containers: Set<Pair<EntityType, UUID>>,
    val members: Map<List<Any?>, Int>,
)

private const val STATE_CHUNK_SIZE = 5000

private val songFields: List<Column<*>> = listOf(
    SongTable.title,
    SongTable.titleTags,
    SongTable.albumId,
    SongTable.duration,
    SongTable.releaseDate,
    SongTable.lyrics,
    SongTable.explicit,
    SongTable.filePath,
    SongTable.format,
    SongTable.cover,
    SongTable.animatedCover,
    SongTable.originalUrl,
    SongTable.isrc,
    SongTable.trackNumber,
    SongTable.discNumber,
    SongTable.copyright,
    SongTable.sampleRate,
    SongTable.bitsPerSample,
    SongTable.bitRate,
    SongTable.fileSize,
    SongTable.audioStartMs,
    SongTable.channels,
)

private val albumTotalFields: List<Int> = listOf(SongTable.duration, SongTable.fileSize).map(songFields::indexOf)

private val albumFields: List<Column<*>> = listOf(
    AlbumTable.name,
    AlbumTable.titleTags,
    AlbumTable.releaseDate,
    AlbumTable.songCount,
    AlbumTable.cover,
    AlbumTable.animatedCover,
    AlbumTable.originalId,
    AlbumTable.barcode,
    AlbumTable.versionGroupId,
)

private val artistFields: List<Column<*>> = listOf(
    ArtistTable.name,
    ArtistTable.isGroup,
    ArtistTable.about,
    ArtistTable.image,
)

private val userPlaylistFields: List<Column<*>> = listOf(
    UserPlaylistTable.name,
    UserPlaylistTable.description,
    UserPlaylistTable.creator,
    UserPlaylistTable.imageId,
    UserPlaylistTable.origin,
    UserPlaylistTable.imageSource,
    UserPlaylistTable.coverStyle,
    UserPlaylistTable.coverSeed,
)

private val playlistFields: List<Column<*>> = listOf(
    PlaylistTable.name,
    PlaylistTable.imageId,
)

private val collectionFields: List<Column<*>> = listOf(
    CollectionTable.name,
    CollectionTable.description,
    CollectionTable.creator,
    CollectionTable.imageId,
    CollectionTable.imageSource,
    CollectionTable.coverStyle,
    CollectionTable.coverSeed,
)

fun entityStates(type: EntityType, ids: Collection<UUID>): EntityStates {
    val entities = ids.toSet()
    return EntityStates(type, entities, readStates(type, entities))
}

fun EntityChangeRecorder.recordChanges(before: EntityStates) {
    val after = readStates(before.type, before.ids)
    val changed = after.filter { (id, state) ->
        before.states[id]?.let {
            it.fields != state.fields || it.links != state.links || it.containers != state.containers
        } == true
    }
    val refilled = after.filter { (id, state) ->
        val previous = before.states[id]
        if (previous == null) state.members.isNotEmpty() else previous.members != state.members
    }
    val moved = changed.flatMap { (id, state) ->
        val previous = before.states.getValue(id).containers
        (previous - state.containers) + (state.containers - previous)
    }

    val rescoped = changed.filter { (id, state) -> before.states.getValue(id).containers != state.containers }.keys

    created(before.type, after.keys - before.states.keys)
    updated(before.type, changed.keys - rescoped)
    relinked(before.type, rescoped)
    membersChanged(before.type, refilled.keys)
    moved.groupBy({ it.first }, { it.second }).forEach { (containerType, containers) ->
        membersChanged(containerType, containers)
    }
    if (before.type == EntityType.SONG) {
        val resized = changed.filter { (id, state) ->
            val previous = before.states.getValue(id).fields
            albumTotalFields.any { previous[it] != state.fields[it] }
        }
        membersChanged(
            EntityType.ALBUM,
            resized.values.flatMap { state -> state.containers.filter { it.first == EntityType.ALBUM }.map { it.second } }
        )
    }
}

private fun readStates(type: EntityType, entities: Set<UUID>): Map<UUID, EntityState> {
    val fields = mutableMapOf<UUID, List<Any?>>()
    val links = mutableMapOf<UUID, MutableSet<List<Any?>>>()
    val containers = mutableMapOf<UUID, MutableSet<Pair<EntityType, UUID>>>()
    val members = mutableMapOf<UUID, MutableMap<List<Any?>, Int>>()
    val chunks = entities.chunked(STATE_CHUNK_SIZE)

    fun own(id: Column<EntityID<UUID>>, columns: List<Column<*>>) {
        for (chunk in chunks) {
            id.table.select(listOf(id) + columns).where { id inList chunk }.forEach { row ->
                fields[row[id].value] = columns.map { row[it] }
            }
        }
    }

    fun linked(owner: Column<EntityID<UUID>>, vararg columns: Column<*>) {
        for (chunk in chunks) {
            owner.table.select(listOf(owner) + columns).where { owner inList chunk }.forEach { row ->
                val values = columns.map { row[it] }
                if (values.any { it != null }) {
                    links.getOrPut(row[owner].value) { mutableSetOf() }.add(listOf(owner.table.tableName) + values)
                }
            }
        }
    }

    fun contained(containerType: EntityType, owner: Column<EntityID<UUID>>, container: Column<EntityID<UUID>>) {
        for (chunk in chunks) {
            owner.table.select(owner, container).where { owner inList chunk }.forEach { row ->
                containers.getOrPut(row[owner].value) { mutableSetOf() }.add(containerType to row[container].value)
            }
        }
    }

    fun member(owner: Column<EntityID<UUID>>, vararg columns: Column<*>) {
        for (chunk in chunks) {
            owner.table.select(listOf(owner) + columns).where { owner inList chunk }.forEach { row ->
                members.getOrPut(row[owner].value) { mutableMapOf() }
                    .merge(listOf(owner.table.tableName) + columns.map { row[it] }, 1, Int::plus)
            }
        }
    }

    when (type) {
        EntityType.SONG -> {
            own(SongTable.id, songFields)
            contained(EntityType.ALBUM, SongTable.id, SongTable.albumId)
            contained(EntityType.ARTIST, SongArtistTable.songId, SongArtistTable.artistId)
            linked(
                SongArtistTable.songId,
                SongArtistTable.artistId,
                SongArtistTable.creditedAliasId,
                SongArtistTable.position,
                SongArtistTable.joinPhrase
            )
            linked(SongGenreTable.songId, SongGenreTable.genreId)
            linked(SongMusicBrainzTable.songId, SongMusicBrainzTable.musicBrainzId)
            linked(
                SongVariantTable.songId,
                SongVariantTable.kind,
                SongVariantTable.path,
                SongVariantTable.codec,
                SongVariantTable.sampleRate,
                SongVariantTable.bitsPerSample,
                SongVariantTable.channels,
                SongVariantTable.bitRate,
                SongVariantTable.fileSize
            )
            linked(
                SongProviderTable.songId,
                SongProviderTable.provider,
                SongProviderTable.externalId,
                SongProviderTable.type,
                SongProviderTable.rawUrl
            )
        }

        EntityType.ALBUM -> {
            own(AlbumTable.id, albumFields)
            contained(EntityType.ARTIST, AlbumArtistTable.albumId, AlbumArtistTable.artistId)
            linked(
                AlbumArtistTable.albumId,
                AlbumArtistTable.artistId,
                AlbumArtistTable.creditedAliasId,
                AlbumArtistTable.position,
                AlbumArtistTable.joinPhrase
            )
            linked(AlbumGenreTable.albumId, AlbumGenreTable.genreId)
            linked(AlbumMusicBrainzTable.albumId, AlbumMusicBrainzTable.musicBrainzId)
            linked(
                AlbumProviderTable.albumId,
                AlbumProviderTable.provider,
                AlbumProviderTable.externalId,
                AlbumProviderTable.type,
                AlbumProviderTable.rawUrl
            )
        }

        EntityType.ARTIST -> {
            own(ArtistTable.id, artistFields)
            linked(ArtistMemberTable.groupId, ArtistMemberTable.artistId)
            linked(ArtistGenreTable.artistId, ArtistGenreTable.genreId)
            linked(ArtistMusicBrainzTable.artistId, ArtistMusicBrainzTable.musicBrainzId)
            linked(ArtistAliasTable.artistId, ArtistAliasTable.name)
            linked(ArtistSplitAliasTable.artistId, ArtistSplitAliasTable.name)
        }

        EntityType.USER_PLAYLIST -> {
            own(UserPlaylistTable.id, userPlaylistFields)
            member(UserPlaylistSongTable.playlistId, UserPlaylistSongTable.songId, UserPlaylistSongTable.addedAt)
        }

        EntityType.PLAYLIST -> {
            own(PlaylistTable.id, playlistFields)
            member(PlaylistSongTable.playlistId, PlaylistSongTable.songId, PlaylistSongTable.position)
        }

        EntityType.COLLECTION -> {
            own(CollectionTable.id, collectionFields)
            member(CollectionSongTable.collectionId, CollectionSongTable.songId, CollectionSongTable.addedAt)
            member(CollectionAlbumTable.collectionId, CollectionAlbumTable.albumId, CollectionAlbumTable.addedAt)
            member(CollectionArtistTable.collectionId, CollectionArtistTable.artistId, CollectionArtistTable.addedAt)
            member(
                CollectionPlaylistTable.collectionId,
                CollectionPlaylistTable.playlistId,
                CollectionPlaylistTable.addedAt
            )
        }

        else -> {}
    }

    return fields.mapValues { (id, values) ->
        EntityState(values, links[id].orEmpty(), containers[id].orEmpty(), members[id].orEmpty())
    }
}
