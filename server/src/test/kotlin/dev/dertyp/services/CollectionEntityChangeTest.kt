package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.EntityType
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedUserChanges
import dev.dertyp.testing.updated
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class CollectionEntityChangeTest : EntityChangeContentTest() {
    private fun nothing() = assertEquals(emptySet<Any>(), recordedChanges(database))

    private fun only(vararg expected: Any) {
        assertEquals(expected.toSet(), recordedChanges(database))
        assertEquals(emptySet<Any>(), recordedUserChanges(database))
        clearRecordedChanges(database)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `creating, renaming, setting the image of and deleting a collection`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val cover = picture()

        val collection = collectionService.createCollection(owner, InsertableCollection("Shelf"))
        only(created(EntityType.COLLECTION, collection))

        assertTrue(collectionService.updateCollection(collection, InsertableCollection("Renamed", "Described")))
        only(updated(EntityType.COLLECTION, collection))
        assertTrue(collectionService.updateCollection(collection, InsertableCollection("Renamed", "Described")))
        nothing()
        assertFalse(collectionService.updateCollection(UUID.randomUUID(), InsertableCollection("Missing")))
        nothing()

        assertTrue(collectionService.setCollectionImage(collection, cover))
        only(updated(EntityType.COLLECTION, collection))
        assertTrue(collectionService.setCollectionImage(collection, cover))
        nothing()
        assertTrue(collectionService.updateCollection(collection, InsertableCollection("Renamed", "Described")))
        only(updated(EntityType.COLLECTION, collection))
        assertFalse(collectionService.setCollectionImage(UUID.randomUUID(), cover))
        nothing()

        assertTrue(collectionService.delete(collection))
        only(deleted(EntityType.COLLECTION, collection))
        assertFalse(collectionService.delete(collection))
        nothing()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `adding and removing each kind of item marks the members of the collection`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val performer = artist("Performer")
        val album = album("Album", performer)
        val song = song(album, "Song", performer)
        val playlist = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Mix"))
        val collection = collectionService.createCollection(owner, InsertableCollection("Shelf"))
        val other = collectionService.createCollection(owner, InsertableCollection("Other"))
        clearRecordedChanges(database)
        val items = listOf(
            CollectionItemType.SONG to song,
            CollectionItemType.ALBUM to album,
            CollectionItemType.ARTIST to performer,
            CollectionItemType.PLAYLIST to playlist,
        )

        for ((kind, item) in items) {
            assertTrue(collectionService.addItem(collection, kind, item))
            only(members(EntityType.COLLECTION, collection))
            assertFalse(collectionService.addItem(collection, kind, item))
            assertFalse(collectionService.addItem(collection, kind, UUID.randomUUID()))
            nothing()
        }

        for ((kind, item) in items) {
            assertTrue(collectionService.removeItem(collection, kind, item))
            only(members(EntityType.COLLECTION, collection))
            assertFalse(collectionService.removeItem(collection, kind, item))
            assertFalse(collectionService.removeItem(other, kind, item))
            nothing()
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a user playlist marks the collections that contained it`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val playlist = userPlaylistService.getOrAddPlaylist(owner, null, InsertablePlaylist("Mix"))
        val holder = collectionService.createCollection(owner, InsertableCollection("Holder"))
        val bystander = collectionService.createCollection(stranger, InsertableCollection("Bystander"))
        assertTrue(collectionService.addItem(holder, CollectionItemType.PLAYLIST, playlist))
        clearRecordedChanges(database)

        assertTrue(userPlaylistService.delete(playlist))

        only(deleted(EntityType.USER_PLAYLIST, playlist), members(EntityType.COLLECTION, holder))
        assertTrue(collectionService.delete(bystander))
        only(deleted(EntityType.COLLECTION, bystander))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a deleted collection keeps only its deletion`(dialect: DbDialect) = runBlocking {
        setupContent(dialect)
        val album = album("Album")
        val collection = collectionService.createCollection(owner, InsertableCollection("Shelf"))
        assertTrue(collectionService.addItem(collection, CollectionItemType.ALBUM, album))
        assertEquals(
            setOf(created(EntityType.COLLECTION, collection), members(EntityType.COLLECTION, collection)),
            recordedChanges(database)
        )

        assertTrue(collectionService.delete(collection))

        only(deleted(EntityType.COLLECTION, collection))
    }
}
