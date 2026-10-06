package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AnimatedImageTable
import dev.dertyp.db.CollectionAlbumTable
import dev.dertyp.db.CollectionTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PlaylistTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserPlaylistSongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.members
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.recordedScopes
import dev.dertyp.testing.updated
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID

class LibraryMergeEntityChangeTest : EntityChangeLibraryTest() {
    private fun image(contentHash: String): UUID = db {
        ImageTable.insertAndGetId {
            it[path] = "$contentHash-${UUID.randomUUID()}.jpg"
            it[imageHash] = contentHash
            it[origin] = "test"
        }.value
    }

    private fun playlistWith(member: UUID): UUID = db {
        val holder = UserPlaylistTable.insertAndGetId {
            it[name] = "Playlist"
            it[description] = ""
            it[creator] = owner
        }.value
        UserPlaylistSongTable.insert {
            it[playlistId] = holder
            it[songId] = member
        }
        holder
    }

    private fun indexedAt(song: UUID, moment: Long, bytes: Long = 0) = db {
        SongTable.update({ SongTable.id eq song }) {
            it[inserted] = moment
            it[fileSize] = bytes
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging duplicate songs records the removed song, the kept one and their containers`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val keptArtist = artist("Kept Artist")
            val removedArtist = artist("Removed Artist")
            val album = album("Album")
            val kept = song(album, "Title", keptArtist, location = "/music/same.flac")
            val removed = song(album, "Title", removedArtist, location = "/music/same.flac")
            indexedAt(kept, 1000)
            indexedAt(removed, 2000)
            val playlist = playlistWith(removed)

            val result = libraryMergeService.mergeDuplicates()

            assertEquals(1, result["songsMerged"])
            assertEquals(
                setOf(
                    deleted(EntityType.SONG, removed),
                    updated(EntityType.SONG, kept),
                    members(EntityType.ALBUM, album),
                    members(EntityType.ARTIST, keptArtist),
                    members(EntityType.ARTIST, removedArtist),
                    members(EntityType.USER_PLAYLIST, playlist),
                ),
                recordedChanges(database)
            )
            assertEquals(
                setOf(EntityType.ALBUM to album, EntityType.ARTIST to removedArtist),
                recordedScopes(database, EntityType.SONG, removed)
            )
            assertEquals(
                setOf(EntityType.ALBUM to album, EntityType.ARTIST to keptArtist, EntityType.ARTIST to removedArtist),
                recordedScopes(database, EntityType.SONG, kept)
            )
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging songs repeated on one album records the smaller file as deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val keptArtist = artist("Kept Artist")
        val removedArtist = artist("Removed Artist")
        val album = album("Album")
        val kept = song(album, "Title", keptArtist)
        val removed = song(album, "Title", removedArtist)
        indexedAt(kept, 1000, bytes = 900)
        indexedAt(removed, 2000, bytes = 100)
        val playlist = playlistWith(removed)

        val result = libraryMergeService.mergeDuplicates()

        assertEquals(1, result["sameAlbumSongsMerged"])
        assertEquals(
            setOf(
                deleted(EntityType.SONG, removed),
                updated(EntityType.SONG, kept),
                members(EntityType.ALBUM, album),
                members(EntityType.ARTIST, keptArtist),
                members(EntityType.ARTIST, removedArtist),
                members(EntityType.USER_PLAYLIST, playlist),
            ),
            recordedChanges(database)
        )
        assertTrue(EntityType.ALBUM to album in recordedScopes(database, EntityType.SONG, removed))
        assertEquals(
            setOf(EntityType.ALBUM to album, EntityType.ARTIST to keptArtist, EntityType.ARTIST to removedArtist),
            recordedScopes(database, EntityType.SONG, kept)
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging duplicate albums records the removed album, its songs and the kept album`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val keptArtist = artist("Kept Artist")
            val removedArtist = artist("Removed Artist")
            val kept = album("Kept Album", keptArtist, tracks = 5)
            val removed = album("Removed Album", removedArtist, tracks = 1)
            db {
                AlbumTable.update({ AlbumTable.id eq kept }) { it[originalId] = "store:1" }
                AlbumTable.update({ AlbumTable.id eq removed }) { it[originalId] = "store:1" }
            }
            val stays = song(kept, "Stays")
            val moves = song(removed, "Moves", track = 2)
            val collection = db {
                val holder = CollectionTable.insertAndGetId {
                    it[name] = "Collection"
                    it[creator] = owner
                }.value
                CollectionAlbumTable.insert {
                    it[collectionId] = holder
                    it[albumId] = removed
                }
                holder
            }

            assertEquals(1, libraryMergeService.mergeDuplicateAlbums())

            assertEquals(
                setOf(
                    deleted(EntityType.ALBUM, removed),
                    updated(EntityType.ALBUM, kept),
                    members(EntityType.ALBUM, kept),
                    updated(EntityType.SONG, moves),
                    members(EntityType.ARTIST, keptArtist),
                    members(EntityType.ARTIST, removedArtist),
                    members(EntityType.COLLECTION, collection),
                ),
                recordedChanges(database)
            )
            assertEquals(setOf(EntityType.ALBUM to kept), recordedScopes(database, EntityType.SONG, moves))
            assertEquals(setOf(EntityType.ARTIST to removedArtist), recordedScopes(database, EntityType.ALBUM, removed))
            assertEquals(
                setOf(EntityType.ARTIST to keptArtist, EntityType.ARTIST to removedArtist),
                recordedScopes(database, EntityType.ALBUM, kept)
            )
            assertEquals(kept, runBlocking { songService.byId(stays)!!.album!!.id })
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `splitting wrongly merged songs into a new album records the album and the moved song`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumCover = image("album-cover")
            val otherCover = image("other-cover")
            val album = album("Album", tracks = 2)
            val stays = song(album, "Stays")
            val moves = song(album, "Moves", track = 2)
            db {
                AlbumTable.update({ AlbumTable.id eq album }) { it[cover] = EntityID(albumCover, ImageTable) }
                SongTable.update({ SongTable.id eq stays }) { it[cover] = EntityID(albumCover, ImageTable) }
                SongTable.update({ SongTable.id eq moves }) { it[cover] = EntityID(otherCover, ImageTable) }
            }

            assertEquals(1, libraryMergeService.fixIncorrectMerges())

            val split = db { SongTable.selectAll().where { SongTable.id eq moves }.single()[SongTable.albumId].value }
            assertTrue(split != album)
            assertEquals(
                setOf(
                    created(EntityType.ALBUM, split),
                    members(EntityType.ALBUM, split),
                    members(EntityType.ALBUM, album),
                    updated(EntityType.ALBUM, album),
                    updated(EntityType.SONG, moves),
                ),
                recordedChanges(database)
            )
            assertEquals(setOf(EntityType.ALBUM to split), recordedScopes(database, EntityType.SONG, moves))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging duplicate images records the entities whose cover is repointed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val keptImage = image("same-hash")
        val removedImage = image("same-hash")
        val repointed = album("Repointed")
        val untouched = album("Untouched")
        val performer = artist("Performer")
        db {
            AlbumTable.update({ AlbumTable.id eq repointed }) { it[cover] = EntityID(removedImage, ImageTable) }
            AlbumTable.update({ AlbumTable.id eq untouched }) { it[cover] = EntityID(keptImage, ImageTable) }
        }
        song(repointed, "One", performer)
        song(untouched, "Two", performer)
        val result = libraryMergeService.mergeDuplicates()

        assertEquals(1, result["imagesMerged"])
        val survivor = db { ImageTable.selectAll().where { ImageTable.imageHash eq "same-hash" }.single()[ImageTable.id].value }
        val changedAlbum = if (survivor == keptImage) repointed else untouched
        assertEquals(setOf(updated(EntityType.ALBUM, changedAlbum)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging duplicate images records the songs and albums whose animated cover is repointed`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val firstImage = image("same-hash")
        val secondImage = image("same-hash")
        fun animated(still: UUID): UUID = db {
            AnimatedImageTable.insertAndGetId {
                it[path] = "${UUID.randomUUID()}.mp4"
                it[contentHash] = UUID.randomUUID().toString()
                it[origin] = "test"
                it[imageId] = EntityID(still, ImageTable)
            }.value
        }
        val firstAnimated = animated(firstImage)
        val secondAnimated = animated(secondImage)
        val moving = album("Moving")
        val holder = album("Holder")
        val track = song(holder, "Track")
        db {
            AlbumTable.update({ AlbumTable.id eq moving }) {
                it[animatedCover] = EntityID(firstAnimated, AnimatedImageTable)
            }
            SongTable.update({ SongTable.id eq track }) {
                it[animatedCover] = EntityID(secondAnimated, AnimatedImageTable)
            }
        }

        val result = libraryMergeService.mergeDuplicates()

        assertEquals(1, result["imagesMerged"])
        val survivor = db { ImageTable.selectAll().where { ImageTable.imageHash eq "same-hash" }.single()[ImageTable.id].value }
        assertEquals(
            listOf(survivor, survivor),
            db { AnimatedImageTable.selectAll().map { it[AnimatedImageTable.imageId]?.value } }
        )
        val expected = if (survivor == firstImage) updated(EntityType.SONG, track) else updated(EntityType.ALBUM, moving)
        assertEquals(setOf(expected), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `merging duplicate images records the playlists and collections whose image is repointed`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val firstImage = image("same-hash")
        val secondImage = image("same-hash")
        val (userPlaylist, global, collection) = db {
            val userPlaylist = UserPlaylistTable.insertAndGetId {
                it[name] = "Mix"
                it[description] = ""
                it[creator] = owner
                it[imageId] = EntityID(firstImage, ImageTable)
            }.value
            val global = PlaylistTable.insertAndGetId {
                it[name] = "Global"
                it[imageId] = EntityID(secondImage, ImageTable)
            }.value
            val collection = CollectionTable.insertAndGetId {
                it[name] = "Shelf"
                it[creator] = owner
                it[imageId] = EntityID(secondImage, ImageTable)
            }.value
            Triple(userPlaylist, global, collection)
        }

        val result = libraryMergeService.mergeDuplicates()

        assertEquals(1, result["imagesMerged"])
        val survivor = db { ImageTable.selectAll().where { ImageTable.imageHash eq "same-hash" }.single()[ImageTable.id].value }
        val expected = if (survivor == firstImage) {
            setOf(updated(EntityType.PLAYLIST, global), updated(EntityType.COLLECTION, collection))
        } else {
            setOf(updated(EntityType.USER_PLAYLIST, userPlaylist))
        }
        assertEquals(expected, recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `songs merged on one album keep their album and artists as scopes after they are deleted`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val keptArtist = artist("Kept Artist")
        val removedArtist = artist("Removed Artist")
        val featured = artist("Featured")
        val album = album("Album")
        val kept = song(album, "Title", keptArtist)
        val removed = song(album, "Title", removedArtist, featured)
        indexedAt(kept, 1000, bytes = 900)
        indexedAt(removed, 2000, bytes = 100)

        val result = libraryMergeService.mergeDuplicates()

        assertEquals(1, result["sameAlbumSongsMerged"])
        assertEquals(0, db { SongTable.selectAll().where { SongTable.id eq removed }.count() })
        assertTrue(deleted(EntityType.SONG, removed) in recordedChanges(database))
        assertEquals(
            setOf(
                EntityType.ALBUM to album,
                EntityType.ARTIST to removedArtist,
                EntityType.ARTIST to featured,
            ),
            recordedScopes(database, EntityType.SONG, removed)
        )
    }
}
