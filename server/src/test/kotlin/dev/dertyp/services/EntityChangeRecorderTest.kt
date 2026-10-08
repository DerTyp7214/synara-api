package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionArtistTable
import dev.dertyp.db.CollectionPlaylistTable
import dev.dertyp.db.CollectionSongTable
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.EntityChangeTrackingTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PlaylistSongTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.db.UserTable
import dev.dertyp.testing.insertAlbum
import dev.dertyp.testing.insertArtist
import dev.dertyp.testing.insertSong
import dev.dertyp.testing.insertUser
import dev.dertyp.testing.linkSongArtist
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.targetTables
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EntityChangeRecorderTest : KoinTest {
    private data class Recorded(
        val type: EntityType,
        val entity: UUID,
        val part: EntityChangeAspect,
        val what: EntityChangeKind
    )

    private class ChangeRowReads : StatementInterceptor {
        var count = 0

        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
            val tables = (context.statement as? Query)?.set?.source?.targetTables().orEmpty()
            if (EntityChangeTable in tables || UserEntityChangeTable in tables) count++
        }
    }

    private lateinit var database: Database
    private val recorder = EntityChangeRecorder()

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }
        database = TestDatabase.connect(
            dialect, "entity_change_recorder_test",
            UserTable,
            ImageTable,
            AlbumTable,
            ArtistTable,
            SongTable, SongVariantTable,
            SongArtistTable,
            AlbumArtistTable,
            PlaylistTable,
            PlaylistSongTable,
            UserPlaylistTable,
            UserPlaylistSongTable,
            CollectionTable,
            CollectionSongTable,
            CollectionAlbumTable,
            CollectionArtistTable,
            CollectionPlaylistTable,
            EntityChangeTable,
            UserEntityChangeTable,
            EntityChangeScopeTable,
            EntityChangeTrackingTable,
        )
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun <T> db(block: () -> T): T = transaction(database) { block() }

    private fun libraryRows(): Set<Recorded> = db {
        EntityChangeTable.selectAll().map {
            Recorded(
                it[EntityChangeTable.entityType],
                it[EntityChangeTable.entityId],
                it[EntityChangeTable.aspect],
                it[EntityChangeTable.kind]
            )
        }.also { rows -> assertEquals(rows.size, rows.toSet().size) }.toSet()
    }

    private fun userRows(): Set<Pair<UUID, Recorded>> = db {
        UserEntityChangeTable.selectAll().map {
            it[UserEntityChangeTable.userId].value to Recorded(
                it[UserEntityChangeTable.entityType],
                it[UserEntityChangeTable.entityId],
                it[UserEntityChangeTable.aspect],
                it[UserEntityChangeTable.kind]
            )
        }.toSet()
    }

    private fun scopes(
        type: EntityType,
        entity: UUID,
        part: EntityChangeAspect = EntityChangeAspect.DATA
    ): Set<Pair<EntityType, UUID>> = db {
        (EntityChangeTable innerJoin EntityChangeScopeTable)
            .selectAll()
            .where { EntityChangeTable.entityType eq type }
            .andWhere { EntityChangeTable.entityId eq entity }
            .andWhere { EntityChangeTable.aspect eq part }
            .map { it[EntityChangeScopeTable.scopeType] to it[EntityChangeScopeTable.scopeId] }
            .toSet()
    }

    private fun changedAt(type: EntityType, entity: UUID, part: EntityChangeAspect): Long = db {
        EntityChangeTable.selectAll()
            .where { EntityChangeTable.entityType eq type }
            .andWhere { EntityChangeTable.entityId eq entity }
            .andWhere { EntityChangeTable.aspect eq part }
            .single()[EntityChangeTable.changedAt]
    }

    private fun data(type: EntityType, entity: UUID, what: EntityChangeKind) =
        Recorded(type, entity, EntityChangeAspect.DATA, what)

    private fun members(type: EntityType, entity: UUID) =
        Recorded(type, entity, EntityChangeAspect.MEMBERS, EntityChangeKind.UPDATED)

    private fun linkAlbumArtist(album: UUID, artist: UUID) {
        AlbumArtistTable.insert {
            it[albumId] = album
            it[artistId] = artist
        }
    }

    private fun insertUserPlaylist(owner: UUID, songs: List<UUID> = emptyList()): UUID {
        val playlist = UUID.randomUUID()
        UserPlaylistTable.insert {
            it[id] = playlist
            it[name] = "Playlist"
            it[description] = ""
            it[creator] = owner
        }
        for (song in songs) {
            UserPlaylistSongTable.insert {
                it[playlistId] = playlist
                it[songId] = song
            }
        }
        return playlist
    }

    private fun insertPlaylist(songs: List<UUID> = emptyList()): UUID {
        val playlist = UUID.randomUUID()
        PlaylistTable.insert {
            it[id] = playlist
            it[name] = "Global"
        }
        songs.forEachIndexed { index, song ->
            PlaylistSongTable.insert {
                it[playlistId] = playlist
                it[songId] = song
                it[position] = index
            }
        }
        return playlist
    }

    private fun insertCollection(
        owner: UUID,
        songs: List<UUID> = emptyList(),
        albums: List<UUID> = emptyList(),
        artists: List<UUID> = emptyList(),
        playlists: List<UUID> = emptyList()
    ): UUID {
        val collection = UUID.randomUUID()
        CollectionTable.insert {
            it[id] = collection
            it[name] = "Collection"
            it[creator] = owner
        }
        for (song in songs) CollectionSongTable.insert {
            it[collectionId] = collection
            it[songId] = song
        }
        for (album in albums) CollectionAlbumTable.insert {
            it[collectionId] = collection
            it[albumId] = album
        }
        for (artist in artists) CollectionArtistTable.insert {
            it[collectionId] = collection
            it[artistId] = artist
        }
        for (playlist in playlists) CollectionPlaylistTable.insert {
            it[collectionId] = collection
            it[playlistId] = playlist
        }
        return collection
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a created song is recorded with its scopes and marks its album and artists`(dialect: DbDialect) {
        setup(dialect)
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val featured = db { insertArtist("Featured") }
        val albumArtist = db { insertArtist("Album Artist") }
        val song = db {
            linkAlbumArtist(album, albumArtist)
            insertSong(album).also {
                linkSongArtist(it, artist)
                linkSongArtist(it, featured)
            }
        }

        db { recorder.created(EntityType.SONG, listOf(song, song)) }

        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.CREATED),
                members(EntityType.ALBUM, album),
                members(EntityType.ARTIST, artist),
                members(EntityType.ARTIST, featured),
            ),
            libraryRows()
        )
        assertEquals(
            setOf(EntityType.ALBUM to album, EntityType.ARTIST to artist, EntityType.ARTIST to featured),
            scopes(EntityType.SONG, song)
        )
        assertEquals(
            setOf(EntityType.ARTIST to albumArtist),
            scopes(EntityType.ALBUM, album, EntityChangeAspect.MEMBERS)
        )
        assertEquals(emptySet<Pair<UUID, Recorded>>(), userRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a created album marks its artists and other types only record themselves`(dialect: DbDialect) {
        setup(dialect)
        val owner = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val loneArtist = db { insertArtist("Lone") }
        db { linkAlbumArtist(album, artist) }
        val userPlaylist = db { insertUserPlaylist(owner) }
        val playlist = db { insertPlaylist() }
        val collection = db { insertCollection(owner) }

        db {
            recorder.created(EntityType.ALBUM, listOf(album))
            recorder.created(EntityType.ARTIST, listOf(loneArtist))
            recorder.created(EntityType.USER_PLAYLIST, listOf(userPlaylist))
            recorder.created(EntityType.PLAYLIST, listOf(playlist))
            recorder.created(EntityType.COLLECTION, listOf(collection))
            recorder.updated(EntityType.USER_PLAYLIST, listOf(userPlaylist), containersChanged = true)
            recorder.created(EntityType.SONG, emptyList())
        }

        assertEquals(
            setOf(
                data(EntityType.ALBUM, album, EntityChangeKind.CREATED),
                members(EntityType.ARTIST, artist),
                data(EntityType.ARTIST, loneArtist, EntityChangeKind.CREATED),
                data(EntityType.USER_PLAYLIST, userPlaylist, EntityChangeKind.CREATED),
                data(EntityType.PLAYLIST, playlist, EntityChangeKind.CREATED),
                data(EntityType.COLLECTION, collection, EntityChangeKind.CREATED),
            ),
            libraryRows()
        )
        assertEquals(setOf(EntityType.ARTIST to artist), scopes(EntityType.ALBUM, album))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `aspects of one entity coexist and each keeps one row`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val album = db { insertAlbum() }
        val song = db { insertSong(album) }

        db {
            recorder.updated(EntityType.SONG, listOf(song))
            recorder.likesChanged(user, EntityType.SONG, listOf(song))
            recorder.timecodesChanged(user, listOf(song))
            recorder.updated(EntityType.ALBUM, listOf(album))
            recorder.membersChanged(EntityType.ALBUM, listOf(album))
            recorder.likesChanged(user, EntityType.ALBUM, listOf(album))
            recorder.updated(EntityType.SONG, listOf(song))
            recorder.likesChanged(user, EntityType.SONG, listOf(song))
            recorder.timecodesChanged(user, listOf(song))
            recorder.membersChanged(EntityType.ALBUM, listOf(album))
        }

        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.UPDATED),
                data(EntityType.ALBUM, album, EntityChangeKind.UPDATED),
                members(EntityType.ALBUM, album),
            ),
            libraryRows()
        )
        assertEquals(
            setOf(
                user to Recorded(EntityType.SONG, song, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
                user to Recorded(EntityType.SONG, song, EntityChangeAspect.TIMECODES, EntityChangeKind.UPDATED),
                user to Recorded(EntityType.ALBUM, album, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
            ),
            userRows()
        )
        assertEquals(3L, db { UserEntityChangeTable.selectAll().count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a later change replaces the time and the kind of the earlier row`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val artist = db { insertArtist() }
        fun kind() = libraryRows().single { it.part == EntityChangeAspect.DATA }.what
        fun age() = db {
            EntityChangeTable.update { it[changedAt] = 1 }
            UserEntityChangeTable.update { it[changedAt] = 1 }
        }

        val before = System.currentTimeMillis()
        db { recorder.updated(EntityType.ARTIST, listOf(artist)) }
        assertEquals(EntityChangeKind.UPDATED, kind())
        assertTrue(changedAt(EntityType.ARTIST, artist, EntityChangeAspect.DATA) >= before)

        age()
        db { recorder.created(EntityType.ARTIST, listOf(artist)) }
        assertEquals(EntityChangeKind.CREATED, kind())
        assertTrue(changedAt(EntityType.ARTIST, artist, EntityChangeAspect.DATA) >= before)

        age()
        db { recorder.updated(EntityType.ARTIST, listOf(artist)) }
        assertEquals(EntityChangeKind.CREATED, kind())
        assertTrue(changedAt(EntityType.ARTIST, artist, EntityChangeAspect.DATA) >= before)

        age()
        db { recorder.deleting(EntityType.ARTIST, listOf(artist)) }
        assertEquals(EntityChangeKind.DELETED, kind())
        assertTrue(changedAt(EntityType.ARTIST, artist, EntityChangeAspect.DATA) >= before)

        age()
        db { recorder.updated(EntityType.ARTIST, listOf(artist)) }
        assertEquals(EntityChangeKind.UPDATED, kind())

        db { recorder.deleting(EntityType.ARTIST, listOf(artist)) }
        db { recorder.created(EntityType.ARTIST, listOf(artist)) }
        assertEquals(EntityChangeKind.CREATED, kind())
        assertEquals(1L, db { EntityChangeTable.selectAll().count() })

        db { recorder.likesChanged(user, EntityType.ARTIST, listOf(artist)) }
        age()
        db { recorder.likesChanged(user, EntityType.ARTIST, listOf(artist)) }
        assertTrue(db { UserEntityChangeTable.selectAll().single()[UserEntityChangeTable.changedAt] } >= before)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a deletion collapses the aspects in both tables and keeps the scopes`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val other = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        val kept = db { insertSong(album) }
        db { linkAlbumArtist(album, artist) }

        db {
            recorder.created(EntityType.SONG, listOf(song, kept))
            recorder.updated(EntityType.ALBUM, listOf(album))
            recorder.likesChanged(user, EntityType.SONG, listOf(song, kept))
            recorder.likesChanged(other, EntityType.SONG, listOf(song))
            recorder.timecodesChanged(other, listOf(song))
            recorder.likesChanged(user, EntityType.ALBUM, listOf(album))
        }
        db {
            recorder.deleting(EntityType.SONG, listOf(song))
            SongTable.deleteWhere { SongTable.id eq song }
        }

        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.DELETED),
                data(EntityType.SONG, kept, EntityChangeKind.CREATED),
                data(EntityType.ALBUM, album, EntityChangeKind.UPDATED),
                members(EntityType.ALBUM, album),
                members(EntityType.ARTIST, artist),
            ),
            libraryRows()
        )
        assertEquals(
            setOf(
                user to Recorded(EntityType.SONG, kept, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
                user to Recorded(EntityType.ALBUM, album, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
            ),
            userRows()
        )
        assertEquals(setOf(EntityType.ALBUM to album, EntityType.ARTIST to artist), scopes(EntityType.SONG, song))

        db { recorder.deleting(EntityType.SONG, listOf(song)) }
        assertEquals(setOf(EntityType.ALBUM to album, EntityType.ARTIST to artist), scopes(EntityType.SONG, song))

        db {
            recorder.deleting(EntityType.SONG, listOf(kept))
            SongTable.deleteWhere { SongTable.id eq kept }
            recorder.deleting(EntityType.ALBUM, listOf(album))
            AlbumTable.deleteWhere { AlbumTable.id eq album }
        }

        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.DELETED),
                data(EntityType.SONG, kept, EntityChangeKind.DELETED),
                data(EntityType.ALBUM, album, EntityChangeKind.DELETED),
                members(EntityType.ARTIST, artist),
            ),
            libraryRows()
        )
        assertEquals(emptySet<Pair<UUID, Recorded>>(), userRows())
        assertEquals(setOf(EntityType.ARTIST to artist), scopes(EntityType.ALBUM, album))
        assertEquals(setOf(EntityType.ALBUM to album), scopes(EntityType.SONG, kept))
        assertEquals(
            4L,
            db { EntityChangeScopeTable.selectAll().count() },
            "the scope rows of the removed MEMBERS row are gone"
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `scopes are rewritten when the entity changes and both albums of a moved song are marked`(dialect: DbDialect) {
        setup(dialect)
        val from = db { insertAlbum("From") }
        val to = db { insertAlbum("To") }
        val oldArtist = db { insertArtist("Old") }
        val newArtist = db { insertArtist("New") }
        val song = db { insertSong(from).also { linkSongArtist(it, oldArtist) } }

        db { recorder.updated(EntityType.SONG, listOf(song)) }
        assertEquals(setOf(EntityType.ALBUM to from, EntityType.ARTIST to oldArtist), scopes(EntityType.SONG, song))
        assertEquals(setOf(data(EntityType.SONG, song, EntityChangeKind.UPDATED)), libraryRows())

        db {
            recorder.leavingContainers(EntityType.SONG, listOf(song))
            SongTable.update({ SongTable.id eq song }) { it[albumId] = to }
            SongArtistTable.deleteWhere { SongArtistTable.songId eq song }
            linkSongArtist(song, newArtist)
            recorder.updated(EntityType.SONG, listOf(song), containersChanged = true)
        }

        assertEquals(setOf(EntityType.ALBUM to to, EntityType.ARTIST to newArtist), scopes(EntityType.SONG, song))
        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.UPDATED),
                members(EntityType.ALBUM, from),
                members(EntityType.ALBUM, to),
                members(EntityType.ARTIST, oldArtist),
                members(EntityType.ARTIST, newArtist),
            ),
            libraryRows()
        )
    }

    private fun markScopes(type: EntityType, entity: UUID): Pair<EntityType, UUID> = db {
        val marker = EntityType.ARTIST to UUID.randomUUID()
        val change = EntityChangeTable.selectAll()
            .where { EntityChangeTable.entityType eq type }
            .andWhere { EntityChangeTable.entityId eq entity }
            .andWhere { EntityChangeTable.aspect eq EntityChangeAspect.DATA }
            .single()[EntityChangeTable.id]
        EntityChangeScopeTable.insert {
            it[changeId] = change
            it[scopeType] = marker.first
            it[scopeId] = marker.second
        }
        marker
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an update without a container change leaves the scope rows of a song and an album untouched`(
        dialect: DbDialect
    ) {
        setup(dialect)
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        db { linkAlbumArtist(album, artist) }
        db {
            recorder.created(EntityType.ALBUM, listOf(album))
            recorder.created(EntityType.SONG, listOf(song))
        }
        val songMarker = markScopes(EntityType.SONG, song)
        val albumMarker = markScopes(EntityType.ALBUM, album)
        db {
            EntityChangeTable.update({ EntityChangeTable.aspect eq EntityChangeAspect.DATA }) { it[changedAt] = 1L }
        }
        val rowsBefore = libraryRows()

        db {
            recorder.updated(EntityType.SONG, listOf(song))
            recorder.updated(EntityType.ALBUM, listOf(album))
        }

        assertEquals(
            setOf(EntityType.ALBUM to album, EntityType.ARTIST to artist, songMarker),
            scopes(EntityType.SONG, song)
        )
        assertEquals(setOf(EntityType.ARTIST to artist, albumMarker), scopes(EntityType.ALBUM, album))
        assertEquals(rowsBefore, libraryRows())
        assertTrue(changedAt(EntityType.SONG, song, EntityChangeAspect.DATA) > 1L)
        assertTrue(changedAt(EntityType.ALBUM, album, EntityChangeAspect.DATA) > 1L)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an update without a container change gives scopes to a row that has none yet`(dialect: DbDialect) {
        setup(dialect)
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val unrecorded = db { insertSong(album, "Unrecorded").also { linkSongArtist(it, artist) } }
        val scopeless = db { insertSong(album, "Scopeless").also { linkSongArtist(it, artist) } }
        val scopedAlready = db { insertSong(album, "Scoped").also { linkSongArtist(it, artist) } }
        db {
            EntityChangeTable.insert {
                it[entityType] = EntityType.SONG
                it[entityId] = scopeless
                it[aspect] = EntityChangeAspect.DATA
                it[kind] = EntityChangeKind.UPDATED
                it[changedAt] = 1L
            }
            recorder.created(EntityType.SONG, listOf(scopedAlready))
        }
        val marker = markScopes(EntityType.SONG, scopedAlready)

        db { recorder.updated(EntityType.SONG, listOf(unrecorded, scopeless, scopedAlready)) }

        val expected = setOf(EntityType.ALBUM to album, EntityType.ARTIST to artist)
        assertEquals(expected, scopes(EntityType.SONG, unrecorded))
        assertEquals(expected, scopes(EntityType.SONG, scopeless))
        assertEquals(expected + marker, scopes(EntityType.SONG, scopedAlready))
        assertTrue(changedAt(EntityType.SONG, scopeless, EntityChangeAspect.DATA) > 1L)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a relink recorded with the container flag rewrites the scope rows`(dialect: DbDialect) {
        setup(dialect)
        val from = db { insertAlbum("From") }
        val to = db { insertAlbum("To") }
        val artist = db { insertArtist() }
        val flagged = db { insertSong(from, "Flagged").also { linkSongArtist(it, artist) } }
        db { recorder.created(EntityType.SONG, listOf(flagged)) }
        val flaggedMarker = markScopes(EntityType.SONG, flagged)

        db {
            recorder.leavingContainers(EntityType.SONG, listOf(flagged))
            SongTable.update({ SongTable.id eq flagged }) { it[albumId] = to }
            recorder.updated(EntityType.SONG, listOf(flagged), containersChanged = true)
        }

        assertEquals(setOf(EntityType.ALBUM to to, EntityType.ARTIST to artist), scopes(EntityType.SONG, flagged))
        assertFalse(flaggedMarker in scopes(EntityType.SONG, flagged))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting songs marks their album, artists, playlists and collections`(dialect: DbDialect) {
        setup(dialect)
        val owner = db { insertUser() }
        val album = db { insertAlbum() }
        val otherAlbum = db { insertAlbum("Other") }
        val artist = db { insertArtist() }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        val second = db { insertSong(album) }
        val untouched = db { insertSong(otherAlbum) }
        val userPlaylist = db { insertUserPlaylist(owner, listOf(song, second)) }
        val otherUserPlaylist = db { insertUserPlaylist(owner, listOf(untouched)) }
        val playlist = db { insertPlaylist(listOf(second)) }
        val otherPlaylist = db { insertPlaylist(listOf(untouched)) }
        val collection = db { insertCollection(owner, songs = listOf(song)) }
        val otherCollection = db { insertCollection(owner, songs = listOf(untouched), albums = listOf(album)) }

        db {
            recorder.deleting(EntityType.SONG, listOf(song, second))
            SongTable.deleteWhere { SongTable.id inList listOf(song, second) }
        }

        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.DELETED),
                data(EntityType.SONG, second, EntityChangeKind.DELETED),
                members(EntityType.ALBUM, album),
                members(EntityType.ARTIST, artist),
                members(EntityType.USER_PLAYLIST, userPlaylist),
                members(EntityType.PLAYLIST, playlist),
                members(EntityType.COLLECTION, collection),
            ),
            libraryRows()
        )
        assertFalse(libraryRows().any { it.entity in setOf(otherUserPlaylist, otherPlaylist, otherCollection) })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting albums, artists and playlists marks what contained them`(dialect: DbDialect) {
        setup(dialect)
        val owner = db { insertUser() }
        val album = db { insertAlbum() }
        val creditedAlbum = db { insertAlbum("Credited") }
        val artist = db { insertArtist() }
        val doomedArtist = db { insertArtist("Doomed") }
        val creditedSong = db { insertSong(creditedAlbum).also { linkSongArtist(it, doomedArtist) } }
        db {
            linkAlbumArtist(album, artist)
            linkAlbumArtist(creditedAlbum, doomedArtist)
            linkAlbumArtist(creditedAlbum, artist)
        }
        val userPlaylist = db { insertUserPlaylist(owner) }
        val playlist = db { insertPlaylist() }
        val albumCollection = db { insertCollection(owner, albums = listOf(album)) }
        val artistCollection = db { insertCollection(owner, artists = listOf(doomedArtist)) }
        val playlistCollection = db { insertCollection(owner, playlists = listOf(userPlaylist)) }
        val doomedCollection = db { insertCollection(owner) }

        db {
            recorder.deleting(EntityType.ALBUM, listOf(album))
            AlbumTable.deleteWhere { AlbumTable.id eq album }
        }
        assertEquals(
            setOf(
                data(EntityType.ALBUM, album, EntityChangeKind.DELETED),
                members(EntityType.ARTIST, artist),
                members(EntityType.COLLECTION, albumCollection),
            ),
            libraryRows()
        )
        assertEquals(setOf(EntityType.ARTIST to artist), scopes(EntityType.ALBUM, album))

        db { EntityChangeTable.deleteWhere { EntityChangeTable.entityType inList EntityType.entries } }
        db {
            recorder.deleting(EntityType.ARTIST, listOf(doomedArtist))
            ArtistTable.deleteWhere { ArtistTable.id eq doomedArtist }
        }
        assertEquals(
            setOf(
                data(EntityType.ARTIST, doomedArtist, EntityChangeKind.DELETED),
                members(EntityType.COLLECTION, artistCollection),
                data(EntityType.SONG, creditedSong, EntityChangeKind.UPDATED),
                data(EntityType.ALBUM, creditedAlbum, EntityChangeKind.UPDATED),
            ),
            libraryRows()
        )
        assertEquals(
            setOf(EntityType.ALBUM to creditedAlbum, EntityType.ARTIST to doomedArtist),
            scopes(EntityType.SONG, creditedSong)
        )

        db { EntityChangeTable.deleteWhere { EntityChangeTable.entityType inList EntityType.entries } }
        db {
            recorder.deleting(EntityType.USER_PLAYLIST, listOf(userPlaylist))
            recorder.deleting(EntityType.PLAYLIST, listOf(playlist))
            recorder.deleting(EntityType.COLLECTION, listOf(doomedCollection))
            UserPlaylistTable.deleteWhere { UserPlaylistTable.id eq userPlaylist }
            PlaylistTable.deleteWhere { PlaylistTable.id eq playlist }
            CollectionTable.deleteWhere { CollectionTable.id eq doomedCollection }
        }
        assertEquals(
            setOf(
                data(EntityType.USER_PLAYLIST, userPlaylist, EntityChangeKind.DELETED),
                members(EntityType.COLLECTION, playlistCollection),
                data(EntityType.PLAYLIST, playlist, EntityChangeKind.DELETED),
                data(EntityType.COLLECTION, doomedCollection, EntityChangeKind.DELETED),
            ),
            libraryRows()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `changed members mark the container only`(dialect: DbDialect) {
        setup(dialect)
        val owner = db { insertUser() }
        val album = db { insertAlbum() }
        val song = db { insertSong(album) }
        val userPlaylist = db { insertUserPlaylist(owner, listOf(song)) }
        val playlist = db { insertPlaylist(listOf(song)) }
        val collection = db { insertCollection(owner, songs = listOf(song)) }

        db {
            recorder.membersChanged(EntityType.USER_PLAYLIST, listOf(userPlaylist))
            recorder.membersChanged(EntityType.PLAYLIST, listOf(playlist))
            recorder.membersChanged(EntityType.COLLECTION, listOf(collection, collection))
        }

        assertEquals(
            setOf(
                members(EntityType.USER_PLAYLIST, userPlaylist),
                members(EntityType.PLAYLIST, playlist),
                members(EntityType.COLLECTION, collection),
            ),
            libraryRows()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a song merge deletes the removed songs, updates the kept one and marks the containers of both`(
        dialect: DbDialect
    ) {
        setup(dialect)
        val owner = db { insertUser() }
        val keptAlbum = db { insertAlbum("Kept") }
        val removedAlbum = db { insertAlbum("Removed") }
        val keptArtist = db { insertArtist("Kept") }
        val removedArtist = db { insertArtist("Removed") }
        val kept = db { insertSong(keptAlbum).also { linkSongArtist(it, keptArtist) } }
        val removed = db { insertSong(removedAlbum).also { linkSongArtist(it, removedArtist) } }
        val keptPlaylist = db { insertUserPlaylist(owner, listOf(kept)) }
        val removedPlaylist = db { insertUserPlaylist(owner, listOf(removed)) }
        val removedGlobal = db { insertPlaylist(listOf(removed)) }
        val removedCollection = db { insertCollection(owner, songs = listOf(removed)) }
        db {
            recorder.likesChanged(owner, EntityType.SONG, listOf(kept, removed))
        }

        db {
            recorder.merging(EntityType.SONG, kept, listOf(removed, kept))
            SongTable.deleteWhere { SongTable.id eq removed }
        }

        assertEquals(
            setOf(
                data(EntityType.SONG, removed, EntityChangeKind.DELETED),
                data(EntityType.SONG, kept, EntityChangeKind.UPDATED),
                members(EntityType.ALBUM, keptAlbum),
                members(EntityType.ALBUM, removedAlbum),
                members(EntityType.ARTIST, keptArtist),
                members(EntityType.ARTIST, removedArtist),
                members(EntityType.USER_PLAYLIST, keptPlaylist),
                members(EntityType.USER_PLAYLIST, removedPlaylist),
                members(EntityType.PLAYLIST, removedGlobal),
                members(EntityType.COLLECTION, removedCollection),
            ),
            libraryRows()
        )
        assertEquals(
            setOf(owner to Recorded(EntityType.SONG, kept, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED)),
            userRows()
        )
        assertEquals(
            setOf(EntityType.ALBUM to removedAlbum, EntityType.ARTIST to removedArtist),
            scopes(EntityType.SONG, removed)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an album merge moves the scopes of the removed album's songs to the kept album`(dialect: DbDialect) {
        setup(dialect)
        val owner = db { insertUser() }
        val artist = db { insertArtist() }
        val removedArtist = db { insertArtist("Removed") }
        val kept = db { insertAlbum("Kept") }
        val removed = db { insertAlbum("Removed") }
        db {
            linkAlbumArtist(kept, artist)
            linkAlbumArtist(removed, removedArtist)
        }
        val keptSong = db { insertSong(kept) }
        val movedSong = db { insertSong(removed).also { linkSongArtist(it, removedArtist) } }
        val removedCollection = db { insertCollection(owner, albums = listOf(removed)) }
        val keptCollection = db { insertCollection(owner, albums = listOf(kept)) }

        db {
            recorder.merging(EntityType.ALBUM, kept, listOf(removed))
            SongTable.update({ SongTable.id eq movedSong }) { it[albumId] = kept }
            AlbumTable.deleteWhere { AlbumTable.id eq removed }
        }

        assertEquals(
            setOf(
                data(EntityType.ALBUM, removed, EntityChangeKind.DELETED),
                data(EntityType.ALBUM, kept, EntityChangeKind.UPDATED),
                members(EntityType.ALBUM, kept),
                data(EntityType.SONG, movedSong, EntityChangeKind.UPDATED),
                members(EntityType.ARTIST, artist),
                members(EntityType.ARTIST, removedArtist),
                members(EntityType.COLLECTION, removedCollection),
                members(EntityType.COLLECTION, keptCollection),
            ),
            libraryRows()
        )
        assertFalse(libraryRows().any { it.entity == keptSong })
        assertEquals(
            setOf(EntityType.ALBUM to kept, EntityType.ARTIST to removedArtist),
            scopes(EntityType.SONG, movedSong)
        )
        assertEquals(setOf(EntityType.ARTIST to removedArtist), scopes(EntityType.ALBUM, removed))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an artist merge moves the scopes of the removed artist's songs and albums to the kept artist`(
        dialect: DbDialect
    ) {
        setup(dialect)
        val owner = db { insertUser() }
        val kept = db { insertArtist("Kept") }
        val removed = db { insertArtist("Removed") }
        val album = db { insertAlbum() }
        val song = db { insertSong(album).also { linkSongArtist(it, removed) } }
        db { linkAlbumArtist(album, removed) }
        val removedCollection = db { insertCollection(owner, artists = listOf(removed)) }

        db {
            recorder.merging(EntityType.ARTIST, kept, listOf(removed))
            SongArtistTable.update({ SongArtistTable.artistId eq removed }) { it[artistId] = kept }
            AlbumArtistTable.update({ AlbumArtistTable.artistId eq removed }) { it[artistId] = kept }
            ArtistTable.deleteWhere { ArtistTable.id eq removed }
        }

        assertEquals(
            setOf(
                data(EntityType.ARTIST, removed, EntityChangeKind.DELETED),
                data(EntityType.ARTIST, kept, EntityChangeKind.UPDATED),
                members(EntityType.ARTIST, kept),
                data(EntityType.SONG, song, EntityChangeKind.UPDATED),
                data(EntityType.ALBUM, album, EntityChangeKind.UPDATED),
                members(EntityType.COLLECTION, removedCollection),
            ),
            libraryRows()
        )
        assertEquals(setOf(EntityType.ALBUM to album, EntityType.ARTIST to kept), scopes(EntityType.SONG, song))
        assertEquals(setOf(EntityType.ARTIST to kept), scopes(EntityType.ALBUM, album))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `per user rows belong to the user who caused them`(dialect: DbDialect) {
        setup(dialect)
        val first = db { insertUser() }
        val second = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val song = db { insertSong(album) }

        db {
            recorder.likesChanged(first, EntityType.SONG, listOf(song, song))
            recorder.likesChanged(first, EntityType.ARTIST, listOf(artist))
            recorder.likesChanged(second, EntityType.ALBUM, listOf(album))
            recorder.timecodesChanged(second, listOf(song))
            recorder.timecodesChanged(second, emptyList())
        }

        assertEquals(
            setOf(
                first to Recorded(EntityType.SONG, song, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
                first to Recorded(EntityType.ARTIST, artist, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
                second to Recorded(EntityType.ALBUM, album, EntityChangeAspect.LIKE, EntityChangeKind.UPDATED),
                second to Recorded(EntityType.SONG, song, EntityChangeAspect.TIMECODES, EntityChangeKind.UPDATED),
            ),
            userRows()
        )
        assertEquals(emptySet<Recorded>(), libraryRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a rollback of the surrounding transaction leaves no rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }

        assertThrows<IllegalStateException> {
            runBlocking {
                dbQuery {
                    recorder.created(EntityType.SONG, listOf(song))
                    recorder.likesChanged(user, EntityType.SONG, listOf(song))
                    dbQuery { recorder.membersChanged(EntityType.ALBUM, listOf(album)) }
                    error("the write failed")
                }
            }
        }

        assertEquals(emptySet<Recorded>(), libraryRows())
        assertEquals(emptySet<Pair<UUID, Recorded>>(), userRows())
        assertEquals(0L, db { EntityChangeScopeTable.selectAll().count() })

        dbQuery {
            recorder.created(EntityType.SONG, listOf(song))
            dbQuery { recorder.likesChanged(user, EntityType.SONG, listOf(song)) }
        }

        assertEquals(
            setOf(
                data(EntityType.SONG, song, EntityChangeKind.CREATED),
                members(EntityType.ALBUM, album),
                members(EntityType.ARTIST, artist),
            ),
            libraryRows()
        )
        assertEquals(1, userRows().size)
    }

    private fun stamps(): List<Long> = db {
        EntityChangeTable.selectAll().map { it[EntityChangeTable.changedAt] } +
            UserEntityChangeTable.selectAll().map { it[UserEntityChangeTable.changedAt] }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `changes carry the time of the commit, not of the recorder call`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        var recordedAt = 0L

        val held = db {
            recorder.created(EntityType.SONG, listOf(song))
            recorder.likesChanged(user, EntityType.SONG, listOf(song))
            recordedAt = EntityChangeTable.selectAll().maxOf { it[EntityChangeTable.changedAt] }
            Thread.sleep(20)
            System.currentTimeMillis()
        }

        assertTrue(recordedAt < held)
        assertEquals(4, stamps().size)
        assertEquals(1, stamps().toSet().size)
        assertTrue(stamps().first() >= held)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `nested blocks are stamped once with the commit of the outermost transaction`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val user = db { insertUser() }
            val first = db { insertArtist("First") }
            val second = db { insertArtist("Second") }
            val third = db { insertArtist("Third") }

            val held = dbQuery {
                recorder.updated(EntityType.ARTIST, listOf(first))
                dbQuery {
                    recorder.updated(EntityType.ARTIST, listOf(second))
                    recorder.likesChanged(user, EntityType.ARTIST, listOf(second))
                }
                Thread.sleep(20)
                transaction { recorder.updated(EntityType.ARTIST, listOf(third)) }
                Thread.sleep(20)
                System.currentTimeMillis()
            }

            assertEquals(4, stamps().size)
            assertEquals(1, stamps().toSet().size)
            assertTrue(stamps().first() >= held)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rows that a later deletion or a restart removes in the same transaction do not break the commit`(
        dialect: DbDialect
    ) {
        setup(dialect)
        val user = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }

        val held = db {
            recorder.updated(EntityType.ALBUM, listOf(album))
            recorder.membersChanged(EntityType.ALBUM, listOf(album))
            recorder.likesChanged(user, EntityType.ALBUM, listOf(album))
            recorder.deleting(EntityType.ALBUM, listOf(album))
            Thread.sleep(20)
            System.currentTimeMillis()
        }

        assertEquals(setOf(data(EntityType.ALBUM, album, EntityChangeKind.DELETED)), libraryRows())
        assertEquals(emptySet<Pair<UUID, Recorded>>(), userRows())
        assertEquals(1, stamps().toSet().size)
        assertTrue(stamps().first() >= held)

        db {
            recorder.updated(EntityType.ARTIST, listOf(artist))
            recorder.likesChanged(user, EntityType.ARTIST, listOf(artist))
            recorder.restartTracking()
        }

        assertEquals(emptyList<Long>(), stamps())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a transaction that commits, records again and rolls back keeps only the committed stamp`(dialect: DbDialect) {
        setup(dialect)
        val first = db { insertArtist("First") }
        val second = db { insertArtist("Second") }

        db {
            recorder.updated(EntityType.ARTIST, listOf(first))
            TransactionManager.current().commit()
            recorder.updated(EntityType.ARTIST, listOf(second))
            TransactionManager.current().rollback()
        }

        assertEquals(setOf(data(EntityType.ARTIST, first, EntityChangeKind.UPDATED)), libraryRows())
        assertEquals(1, stamps().size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class, names = ["POSTGRES"])
    fun `transactions marking the same rows in opposite orders do not deadlock`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val artists = List(3000) { UUID.randomUUID() }
        val failures = ConcurrentLinkedQueue<Throwable>()

        repeat(4) {
            val start = CyclicBarrier(2)
            listOf(artists, artists.reversed()).map { order ->
                thread {
                    runCatching {
                        start.await(30, TimeUnit.SECONDS)
                        transaction(database) {
                            maxAttempts = 1
                            recorder.membersChanged(EntityType.ARTIST, order)
                            recorder.likesChanged(user, EntityType.ARTIST, order)
                        }
                    }.onFailure { failures += it }
                }
            }.forEach { it.join() }
        }

        assertEquals(emptyList<Throwable>(), failures.toList())
        assertEquals(3000L, db { EntityChangeTable.selectAll().count() })
        assertEquals(3000L, db { UserEntityChangeTable.selectAll().count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recording outside a transaction fails instead of writing on its own`(dialect: DbDialect) {
        setup(dialect)
        val artist = db { insertArtist() }

        assertThrows<IllegalStateException> { recorder.updated(EntityType.ARTIST, listOf(artist)) }

        assertEquals(emptySet<Recorded>(), libraryRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class, names = ["POSTGRES"])
    fun `rows written on PostgreSQL are not read back by their key`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val songs = List(5500) { UUID.randomUUID() }
        db {
            SongTable.batchInsert(songs, shouldReturnGeneratedValues = false) { song ->
                this[SongTable.id] = song
                this[SongTable.title] = "Song"
                this[SongTable.albumId] = album
                this[SongTable.fileSize] = 0
                this[SongTable.duration] = 0
            }
            SongArtistTable.batchInsert(songs, shouldReturnGeneratedValues = false) { song ->
                this[SongArtistTable.songId] = song
                this[SongArtistTable.artistId] = artist
            }
        }
        val reads = ChangeRowReads()

        db {
            TransactionManager.current().registerInterceptor(reads)
            recorder.created(EntityType.SONG, songs)
            recorder.updated(EntityType.SONG, songs)
            recorder.likesChanged(user, EntityType.SONG, songs)
            recorder.likesChanged(user, EntityType.SONG, songs)
        }

        assertEquals(0, reads.count)
        assertEquals(
            songs.mapTo(mutableSetOf()) { data(EntityType.SONG, it, EntityChangeKind.CREATED) } +
                members(EntityType.ALBUM, album) + members(EntityType.ARTIST, artist),
            libraryRows()
        )
        assertEquals(11_000L, db { EntityChangeScopeTable.selectAll().count() })
        assertEquals(5500, userRows().size)
        val stamps = db {
            EntityChangeTable.selectAll().map { it[EntityChangeTable.changedAt] } +
                UserEntityChangeTable.selectAll().map { it[UserEntityChangeTable.changedAt] }
        }
        assertEquals(11_002, stamps.size)
        assertEquals(1, stamps.toSet().size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `large batches are recorded completely`(dialect: DbDialect) {
        setup(dialect)
        val user = db { insertUser() }
        val album = db { insertAlbum() }
        val artist = db { insertArtist() }
        val songs = List(5500) { UUID.randomUUID() }
        db {
            SongTable.batchInsert(songs, shouldReturnGeneratedValues = false) { song ->
                this[SongTable.id] = song
                this[SongTable.title] = "Song"
                this[SongTable.albumId] = album
                this[SongTable.fileSize] = 0
                this[SongTable.duration] = 0
            }
            SongArtistTable.batchInsert(songs, shouldReturnGeneratedValues = false) { song ->
                this[SongArtistTable.songId] = song
                this[SongArtistTable.artistId] = artist
            }
        }

        val held = db {
            recorder.created(EntityType.SONG, songs)
            recorder.likesChanged(user, EntityType.SONG, songs)
            Thread.sleep(20)
            System.currentTimeMillis()
        }

        val stamps = db {
            EntityChangeTable.selectAll().map { it[EntityChangeTable.changedAt] } +
                UserEntityChangeTable.selectAll().map { it[UserEntityChangeTable.changedAt] }
        }
        assertEquals(11_002, stamps.size)
        assertEquals(1, stamps.toSet().size)
        assertTrue(stamps.first() >= held)
        assertEquals(
            songs.mapTo(mutableSetOf()) { data(EntityType.SONG, it, EntityChangeKind.CREATED) } +
                members(EntityType.ALBUM, album) + members(EntityType.ARTIST, artist),
            libraryRows()
        )
        assertEquals(11_000L, db { EntityChangeScopeTable.selectAll().count() })
        assertEquals(5500, userRows().size)

        db {
            recorder.deleting(EntityType.SONG, songs)
            SongTable.deleteWhere { SongTable.albumId eq album }
        }

        assertEquals(
            songs.mapTo(mutableSetOf()) { data(EntityType.SONG, it, EntityChangeKind.DELETED) } +
                members(EntityType.ALBUM, album) + members(EntityType.ARTIST, artist),
            libraryRows()
        )
        assertEquals(11_000L, db { EntityChangeScopeTable.selectAll().count() })
        assertEquals(emptySet<Pair<UUID, Recorded>>(), userRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a song that a merge already recorded keeps the scopes of the merge`(dialect: DbDialect) {
        setup(dialect)
        val album = db { insertAlbum("Album") }
        val otherAlbum = db { insertAlbum("Other") }
        val keptArtist = db { insertArtist("Kept") }
        val removedArtist = db { insertArtist("Removed") }
        val kept = db { insertSong(album).also { linkSongArtist(it, keptArtist) } }
        val removed = db { insertSong(album).also { linkSongArtist(it, removedArtist) } }
        val unmerged = db { insertSong(otherAlbum).also { linkSongArtist(it, removedArtist) } }

        db {
            recorder.merging(EntityType.SONG, kept, listOf(removed))
            SongArtistTable.deleteWhere { SongArtistTable.songId eq removed }
            recorder.deleting(EntityType.SONG, listOf(removed, unmerged))
            SongTable.deleteWhere { SongTable.id inList listOf(removed, unmerged) }
        }

        assertEquals(
            setOf(EntityType.ALBUM to album, EntityType.ARTIST to removedArtist),
            scopes(EntityType.SONG, removed)
        )
        assertEquals(
            setOf(EntityType.ALBUM to otherAlbum, EntityType.ARTIST to removedArtist),
            scopes(EntityType.SONG, unmerged)
        )
        assertTrue(data(EntityType.SONG, removed, EntityChangeKind.DELETED) in libraryRows())
        assertTrue(data(EntityType.SONG, unmerged, EntityChangeKind.DELETED) in libraryRows())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `restarting the tracking clears every change and moves the start to now`(dialect: DbDialect) {
        setup(dialect)
        val owner = db { insertUser() }
        val album = db { insertAlbum("Album") }
        val song = db { insertSong(album) }
        db {
            EntityChangeTrackingTable.insert {
                it[id] = ROW_ID
                it[startedAt] = 1_000
            }
            recorder.created(EntityType.SONG, listOf(song))
            recorder.likesChanged(owner, EntityType.SONG, listOf(song))
        }
        val before = System.currentTimeMillis()

        db { recorder.restartTracking() }

        assertEquals(emptySet<Recorded>(), libraryRows())
        assertEquals(emptySet<Pair<UUID, Recorded>>(), userRows())
        assertEquals(0, db { EntityChangeScopeTable.selectAll().count() })
        val started = db { EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt] }
        assertTrue(started >= before)

        db { recorder.restartTracking() }
        assertEquals(1, db { EntityChangeTrackingTable.selectAll().count() })
    }
}
