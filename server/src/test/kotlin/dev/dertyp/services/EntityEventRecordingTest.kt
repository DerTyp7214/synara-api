package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.EntityType
import dev.dertyp.data.TimecodeTagAction
import dev.dertyp.data.TimecodeTagType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.TimecodeTagTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.plugins.HookEvent
import dev.dertyp.plugins.on
import dev.dertyp.testing.RecordedChange
import dev.dertyp.testing.RecordedEntityEvents
import dev.dertyp.testing.RecordedUserChange
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import dev.dertyp.testing.insertAlbum
import dev.dertyp.testing.insertArtist
import dev.dertyp.testing.insertSong
import dev.dertyp.testing.insertUser
import dev.dertyp.testing.linkSongArtist
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedScopes
import dev.dertyp.testing.recordedUserChanges
import dev.dertyp.testing.timecodes
import dev.dertyp.testing.updated
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import java.sql.SQLException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EntityEventRecordingTest : KoinTest {
    private class Library(
        val listener: UUID,
        val artist: UUID,
        val otherArtist: UUID,
        val album: UUID,
        val otherAlbum: UUID,
        val songs: List<UUID>,
        val playlist: UUID,
    )

    private data class Snapshot(
        val library: Set<RecordedChange>,
        val user: Set<RecordedUserChange>,
        val scopes: Map<RecordedChange, Set<Pair<EntityType, UUID>>>,
    )

    private class Published(private val publisher: EntityEventPublisher) : EntityWriteSubscriber {
        override fun created(type: EntityType, ids: Collection<UUID>) = publisher.created(type, ids)

        override fun updated(type: EntityType, ids: Collection<UUID>, containersChanged: Boolean) =
            publisher.updated(type, ids, containersChanged)

        override fun relinked(type: EntityType, ids: Collection<UUID>) = publisher.relinked(type, ids)

        override fun leavingContainers(type: EntityType, ids: Collection<UUID>) = publisher.leavingContainers(type, ids)

        override fun membersChanged(type: EntityType, ids: Collection<UUID>) = publisher.membersChanged(type, ids)

        override fun deleting(type: EntityType, ids: Collection<UUID>) = publisher.deleting(type, ids)

        override fun merging(type: EntityType, keptId: UUID, removedIds: Collection<UUID>) =
            publisher.merging(type, keptId, removedIds)

        override fun likesChanged(userId: UUID, type: EntityType, ids: Collection<UUID>) =
            publisher.likesChanged(userId, type, ids)

        override fun timecodesChanged(userId: UUID, songIds: Collection<UUID>) =
            publisher.timecodesChanged(userId, songIds)
    }

    private lateinit var database: Database
    private val events = RecordedEntityEvents()

    private fun setup(dialect: DbDialect) {
        startKoin { modules(entityEventsModule(events)) }
        database = TestDatabase.connect(dialect, "entity_event_recording_test")
        transaction(database) { SchemaUtils.create(TimecodeTagTable, *entityChangeTables) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun library(): Library = transaction(database) {
        val listener = insertUser()
        val artist = insertArtist("Artist")
        val otherArtist = insertArtist("Other Artist")
        val album = insertAlbum("Album")
        val otherAlbum = insertAlbum("Other Album")
        for (linked in listOf(album, otherAlbum)) {
            AlbumArtistTable.insert {
                it[albumId] = linked
                it[artistId] = artist
            }
        }
        val songs = listOf(insertSong(album, "First"), insertSong(album, "Second"), insertSong(otherAlbum, "Third"))
        linkSongArtist(songs[0], artist)
        linkSongArtist(songs[1], otherArtist)
        linkSongArtist(songs[2], artist)
        val playlist = UUID.randomUUID()
        UserPlaylistTable.insert {
            it[id] = playlist
            it[name] = "Playlist"
            it[description] = ""
            it[creator] = listener
        }
        UserPlaylistSongTable.insert {
            it[playlistId] = playlist
            it[songId] = songs[0]
        }
        Library(listener, artist, otherArtist, album, otherAlbum, songs, playlist)
    }

    private fun announce(subscriber: EntityWriteSubscriber, library: Library) = with(library) {
        val steps = listOf<() -> Unit>(
            { subscriber.created(EntityType.ARTIST, listOf(artist, otherArtist)) },
            { subscriber.created(EntityType.ALBUM, listOf(album, otherAlbum)) },
            { subscriber.created(EntityType.SONG, songs) },
            { subscriber.updated(EntityType.SONG, listOf(songs[0])) },
            { subscriber.updated(EntityType.SONG, listOf(songs[1]), containersChanged = true) },
            { subscriber.relinked(EntityType.ALBUM, listOf(album)) },
            { subscriber.leavingContainers(EntityType.SONG, listOf(songs[2])) },
            { subscriber.membersChanged(EntityType.USER_PLAYLIST, listOf(playlist)) },
            { subscriber.likesChanged(listener, EntityType.SONG, songs) },
            { subscriber.timecodesChanged(listener, listOf(songs[0], songs[1])) },
            { subscriber.merging(EntityType.ALBUM, album, listOf(otherAlbum, album)) },
            { subscriber.deleting(EntityType.SONG, listOf(songs[0])) },
            { subscriber.deleting(EntityType.ARTIST, listOf(otherArtist)) },
            {
                subscriber.created(EntityType.SONG, listOf(songs[1]))
                subscriber.updated(EntityType.SONG, listOf(songs[1]))
                subscriber.deleting(EntityType.SONG, listOf(songs[1]))
            },
        )
        for (step in steps) transaction(database) { step() }
    }

    private fun snapshot(): Snapshot {
        val library = recordedChanges(database)
        return Snapshot(
            library,
            recordedUserChanges(database),
            library.associateWith { recordedScopes(database, it.type, it.entity, it.part) }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `announcing through the publisher records the same rows as calling the recorder`(dialect: DbDialect) {
        setup(dialect)
        val library = library()

        announce(EntityChangeRecorder(), library)
        val direct = snapshot()
        clearRecordedChanges(database)
        announce(Published(events.publisher), library)
        val published = snapshot()

        assertTrue(direct.library.size > 10)
        assertTrue(direct.user.isNotEmpty())
        assertTrue(direct.scopes.values.any { it.isNotEmpty() })
        assertEquals(direct, published)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `changes found by comparing states are recorded through the publisher`(dialect: DbDialect) {
        setup(dialect)
        val library = library()
        val song = library.songs[0]

        transaction(database) {
            val before = entityStates(EntityType.SONG, library.songs)
            SongTable.update({ SongTable.id eq song }) { it[title] = "Renamed" }
            SongTable.update({ SongTable.id eq library.songs[2] }) { it[albumId] = library.album }
            events.publisher.recordChanges(before)
        }

        assertEquals(
            setOf(
                updated(EntityType.SONG, song),
                updated(EntityType.SONG, library.songs[2]),
                members(EntityType.ALBUM, library.album),
                members(EntityType.ALBUM, library.otherAlbum),
            ),
            recordedChanges(database)
        )
        assertEquals(
            setOf(EntityType.ALBUM to library.album, EntityType.ARTIST to library.artist),
            recordedScopes(database, EntityType.SONG, library.songs[2])
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a service write is recorded in its transaction`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val library = library()

        TimecodeTagService().createTag(
            library.listener, library.songs[0], TimecodeTagType.MARKER, "", 1000L, null, TimecodeTagAction.NONE
        )

        assertEquals(setOf(timecodes(library.listener, library.songs[0])), recordedUserChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failing recorder aborts the write of the service`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val library = library()
        val announced = Channel<HookEvent.TimecodesChanged>(Channel.UNLIMITED)
        events.hooks.on<HookEvent.TimecodesChanged> { announced.send(it) }
        transaction(database) { SchemaUtils.drop(UserEntityChangeTable) }

        assertThrows<SQLException> {
            runBlocking {
                TimecodeTagService().createTag(
                    library.listener, library.songs[0], TimecodeTagType.MARKER, "", 1000L, null, TimecodeTagAction.NONE
                )
            }
        }

        assertEquals(0L, transaction(database) { TimecodeTagTable.selectAll().count() })
        assertNull(withTimeoutOrNull(200.milliseconds) { announced.receive() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a bulk announcement of 30000 ids is recorded once and delivered as one event`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songs = List(30000) { UUID.randomUUID() }
        val created = Channel<HookEvent.EntitiesCreated>(Channel.UNLIMITED)
        events.hooks.on<HookEvent.EntitiesCreated> { created.send(it) }
        val recorder = EntityChangeRecorder()

        transaction(database) { songs.chunked(10000).forEach { recorder.created(EntityType.SONG, it) } }
        assertEquals(30000L, transaction(database) { EntityChangeTable.selectAll().count() })
        clearRecordedChanges(database)
        transaction(database) { songs.chunked(10000).forEach { events.publisher.created(EntityType.SONG, it) } }

        assertEquals(30000L, transaction(database) { EntityChangeTable.selectAll().count() })
        assertEquals(HookEvent.EntitiesCreated(EntityType.SONG, songs.toSet()), withTimeout(5.seconds) { created.receive() })
        assertNull(withTimeoutOrNull(200.milliseconds) { created.receive() })
    }
}
