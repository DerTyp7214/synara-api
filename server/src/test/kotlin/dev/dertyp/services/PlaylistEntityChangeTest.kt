package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.EntityType
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.Playlist
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserPlaylistSong
import dev.dertyp.db.PlaylistTable
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedUserChanges
import dev.dertyp.testing.updated
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class PlaylistEntityChangeTest : EntityChangeContentTest() {
    private fun nothing() = assertEquals(emptySet<Any>(), recordedChanges(database))

    private fun only(vararg expected: Any) {
        assertEquals(expected.toSet(), recordedChanges(database))
        assertEquals(emptySet<Any>(), recordedUserChanges(database))
        clearRecordedChanges(database)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `creating, renaming, setting the image of and deleting a user playlist`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val cover = picture()

        val playlist = userPlaylistService.getOrAddPlaylist(owner, "mix", InsertablePlaylist("Mix"))
        only(created(EntityType.USER_PLAYLIST, playlist))

        assertEquals(playlist, userPlaylistService.getOrAddPlaylist(owner, "mix", InsertablePlaylist("Mix")))
        nothing()

        assertTrue(subsonicQueryService.updatePlaylistMeta(playlist, "Renamed", null))
        only(updated(EntityType.USER_PLAYLIST, playlist))
        assertTrue(subsonicQueryService.updatePlaylistMeta(playlist, "Renamed", null))
        assertTrue(subsonicQueryService.updatePlaylistMeta(playlist, null, null))
        nothing()
        assertTrue(subsonicQueryService.updatePlaylistMeta(playlist, null, "Comment"))
        only(updated(EntityType.USER_PLAYLIST, playlist))

        assertTrue(userPlaylistService.setPlaylistImage(playlist, cover))
        only(updated(EntityType.USER_PLAYLIST, playlist))
        assertTrue(userPlaylistService.setPlaylistImage(playlist, cover))
        nothing()
        assertTrue(userPlaylistService.setPlaylistImage(playlist, null))
        only(updated(EntityType.USER_PLAYLIST, playlist))
        assertFalse(userPlaylistService.setPlaylistImage(UUID.randomUUID(), cover))
        nothing()

        assertTrue(userPlaylistService.delete(playlist))
        only(deleted(EntityType.USER_PLAYLIST, playlist))
        assertFalse(userPlaylistService.delete(playlist))
        nothing()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `making a user playlist public and sharing it is recorded`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val playlist = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Mix"))
        clearRecordedChanges(database)

        assertTrue(userPlaylistService.setPublic(playlist, true))
        only(updated(EntityType.USER_PLAYLIST, playlist))
        assertTrue(userPlaylistService.setPublic(playlist, true))
        nothing()
        assertTrue(userPlaylistService.setPublic(playlist, false))
        only(updated(EntityType.USER_PLAYLIST, playlist))
        assertFalse(userPlaylistService.setPublic(UUID.randomUUID(), true))
        nothing()

        userPlaylistService.setShare(playlist, stranger, PlaylistAccess.READ)
        only(members(EntityType.USER_PLAYLIST, playlist))
        userPlaylistService.setShare(playlist, stranger, PlaylistAccess.READ)
        nothing()
        userPlaylistService.setShare(playlist, stranger, PlaylistAccess.WRITE)
        only(members(EntityType.USER_PLAYLIST, playlist))

        assertTrue(userPlaylistService.removeShare(playlist, stranger))
        only(members(EntityType.USER_PLAYLIST, playlist))
        assertFalse(userPlaylistService.removeShare(playlist, stranger))
        nothing()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `transferring a user playlist records the new owner and the share of the previous one`(dialect: DbDialect) =
        runBlocking {
            setupContent(dialect)
            val playlist = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Mix"))
            clearRecordedChanges(database)

            assertTrue(userPlaylistService.transferOwnership(playlist, stranger))
            only(updated(EntityType.USER_PLAYLIST, playlist), members(EntityType.USER_PLAYLIST, playlist))
            assertFalse(userPlaylistService.transferOwnership(playlist, stranger))
            nothing()
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `adding, reordering and removing songs of a user playlist marks its members`(dialect: DbDialect) =
        runBlocking {
            setupContent(dialect)
            val album = album("Album")
            val first = song(album, "First")
            val second = song(album, "Second", track = 2)
            val playlist = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Mix"))
            clearRecordedChanges(database)

            userPlaylistService.addToPlaylist(playlist, listOf(1000L to first, 2000L to second))
            only(members(EntityType.USER_PLAYLIST, playlist))

            userPlaylistService.addToPlaylist(playlist, listOf(1000L to first))
            userPlaylistService.addToPlaylist(playlist, emptyList())
            nothing()

            val stored = userPlaylistService.byId(playlist)!!
            assertEquals(listOf(first, second), stored.songs)
            userPlaylistService.upsertUserPlaylist(stored)
            nothing()

            userPlaylistService.upsertUserPlaylist(
                stored.copy(songEntries = listOf(UserPlaylistSong(second, 1000L), UserPlaylistSong(first, 2000L)))
            )
            assertEquals(listOf(second, first), userPlaylistService.byId(playlist)!!.songs)
            only(members(EntityType.USER_PLAYLIST, playlist))

            assertEquals(1, userPlaylistService.removeFromPlaylist(playlist, listOf(first)))
            only(members(EntityType.USER_PLAYLIST, playlist))
            assertEquals(0, userPlaylistService.removeFromPlaylist(playlist, listOf(first)))
            nothing()

            userPlaylistService.addSongsToPlaylist(playlist, listOf(first))
            only(members(EntityType.USER_PLAYLIST, playlist))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mirroring a user playlist records a creation, then only real changes`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        val first = song(album, "First")
        val second = song(album, "Second", track = 2)
        val remote = UserPlaylist(
            id = UUID.randomUUID(),
            name = "Mirrored",
            songs = listOf(first),
            songEntries = listOf(UserPlaylistSong(first, 1000L)),
            creator = owner,
            description = "",
        )

        userPlaylistService.upsertUserPlaylist(remote)
        only(created(EntityType.USER_PLAYLIST, remote.id), members(EntityType.USER_PLAYLIST, remote.id))

        userPlaylistService.upsertUserPlaylist(remote)
        nothing()

        userPlaylistService.upsertUserPlaylist(remote.copy(description = "Described"))
        only(updated(EntityType.USER_PLAYLIST, remote.id))

        userPlaylistService.upsertUserPlaylist(
            remote.copy(
                description = "Described",
                songEntries = listOf(UserPlaylistSong(first, 1000L), UserPlaylistSong(second, 2000L))
            )
        )
        only(members(EntityType.USER_PLAYLIST, remote.id))

        userPlaylistService.upsertUserPlaylist(remote.copy(description = "Described"), creatorOverride = stranger)
        only(updated(EntityType.USER_PLAYLIST, remote.id), members(EntityType.USER_PLAYLIST, remote.id))

        val empty = remote.copy(id = UUID.randomUUID(), songs = emptyList(), songEntries = null)
        userPlaylistService.upsertUserPlaylist(empty)
        only(created(EntityType.USER_PLAYLIST, empty.id))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `creating, renaming, setting the image of and deleting a global playlist`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val cover = picture()

        val playlist = playlistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Global"))
        only(created(EntityType.PLAYLIST, playlist))
        assertEquals(playlist, playlistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Global")))
        nothing()

        val stored = playlistService.byId(playlist)!!
        playlistService.upsertPlaylist(stored)
        nothing()
        playlistService.upsertPlaylist(stored.copy(name = "Renamed"))
        only(updated(EntityType.PLAYLIST, playlist))
        playlistService.upsertPlaylist(stored.copy(name = "Renamed", imageId = cover))
        only(updated(EntityType.PLAYLIST, playlist))
        playlistService.upsertPlaylist(stored.copy(name = "Renamed", imageId = cover))
        nothing()

        assertTrue(playlistService.delete(playlist))
        only(deleted(EntityType.PLAYLIST, playlist))
        assertFalse(playlistService.delete(playlist))
        nothing()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `adding, reordering and removing songs of a global playlist marks its members`(dialect: DbDialect) =
        runBlocking {
            setupContent(dialect)
            val album = album("Album")
            val first = song(album, "First")
            val second = song(album, "Second", track = 2)
            val playlist = playlistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Global"))
            clearRecordedChanges(database)

            playlistService.addToPlaylist(playlist, listOf(0L to first, 0L to second))
            only(members(EntityType.PLAYLIST, playlist))
            playlistService.addToPlaylist(playlist, emptyList())
            nothing()

            val stored = playlistService.byId(playlist)!!
            assertEquals(listOf(first, second), stored.songs)
            playlistService.upsertPlaylist(stored)
            nothing()

            playlistService.upsertPlaylist(stored.copy(songs = listOf(second, first)))
            only(members(EntityType.PLAYLIST, playlist))

            playlistService.upsertPlaylist(stored.copy(songs = listOf(second)))
            only(members(EntityType.PLAYLIST, playlist))

            val remote = Playlist(id = UUID.randomUUID(), name = "Mirrored", songs = listOf(first, second))
            playlistService.upsertPlaylist(remote)
            only(created(EntityType.PLAYLIST, remote.id), members(EntityType.PLAYLIST, remote.id))
            playlistService.upsertPlaylist(remote)
            nothing()
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `indexing global playlists again keeps their ids and records only what changed`(dialect: DbDialect) =
        runBlocking {
            setupContent(dialect)
            val album = album("Album")
            val first = song(album, "First", location = "/music/first.flac")
            val second = song(album, "Second", track = 2, location = "/music/second.flac")
            val cover = picture()
            coEvery { imageService.getCoverHashes(listOf("cover")) } returns mapOf("cover" to cover)
            val filledPlaylist = InsertablePlaylist("Filled", songPaths = listOf("/music/first.flac", "/music/second.flac"))
            val indexed = listOf(filledPlaylist, InsertablePlaylist("Empty"))

            val (filled, empty) = playlistService.createBatch(indexed)
            only(
                created(EntityType.PLAYLIST, filled),
                members(EntityType.PLAYLIST, filled),
                created(EntityType.PLAYLIST, empty),
            )

            assertEquals(listOf(filled, empty), playlistService.createBatch(indexed))
            nothing()
            assertEquals(listOf(first, second), playlistService.byId(filled)!!.songs)

            val reordered = filledPlaylist.copy(songPaths = listOf("/music/second.flac", "/music/first.flac"))
            assertEquals(listOf(filled, empty), playlistService.createBatch(listOf(reordered, InsertablePlaylist("Empty"))))
            only(members(EntityType.PLAYLIST, filled))
            assertEquals(listOf(second, first), playlistService.byId(filled)!!.songs)

            assertEquals(
                listOf(filled),
                playlistService.createBatch(listOf(filledPlaylist.copy(songPaths = listOf("/music/second.flac"))))
            )
            only(members(EntityType.PLAYLIST, filled))
            assertEquals(listOf(second), playlistService.byId(filled)!!.songs)

            assertEquals(listOf(empty), playlistService.createBatch(listOf(InsertablePlaylist("Empty", imageHash = "cover"))))
            only(updated(EntityType.PLAYLIST, empty))
            assertEquals(cover, playlistService.byId(empty)!!.imageId)

            val (other) = playlistService.createBatch(listOf(InsertablePlaylist("Other", songPaths = listOf("/music/first.flac"))))
            assertFalse(other in setOf(filled, empty))
            only(created(EntityType.PLAYLIST, other), members(EntityType.PLAYLIST, other))
            assertEquals(
                setOf(filled, empty, other),
                db { PlaylistTable.selectAll().map { it[PlaylistTable.id].value }.toSet() }
            )
            assertEquals(listOf(second), playlistService.byId(filled)!!.songs)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `indexing a global playlist whose name several rows share updates the one with the smallest id and deletes the others`(
        dialect: DbDialect
    ) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        val first = song(album, "First", location = "/music/first.flac")
        val duplicates = List(3) { UUID.randomUUID() }
        val bystander = UUID.randomUUID()
        db {
            for (duplicate in duplicates) {
                PlaylistTable.insert {
                    it[id] = duplicate
                    it[name] = "Shared"
                }
            }
            PlaylistTable.insert {
                it[id] = bystander
                it[name] = "Bystander"
            }
        }
        val kept = duplicates.minBy { it.toString() }
        val removed = duplicates - kept
        val shared = listOf(InsertablePlaylist("Shared", songPaths = listOf("/music/first.flac")))

        assertEquals(listOf(kept), playlistService.createBatch(shared))
        only(members(EntityType.PLAYLIST, kept), *removed.map { deleted(EntityType.PLAYLIST, it) }.toTypedArray())
        assertEquals(setOf(kept, bystander), db { PlaylistTable.selectAll().map { it[PlaylistTable.id].value }.toSet() })
        assertEquals(listOf(first), playlistService.byId(kept)!!.songs)
        for (gone in removed) {
            assertEquals(null, playlistService.byId(gone))
        }

        assertEquals(listOf(kept), playlistService.createBatch(shared))
        nothing()

        val twice = playlistService.createBatch(
            listOf(InsertablePlaylist("Twice", songPaths = listOf("/music/first.flac")), InsertablePlaylist("Twice"))
        )
        assertEquals(2, twice.size)
        assertEquals(1, twice.toSet().size)
        only(created(EntityType.PLAYLIST, twice.first()))
        assertEquals(emptyList<UUID>(), playlistService.byId(twice.first())!!.songs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an indexing run that fails after it removed a playlist leaves no record`(dialect: DbDialect) {
        setupContent(dialect)
        val (existing) = runBlocking { playlistService.createBatch(listOf(InsertablePlaylist("Existing"))) }
        clearRecordedChanges(database)

        assertThrows<Exception> {
            runBlocking {
                playlistService.createBatch(listOf(InsertablePlaylist("Existing"), InsertablePlaylist("x".repeat(300))))
            }
        }

        nothing()
        assertEquals(listOf(existing), db { PlaylistTable.selectAll().map { it[PlaylistTable.id].value } })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a song marks the playlists and collections that contained it`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        val removed = song(album, "Removed")
        val staying = song(album, "Staying", track = 2)
        val userPlaylist = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Mix"))
        val untouched = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Untouched"))
        val global = playlistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Global"))
        val collection = collectionService.createCollection(owner, InsertableCollection("Shelf"))
        userPlaylistService.addSongsToPlaylist(userPlaylist, listOf(removed, staying))
        userPlaylistService.addSongsToPlaylist(untouched, listOf(staying))
        playlistService.addToPlaylist(global, listOf(0L to removed))
        assertTrue(collectionService.addItem(collection, CollectionItemType.SONG, removed))
        clearRecordedChanges(database)

        assertTrue(songService.deleteSongs(listOf(removed)))

        assertTrue(deleted(EntityType.SONG, removed) in recordedChanges(database))
        assertEquals(
            setOf(
                members(EntityType.USER_PLAYLIST, userPlaylist),
                members(EntityType.PLAYLIST, global),
                members(EntityType.COLLECTION, collection),
            ),
            containerChanges()
        )
    }
}
