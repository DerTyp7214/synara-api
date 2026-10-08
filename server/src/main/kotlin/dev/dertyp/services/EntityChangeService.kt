package dev.dertyp.services

import dev.dertyp.config.EntityChangeConfig
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityChange
import dev.dertyp.data.EntityChangeWindow
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.ArtistMemberTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionPlaylistTable
import dev.dertyp.db.CollectionSongTable
import dev.dertyp.db.EntityChangeRows
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.EntityChangeTrackingTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserPlaylistSongTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class EntityChangeService(private val config: EntityChangeConfig) {
    companion object {
        const val SERVER_TIME_MARGIN_MS = 60_000L
        const val STREAM_CHUNK_SIZE = 1000
    }

    private class Cursor(
        private val table: EntityChangeRows,
        private val since: Long,
        private val until: Long,
        private val filter: () -> Op<Boolean>
    ) {
        private val buffer = ArrayDeque<EntityChange>()
        private var last: Pair<Long, UUID>? = null
        private var exhausted = false

        suspend fun peek(): EntityChange? {
            if (buffer.isEmpty() && !exhausted) fill()
            return buffer.firstOrNull()
        }

        fun take(): EntityChange = buffer.removeFirst()

        private suspend fun fill() {
            val after = last
            val rows = dbQuery {
                table
                    .select(table.id, table.entityType, table.entityId, table.aspect, table.kind, table.changedAt)
                    .where { table.changedAt greaterEq since }
                    .andWhere { table.changedAt lessEq until }
                    .andWhere { filter() }
                    .apply {
                        if (after != null) andWhere {
                            (table.changedAt greater after.first) or
                                ((table.changedAt eq after.first) and (table.id greater after.second))
                        }
                    }
                    .orderBy(table.changedAt to SortOrder.ASC, table.id to SortOrder.ASC)
                    .limit(STREAM_CHUNK_SIZE)
                    .map {
                        it[table.id].value to EntityChange(
                            entityType = it[table.entityType],
                            entityId = it[table.entityId],
                            aspect = it[table.aspect],
                            kind = it[table.kind],
                            changedAt = it[table.changedAt]
                        )
                    }
            }
            rows.lastOrNull()?.let { (id, change) -> last = change.changedAt to id }
            exhausted = rows.size < STREAM_CHUNK_SIZE
            rows.mapTo(buffer) { it.second }
        }
    }

    suspend fun getWindow(): EntityChangeWindow {
        val now = Clock.System.now().toEpochMilliseconds()
        return EntityChangeWindow(
            serverTime = now - SERVER_TIME_MARGIN_MS,
            availableSince = maxOf(now - config.retentionDays.days.inWholeMilliseconds, trackingStartedAt())
        )
    }

    suspend fun trackingStartedAt(): Long = dbQuery {
        val stored = EntityChangeTrackingTable
            .select(EntityChangeTrackingTable.startedAt)
            .where { EntityChangeTrackingTable.id eq EntityChangeTrackingTable.ROW_ID }
        stored.singleOrNull()?.let { return@dbQuery it[EntityChangeTrackingTable.startedAt] }

        EntityChangeTrackingTable.insertIgnore {
            it[id] = EntityChangeTrackingTable.ROW_ID
            it[startedAt] = Clock.System.now().toEpochMilliseconds()
        }
        stored.copy().single()[EntityChangeTrackingTable.startedAt]
    }

    fun allChanges(userId: UUID, since: Long): Flow<EntityChange> =
        pull(userId, since, library = { Op.TRUE }, user = { Op.TRUE })

    fun byArtist(userId: UUID, artistId: UUID, since: Long): Flow<EntityChange> {
        val songs = SongArtistTable.select(SongArtistTable.songId).where { SongArtistTable.artistId eq artistId }
        val albums = listOf(
            AlbumArtistTable.select(AlbumArtistTable.albumId).where { AlbumArtistTable.artistId eq artistId },
            albumsOfSongs(songs)
        )
        val groupMembers = ArtistMemberTable
            .select(ArtistMemberTable.artistId)
            .where { ArtistMemberTable.groupId eq artistId }
        val related = Related(
            songs = listOf(songs),
            albums = albums,
            artists = withGroupMembers(albums.map(::artistsOfAlbums) + listOf(artistsOfSongs(songs))) + listOf(groupMembers)
        )
        return pull(
            userId,
            since,
            library = {
                own(EntityChangeTable, EntityType.ARTIST, artistId) or
                    scopedTo(EntityType.ARTIST, artistId) or
                    related.rows(EntityChangeTable)
            },
            user = { own(UserEntityChangeTable, EntityType.ARTIST, artistId) or related.rows(UserEntityChangeTable) }
        )
    }

    fun byAlbum(userId: UUID, albumId: UUID, since: Long): Flow<EntityChange> {
        val songs = SongTable.select(SongTable.id).where { SongTable.albumId eq albumId }
        val albumArtists = AlbumArtistTable
            .select(AlbumArtistTable.artistId)
            .where { AlbumArtistTable.albumId eq albumId }
        val related = Related(
            songs = listOf(songs),
            albums = emptyList(),
            artists = withGroupMembers(listOf(albumArtists, artistsOfSongs(songs)))
        )
        return pull(
            userId,
            since,
            library = {
                own(EntityChangeTable, EntityType.ALBUM, albumId) or
                    scopedTo(EntityType.ALBUM, albumId) or
                    related.rows(EntityChangeTable)
            },
            user = { own(UserEntityChangeTable, EntityType.ALBUM, albumId) or related.rows(UserEntityChangeTable) }
        )
    }

    fun byPlaylist(userId: UUID, playlistId: UUID, since: Long): Flow<EntityChange> {
        val songs = listOf(
            UserPlaylistSongTable
                .select(UserPlaylistSongTable.songId)
                .where { UserPlaylistSongTable.playlistId eq playlistId },
            PlaylistSongTable
                .select(PlaylistSongTable.songId)
                .where { PlaylistSongTable.playlistId eq playlistId }
        )
        val albums = songs.map(::albumsOfSongs)
        val related = Related(
            songs = songs,
            albums = albums,
            artists = withGroupMembers(songs.map(::artistsOfSongs) + albums.map(::artistsOfAlbums))
        )
        return pull(
            userId,
            since,
            library = {
                ((EntityChangeTable.entityType inList listOf(EntityType.USER_PLAYLIST, EntityType.PLAYLIST)) and
                    (EntityChangeTable.entityId eq playlistId)) or
                    related.rows(EntityChangeTable)
            },
            user = { related.rows(UserEntityChangeTable) }
        )
    }

    fun byCollection(userId: UUID, collectionId: UUID, since: Long): Flow<EntityChange> {
        val memberSongs = CollectionSongTable
            .select(CollectionSongTable.songId)
            .where { CollectionSongTable.collectionId eq collectionId }
        val memberAlbums = CollectionAlbumTable
            .select(CollectionAlbumTable.albumId)
            .where { CollectionAlbumTable.collectionId eq collectionId }
        val memberArtists = CollectionArtistTable
            .select(CollectionArtistTable.artistId)
            .where { CollectionArtistTable.collectionId eq collectionId }
        val memberPlaylists = CollectionPlaylistTable
            .select(CollectionPlaylistTable.playlistId)
            .where { CollectionPlaylistTable.collectionId eq collectionId }

        val expandedAlbums = listOf(
            memberAlbums,
            AlbumArtistTable.select(AlbumArtistTable.albumId).where { AlbumArtistTable.artistId inSubQuery memberArtists }
        )
        val songsOutsideAlbums = listOf(
            memberSongs,
            SongArtistTable.select(SongArtistTable.songId).where { SongArtistTable.artistId inSubQuery memberArtists },
            UserPlaylistSongTable
                .select(UserPlaylistSongTable.songId)
                .where { UserPlaylistSongTable.playlistId inSubQuery memberPlaylists }
        )
        val songs = songsOutsideAlbums + expandedAlbums.map { albums ->
            SongTable.select(SongTable.id).where { SongTable.albumId inSubQuery albums }
        }
        val albums = expandedAlbums + songsOutsideAlbums.map(::albumsOfSongs)
        val related = Related(
            songs = songs,
            albums = albums,
            artists = withGroupMembers(listOf(memberArtists) + songs.map(::artistsOfSongs) + albums.map(::artistsOfAlbums))
        )
        return pull(
            userId,
            since,
            library = {
                own(EntityChangeTable, EntityType.COLLECTION, collectionId) or
                    members(EntityChangeTable, EntityType.USER_PLAYLIST, memberPlaylists) or
                    related.rows(EntityChangeTable)
            },
            user = {
                members(UserEntityChangeTable, EntityType.USER_PLAYLIST, memberPlaylists) or
                    related.rows(UserEntityChangeTable)
            }
        )
    }

    suspend fun cleanup(): Map<String, Any?> {
        val cutoff = Clock.System.now().toEpochMilliseconds() - config.retentionDays.days.inWholeMilliseconds
        val (libraryRows, userRows) = dbQuery {
            EntityChangeTable.deleteWhere { EntityChangeTable.changedAt less cutoff } to
                UserEntityChangeTable.deleteWhere { UserEntityChangeTable.changedAt less cutoff }
        }
        return mapOf("deletedEntityChanges" to libraryRows, "deletedUserEntityChanges" to userRows)
    }

    private fun pull(
        userId: UUID,
        since: Long,
        library: () -> Op<Boolean>,
        user: () -> Op<Boolean>
    ): Flow<EntityChange> = flow {
        val until = Clock.System.now().toEpochMilliseconds()
        val libraryRows = Cursor(EntityChangeTable, since, until, library)
        val userRows = Cursor(UserEntityChangeTable, since, until) {
            (UserEntityChangeTable.userId eq userId) and user()
        }
        while (true) {
            val libraryNext = libraryRows.peek()
            val userNext = userRows.peek()
            emit(
                when {
                    libraryNext == null && userNext == null -> break
                    userNext == null || (libraryNext != null && libraryNext.changedAt <= userNext.changedAt) ->
                        libraryRows.take()

                    else -> userRows.take()
                }
            )
        }
    }

    private inner class Related(val songs: List<Query>, val albums: List<Query>, val artists: List<Query>) {
        fun rows(table: EntityChangeRows): Op<Boolean> =
            (songs.map { members(table, EntityType.SONG, it) } +
                albums.map { members(table, EntityType.ALBUM, it) } +
                artists.map { members(table, EntityType.ARTIST, it) })
                .reduce { filter, next -> filter or next }
    }

    private fun albumsOfSongs(songs: Query): Query =
        SongTable.select(SongTable.albumId).where { SongTable.id inSubQuery songs }

    private fun artistsOfSongs(songs: Query): Query =
        SongArtistTable.select(SongArtistTable.artistId).where { SongArtistTable.songId inSubQuery songs }

    private fun artistsOfAlbums(albums: Query): Query =
        AlbumArtistTable.select(AlbumArtistTable.artistId).where { AlbumArtistTable.albumId inSubQuery albums }

    private fun withGroupMembers(artists: List<Query>): List<Query> = artists + artists.map { groups ->
        ArtistMemberTable.select(ArtistMemberTable.artistId).where { ArtistMemberTable.groupId inSubQuery groups }
    }

    private fun own(table: EntityChangeRows, type: EntityType, id: UUID): Op<Boolean> =
        (table.entityType eq type) and (table.entityId eq id)

    private fun members(table: EntityChangeRows, type: EntityType, ids: Query): Op<Boolean> =
        (table.entityType eq type) and (table.entityId inSubQuery ids)

    private fun scopedTo(type: EntityType, id: UUID): Op<Boolean> =
        EntityChangeTable.id inSubQuery EntityChangeScopeTable
            .select(EntityChangeScopeTable.changeId)
            .where { EntityChangeScopeTable.scopeType eq type }
            .andWhere { EntityChangeScopeTable.scopeId eq id }
}
