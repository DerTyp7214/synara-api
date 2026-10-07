package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.EntityChangeConfig
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityChange
import dev.dertyp.data.EntityChangeAspect
import dev.dertyp.data.EntityChangeKind
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistMemberTable
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class EntityChangeServiceTest : KoinTest {
    private lateinit var database: Database
    private val recorder = EntityChangeRecorder()
    private val service = EntityChangeService(EntityChangeConfig())

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }
        database = TestDatabase.connect(
            dialect, "entity_change_service_test",
            UserTable,
            ImageTable,
            AlbumTable,
            ArtistTable,
            ArtistMemberTable,
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

    private fun libraryRow(
        type: EntityType,
        entity: UUID,
        at: Long,
        part: EntityChangeAspect = EntityChangeAspect.DATA,
        what: EntityChangeKind = EntityChangeKind.UPDATED,
        within: List<Pair<EntityType, UUID>> = emptyList()
    ): EntityChange {
        val row = UUID.randomUUID()
        EntityChangeTable.insert {
            it[id] = row
            it[entityType] = type
            it[entityId] = entity
            it[aspect] = part
            it[kind] = what
            it[changedAt] = at
        }
        for ((containerType, container) in within) {
            EntityChangeScopeTable.insert {
                it[changeId] = row
                it[scopeType] = containerType
                it[scopeId] = container
            }
        }
        return EntityChange(type, entity, part, what, at)
    }

    private fun userRow(
        owner: UUID,
        type: EntityType,
        entity: UUID,
        at: Long,
        part: EntityChangeAspect = EntityChangeAspect.LIKE
    ): EntityChange {
        UserEntityChangeTable.insert {
            it[userId] = owner
            it[entityType] = type
            it[entityId] = entity
            it[aspect] = part
            it[kind] = EntityChangeKind.UPDATED
            it[changedAt] = at
        }
        return EntityChange(type, entity, part, EntityChangeKind.UPDATED, at)
    }

    private fun linkAlbumArtist(album: UUID, artist: UUID) {
        AlbumArtistTable.insert {
            it[albumId] = album
            it[artistId] = artist
        }
    }

    private fun linkGroupMember(group: UUID, member: UUID) {
        ArtistMemberTable.insert {
            it[groupId] = group
            it[artistId] = member
        }
    }

    private fun insertCollection(owner: UUID): UUID {
        val collection = UUID.randomUUID()
        CollectionTable.insert {
            it[id] = collection
            it[name] = "Collection"
            it[creator] = owner
        }
        return collection
    }

    private fun relatedRows(
        caller: UUID,
        other: UUID,
        related: List<Pair<EntityType, UUID>>,
        unrelated: List<Pair<EntityType, UUID>>
    ): List<EntityChange> {
        unrelated.forEachIndexed { index, (type, entity) ->
            libraryRow(type, entity, 10L + index)
            userRow(caller, type, entity, 10L + index)
        }
        return related.flatMapIndexed { index, (type, entity) ->
            val at = 1000L + index * 10
            userRow(other, type, entity, at)
            listOfNotNull(
                libraryRow(type, entity, at),
                userRow(caller, type, entity, at + 3),
                if (type == EntityType.SONG) userRow(caller, type, entity, at + 6, EntityChangeAspect.TIMECODES) else null
            )
        }
    }

    private fun insertUserPlaylist(owner: UUID, songs: List<UUID>): UUID {
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

    private fun insertPlaylist(songs: List<UUID>): UUID {
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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `all changes returns the library rows and the caller's rows in time order from since on`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val song = UUID.randomUUID()
        val album = UUID.randomUUID()
        val artist = UUID.randomUUID()
        val playlist = UUID.randomUUID()

        val expected = db {
            libraryRow(EntityType.SONG, song, 99)
            userRow(caller, EntityType.ALBUM, album, 99)
            userRow(other, EntityType.SONG, song, 150)
            userRow(other, EntityType.SONG, song, 250, EntityChangeAspect.TIMECODES)
            listOf(
                libraryRow(EntityType.ALBUM, album, 100, what = EntityChangeKind.CREATED),
                userRow(caller, EntityType.SONG, song, 100),
                libraryRow(EntityType.ALBUM, album, 110, EntityChangeAspect.MEMBERS),
                userRow(caller, EntityType.SONG, song, 120, EntityChangeAspect.TIMECODES),
                libraryRow(EntityType.ARTIST, artist, 130, what = EntityChangeKind.DELETED),
                userRow(caller, EntityType.ARTIST, artist, 140),
                libraryRow(EntityType.USER_PLAYLIST, playlist, 200, EntityChangeAspect.MEMBERS),
            )
        }

        assertEquals(expected, service.allChanges(caller, 100).toList())
        assertEquals(expected.drop(2), service.allChanges(caller, 101).toList())
        assertEquals(expected.takeLast(1), service.allChanges(caller, 200).toList())
        assertEquals(emptyList<EntityChange>(), service.allChanges(caller, 201).toList())
        assertEquals(
            listOf(EntityType.SONG to 150L, EntityType.SONG to 250L),
            service.allChanges(other, 100).toList()
                .filter { it.aspect == EntityChangeAspect.LIKE || it.aspect == EntityChangeAspect.TIMECODES }
                .map { it.entityType to it.changedAt }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by artist returns the artist, what is scoped to it and the caller's rows for its songs and albums`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val artist = db { insertArtist("Wanted") }
        val stranger = db { insertArtist("Stranger") }
        val album = db { insertAlbum("Wanted") }
        val strangerAlbum = db { insertAlbum("Stranger") }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        val strangerSong = db { insertSong(strangerAlbum).also { linkSongArtist(it, stranger) } }
        val gone = UUID.randomUUID()
        db {
            linkAlbumArtist(album, artist)
            linkAlbumArtist(strangerAlbum, stranger)
        }
        val ofArtist = listOf(EntityType.ARTIST to artist)
        val ofStranger = listOf(EntityType.ARTIST to stranger)

        val expected = db {
            libraryRow(EntityType.ARTIST, stranger, 10)
            libraryRow(EntityType.SONG, strangerSong, 20, within = ofStranger + (EntityType.ALBUM to strangerAlbum))
            libraryRow(EntityType.ALBUM, strangerAlbum, 30, within = ofStranger)
            userRow(caller, EntityType.SONG, strangerSong, 40)
            userRow(caller, EntityType.ARTIST, stranger, 45)
            userRow(other, EntityType.SONG, song, 50)
            userRow(other, EntityType.ARTIST, artist, 55)
            libraryRow(EntityType.SONG, song, 5, within = ofArtist)
            listOf(
                libraryRow(EntityType.ARTIST, artist, 10),
                userRow(caller, EntityType.ARTIST, artist, 15),
                libraryRow(EntityType.ARTIST, artist, 20, EntityChangeAspect.MEMBERS),
                libraryRow(EntityType.ALBUM, album, 30, what = EntityChangeKind.CREATED, within = ofArtist),
                userRow(caller, EntityType.SONG, song, 40),
                libraryRow(EntityType.ALBUM, album, 50, EntityChangeAspect.MEMBERS, within = ofArtist),
                userRow(caller, EntityType.ALBUM, album, 60),
                libraryRow(EntityType.SONG, gone, 70, what = EntityChangeKind.DELETED, within = ofArtist),
                userRow(caller, EntityType.SONG, song, 80, EntityChangeAspect.TIMECODES),
            )
        }

        assertEquals(expected, service.byArtist(caller, artist, 10).toList())
        assertEquals(emptyList<EntityChange>(), service.byArtist(caller, UUID.randomUUID(), 0).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by album returns the album, what is scoped to it and the caller's rows for it and its songs`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val artist = db { insertArtist() }
        val album = db { insertAlbum("Wanted") }
        val strangerAlbum = db { insertAlbum("Stranger") }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        val strangerSong = db { insertSong(strangerAlbum).also { linkSongArtist(it, artist) } }
        val gone = UUID.randomUUID()
        val ofAlbum = listOf(EntityType.ALBUM to album, EntityType.ARTIST to artist)

        val expected = db {
            libraryRow(EntityType.ALBUM, strangerAlbum, 10)
            libraryRow(
                EntityType.SONG,
                strangerSong,
                20,
                within = listOf(EntityType.ALBUM to strangerAlbum, EntityType.ARTIST to artist)
            )
            userRow(caller, EntityType.SONG, strangerSong, 30)
            userRow(caller, EntityType.ALBUM, strangerAlbum, 35)
            userRow(other, EntityType.SONG, song, 40)
            userRow(other, EntityType.ALBUM, album, 45)
            userRow(other, EntityType.ARTIST, artist, 46)
            listOf(
                libraryRow(EntityType.ALBUM, album, 10),
                libraryRow(EntityType.SONG, song, 20, what = EntityChangeKind.CREATED, within = ofAlbum),
                libraryRow(EntityType.ARTIST, artist, 25),
                userRow(caller, EntityType.ALBUM, album, 30),
                userRow(caller, EntityType.ARTIST, artist, 36),
                libraryRow(EntityType.ALBUM, album, 40, EntityChangeAspect.MEMBERS),
                userRow(caller, EntityType.SONG, song, 50),
                userRow(caller, EntityType.SONG, song, 60, EntityChangeAspect.TIMECODES),
                libraryRow(EntityType.SONG, gone, 70, what = EntityChangeKind.DELETED, within = ofAlbum),
            )
        }

        assertEquals(expected, service.byAlbum(caller, album, 0).toList())
        assertEquals(emptyList<EntityChange>(), service.byAlbum(caller, UUID.randomUUID(), 0).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a deleted song is found through its album and its artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val artist = db { insertArtist() }
        val album = db { insertAlbum() }
        val song = db { insertSong(album).also { linkSongArtist(it, artist) } }
        db { linkAlbumArtist(album, artist) }

        dbQuery {
            recorder.created(EntityType.SONG, listOf(song))
            recorder.likesChanged(caller, EntityType.SONG, listOf(song))
        }
        dbQuery {
            recorder.deleting(EntityType.SONG, listOf(song))
            SongTable.deleteWhere { SongTable.id eq song }
        }

        val deleted = Triple(EntityType.SONG, EntityChangeAspect.DATA, EntityChangeKind.DELETED)
        val albumMembers = Triple(EntityType.ALBUM, EntityChangeAspect.MEMBERS, EntityChangeKind.UPDATED)
        val artistMembers = Triple(EntityType.ARTIST, EntityChangeAspect.MEMBERS, EntityChangeKind.UPDATED)
        fun List<EntityChange>.shape() = map { Triple(it.entityType, it.aspect, it.kind) }.toSet()

        val byAlbum = service.byAlbum(caller, album, 0).toList()
        assertEquals(setOf(deleted, albumMembers, artistMembers), byAlbum.shape())
        assertEquals(song, byAlbum.single { it.entityType == EntityType.SONG }.entityId)

        val byArtist = service.byArtist(caller, artist, 0).toList()
        assertEquals(setOf(deleted, albumMembers, artistMembers), byArtist.shape())
        assertEquals(song, byArtist.single { it.entityType == EntityType.SONG }.entityId)

        assertEquals(setOf(deleted, albumMembers, artistMembers), service.allChanges(caller, 0).toList().shape())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by playlist returns the playlist and the rows of the songs it contains now`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val album = db { insertAlbum() }
        val first = db { insertSong(album, "First") }
        val second = db { insertSong(album, "Second") }
        val outside = db { insertSong(album, "Outside") }
        val userPlaylist = db { insertUserPlaylist(caller, listOf(first, first, second)) }
        val playlist = db { insertPlaylist(listOf(second)) }
        val strangerPlaylist = db { insertUserPlaylist(other, listOf(outside)) }

        val rows = db {
            libraryRow(EntityType.USER_PLAYLIST, strangerPlaylist, 5)
            libraryRow(EntityType.SONG, outside, 6)
            userRow(caller, EntityType.SONG, outside, 8)
            userRow(caller, EntityType.USER_PLAYLIST, userPlaylist, 9)
            userRow(other, EntityType.SONG, first, 9)
            listOf(
                libraryRow(EntityType.ALBUM, album, 7),
                libraryRow(EntityType.USER_PLAYLIST, userPlaylist, 10, what = EntityChangeKind.CREATED),
                libraryRow(EntityType.USER_PLAYLIST, userPlaylist, 20, EntityChangeAspect.MEMBERS),
                libraryRow(EntityType.SONG, first, 30),
                userRow(caller, EntityType.SONG, first, 40),
                libraryRow(EntityType.SONG, second, 50),
                userRow(caller, EntityType.SONG, second, 60, EntityChangeAspect.TIMECODES),
                libraryRow(EntityType.PLAYLIST, playlist, 70, EntityChangeAspect.MEMBERS),
            )
        }

        assertEquals(rows.take(7), service.byPlaylist(caller, userPlaylist, 0).toList())
        assertEquals(rows.take(1) + rows.drop(5), service.byPlaylist(caller, playlist, 0).toList())
        assertEquals(emptyList<EntityChange>(), service.byPlaylist(caller, UUID.randomUUID(), 0).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by collection returns the collection, its direct members and what they contain`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val artist = db { insertArtist() }
        val album = db { insertAlbum() }
        val memberSong = db { insertSong(album, "Member") }
        val albumSong = db { insertSong(album, "Album only").also { linkSongArtist(it, artist) } }
        val playlist = db { insertUserPlaylist(caller, listOf(albumSong)) }
        val collection = UUID.randomUUID()
        val strangerCollection = UUID.randomUUID()
        db {
            linkAlbumArtist(album, artist)
            for (target in listOf(collection, strangerCollection)) {
                CollectionTable.insert {
                    it[id] = target
                    it[name] = "Collection"
                    it[creator] = caller
                }
            }
            CollectionSongTable.insert {
                it[collectionId] = collection
                it[songId] = memberSong
            }
            CollectionAlbumTable.insert {
                it[collectionId] = collection
                it[albumId] = album
            }
            CollectionArtistTable.insert {
                it[collectionId] = collection
                it[artistId] = artist
            }
            CollectionPlaylistTable.insert {
                it[collectionId] = collection
                it[playlistId] = playlist
            }
            CollectionSongTable.insert {
                it[collectionId] = strangerCollection
                it[songId] = albumSong
            }
        }

        val expected = db {
            libraryRow(EntityType.COLLECTION, strangerCollection, 5)
            userRow(other, EntityType.SONG, memberSong, 8)
            userRow(other, EntityType.ALBUM, album, 9)
            listOf(
                libraryRow(
                    EntityType.SONG,
                    albumSong,
                    6,
                    within = listOf(EntityType.ALBUM to album, EntityType.ARTIST to artist)
                ),
                userRow(caller, EntityType.SONG, albumSong, 7),
                libraryRow(EntityType.COLLECTION, collection, 10, what = EntityChangeKind.CREATED),
                libraryRow(EntityType.COLLECTION, collection, 20, EntityChangeAspect.MEMBERS),
                libraryRow(EntityType.SONG, memberSong, 30, within = listOf(EntityType.ALBUM to album)),
                userRow(caller, EntityType.SONG, memberSong, 40),
                libraryRow(EntityType.ALBUM, album, 50, within = listOf(EntityType.ARTIST to artist)),
                libraryRow(EntityType.ALBUM, album, 55, EntityChangeAspect.MEMBERS),
                userRow(caller, EntityType.ALBUM, album, 60),
                libraryRow(EntityType.ARTIST, artist, 70),
                userRow(caller, EntityType.ARTIST, artist, 80),
                libraryRow(EntityType.USER_PLAYLIST, playlist, 90, EntityChangeAspect.MEMBERS),
                userRow(caller, EntityType.USER_PLAYLIST, playlist, 95),
            )
        }

        assertEquals(expected, service.byCollection(caller, collection, 0).toList())
        assertEquals(expected.drop(6), service.byCollection(caller, collection, 50).toList())
        assertEquals(emptyList<EntityChange>(), service.byCollection(caller, UUID.randomUUID(), 0).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by album returns the artists of the album and of its songs and their group members once`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val albumArtist = db { insertArtist("Album artist") }
        val featured = db { insertArtist("Featured group") }
        val member = db { insertArtist("Member") }
        val unrelated = db { insertArtist("Unrelated") }
        val album = db { insertAlbum("Wanted").also { linkAlbumArtist(it, albumArtist) } }
        val elsewhere = db { insertAlbum("Elsewhere").also { linkAlbumArtist(it, unrelated) } }
        val plain = db { insertSong(album, "Plain").also { linkSongArtist(it, albumArtist) } }
        val collaboration = db {
            insertSong(album, "Collaboration").also {
                linkSongArtist(it, albumArtist)
                linkSongArtist(it, featured)
            }
        }
        val elsewhereSong = db {
            insertSong(elsewhere, "Elsewhere").also {
                linkSongArtist(it, unrelated)
                linkSongArtist(it, featured)
            }
        }
        db { linkGroupMember(featured, member) }

        val expected = db {
            relatedRows(
                caller,
                other,
                related = listOf(
                    EntityType.ARTIST to featured,
                    EntityType.SONG to collaboration,
                    EntityType.ARTIST to member,
                    EntityType.ALBUM to album,
                    EntityType.ARTIST to albumArtist,
                    EntityType.SONG to plain,
                ),
                unrelated = listOf(
                    EntityType.ARTIST to unrelated,
                    EntityType.ALBUM to elsewhere,
                    EntityType.SONG to elsewhereSong,
                )
            )
        }

        assertEquals(expected, service.byAlbum(caller, album, 0).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by artist returns its group members, the other credited artists and the albums of its songs once`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val group = db { insertArtist("Group") }
        val member = db { insertArtist("Member") }
        val coArtist = db { insertArtist("Co artist") }
        val featured = db { insertArtist("Featured") }
        val host = db { insertArtist("Host") }
        val unrelated = db { insertArtist("Unrelated") }
        val ownAlbum = db {
            insertAlbum("Own").also {
                linkAlbumArtist(it, group)
                linkAlbumArtist(it, coArtist)
            }
        }
        val hostAlbum = db { insertAlbum("Host").also { linkAlbumArtist(it, host) } }
        val unrelatedAlbum = db { insertAlbum("Unrelated").also { linkAlbumArtist(it, unrelated) } }
        val guestSong = db {
            insertSong(hostAlbum, "Guest").also {
                linkSongArtist(it, group)
                linkSongArtist(it, featured)
            }
        }
        val hostSong = db { insertSong(hostAlbum, "Host only").also { linkSongArtist(it, host) } }
        db { linkGroupMember(group, member) }

        val expected = db {
            relatedRows(
                caller,
                other,
                related = listOf(
                    EntityType.ARTIST to member,
                    EntityType.ARTIST to coArtist,
                    EntityType.SONG to guestSong,
                    EntityType.ARTIST to featured,
                    EntityType.ALBUM to hostAlbum,
                    EntityType.ARTIST to host,
                    EntityType.ALBUM to ownAlbum,
                    EntityType.ARTIST to group,
                ),
                unrelated = listOf(
                    EntityType.SONG to hostSong,
                    EntityType.ARTIST to unrelated,
                    EntityType.ALBUM to unrelatedAlbum,
                )
            )
        }

        assertEquals(expected, service.byArtist(caller, group, 0).toList())
        assertEquals(
            listOf(EntityType.ARTIST to member),
            service.byArtist(caller, member, 0).toList().map { it.entityType to it.entityId }.distinct()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by playlist returns the albums and artists of its songs and the artists of those albums once`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val songArtist = db { insertArtist("Song artist") }
        val albumArtist = db { insertArtist("Album artist group") }
        val member = db { insertArtist("Member") }
        val globalArtist = db { insertArtist("Global artist") }
        val unrelated = db { insertArtist("Unrelated") }
        val album = db { insertAlbum("Album").also { linkAlbumArtist(it, albumArtist) } }
        val globalAlbum = db { insertAlbum("Global album").also { linkAlbumArtist(it, globalArtist) } }
        val song = db { insertSong(album, "Song").also { linkSongArtist(it, songArtist) } }
        val sibling = db { insertSong(album, "Sibling").also { linkSongArtist(it, unrelated) } }
        val globalSong = db { insertSong(globalAlbum, "Global song").also { linkSongArtist(it, globalArtist) } }
        val userPlaylist = db { insertUserPlaylist(caller, listOf(song, song)) }
        val playlist = db { insertPlaylist(listOf(globalSong)) }
        db { linkGroupMember(albumArtist, member) }

        val ofUserPlaylist = listOf(
            EntityType.ARTIST to member,
            EntityType.ALBUM to album,
            EntityType.SONG to song,
            EntityType.ARTIST to albumArtist,
            EntityType.ARTIST to songArtist,
        )
        val ofPlaylist = listOf(
            EntityType.ARTIST to globalArtist,
            EntityType.SONG to globalSong,
            EntityType.ALBUM to globalAlbum,
        )
        val expected = db {
            relatedRows(
                caller,
                other,
                related = ofUserPlaylist + ofPlaylist,
                unrelated = listOf(EntityType.SONG to sibling, EntityType.ARTIST to unrelated)
            )
        }
        val userPlaylistRows = expected.filter { change -> ofUserPlaylist.any { it.second == change.entityId } }

        assertEquals(userPlaylistRows, service.byPlaylist(caller, userPlaylist, 0).toList())
        assertEquals(expected - userPlaylistRows.toSet(), service.byPlaylist(caller, playlist, 0).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by collection returns what its member albums, artists and playlists contain with their albums and artists once`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val albumArtist = db { insertArtist("Member album artist") }
        val albumSongArtist = db { insertArtist("Member album song group") }
        val groupMember = db { insertArtist("Group member") }
        val memberArtist = db { insertArtist("Member artist") }
        val guest = db { insertArtist("Guest") }
        val host = db { insertArtist("Host") }
        val playlistSongArtist = db { insertArtist("Playlist song artist") }
        val playlistAlbumArtist = db { insertArtist("Playlist album artist") }
        val songAlbumArtist = db { insertArtist("Member song album artist") }
        val unrelated = db { insertArtist("Unrelated") }
        val memberAlbum = db { insertAlbum("Member album").also { linkAlbumArtist(it, albumArtist) } }
        val artistAlbum = db { insertAlbum("Album of the member artist").also { linkAlbumArtist(it, memberArtist) } }
        val hostAlbum = db { insertAlbum("Host album").also { linkAlbumArtist(it, host) } }
        val playlistAlbum = db { insertAlbum("Playlist album").also { linkAlbumArtist(it, playlistAlbumArtist) } }
        val songAlbum = db { insertAlbum("Member song album").also { linkAlbumArtist(it, songAlbumArtist) } }
        val unrelatedAlbum = db { insertAlbum("Unrelated").also { linkAlbumArtist(it, unrelated) } }
        val albumSong = db { insertSong(memberAlbum, "Album song").also { linkSongArtist(it, albumSongArtist) } }
        val artistAlbumSong = db { insertSong(artistAlbum, "Artist album song").also { linkSongArtist(it, guest) } }
        val artistSong = db { insertSong(hostAlbum, "Artist song").also { linkSongArtist(it, memberArtist) } }
        val hostSong = db { insertSong(hostAlbum, "Host only").also { linkSongArtist(it, host) } }
        val playlistSong = db { insertSong(playlistAlbum, "Playlist song").also { linkSongArtist(it, playlistSongArtist) } }
        val memberSong = db { insertSong(songAlbum, "Member song").also { linkSongArtist(it, memberArtist) } }
        val unrelatedSong = db { insertSong(unrelatedAlbum, "Unrelated").also { linkSongArtist(it, unrelated) } }
        val playlist = db { insertUserPlaylist(caller, listOf(playlistSong)) }
        val strangerPlaylist = db { insertUserPlaylist(caller, listOf(unrelatedSong)) }
        val collection = db { insertCollection(caller) }
        db {
            linkGroupMember(albumSongArtist, groupMember)
            CollectionSongTable.insert {
                it[collectionId] = collection
                it[songId] = memberSong
            }
            CollectionAlbumTable.insert {
                it[collectionId] = collection
                it[albumId] = memberAlbum
            }
            CollectionArtistTable.insert {
                it[collectionId] = collection
                it[artistId] = memberArtist
            }
            CollectionPlaylistTable.insert {
                it[collectionId] = collection
                it[playlistId] = playlist
            }
        }

        val expected = db {
            relatedRows(
                caller,
                other,
                related = listOf(
                    EntityType.SONG to albumSong,
                    EntityType.ARTIST to albumSongArtist,
                    EntityType.ARTIST to groupMember,
                    EntityType.ARTIST to albumArtist,
                    EntityType.ALBUM to memberAlbum,
                    EntityType.ALBUM to artistAlbum,
                    EntityType.SONG to artistAlbumSong,
                    EntityType.ARTIST to guest,
                    EntityType.SONG to artistSong,
                    EntityType.ALBUM to hostAlbum,
                    EntityType.ARTIST to host,
                    EntityType.ARTIST to memberArtist,
                    EntityType.SONG to playlistSong,
                    EntityType.ALBUM to playlistAlbum,
                    EntityType.ARTIST to playlistSongArtist,
                    EntityType.ARTIST to playlistAlbumArtist,
                    EntityType.SONG to memberSong,
                    EntityType.ALBUM to songAlbum,
                    EntityType.ARTIST to songAlbumArtist,
                    EntityType.USER_PLAYLIST to playlist,
                    EntityType.COLLECTION to collection,
                ),
                unrelated = listOf(
                    EntityType.SONG to hostSong,
                    EntityType.SONG to unrelatedSong,
                    EntityType.ALBUM to unrelatedAlbum,
                    EntityType.ARTIST to unrelated,
                    EntityType.USER_PLAYLIST to strangerPlaylist,
                )
            )
        }

        assertEquals(
            expected.filterNot { it.entityType == EntityType.COLLECTION && it.aspect == EntityChangeAspect.LIKE },
            service.byCollection(caller, collection, 0).toList()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a result larger than one chunk arrives complete and in time order and the flow completes`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val other = db { insertUser() }
        val album = db { insertAlbum() }
        val librarySize = EntityChangeService.STREAM_CHUNK_SIZE * 2 + 345
        val userSize = EntityChangeService.STREAM_CHUNK_SIZE + 123
        val songs = List(librarySize) { UUID.randomUUID() }
        val rowIds = songs.associateWith { UUID.randomUUID() }
        db {
            SongTable.batchInsert(songs, shouldReturnGeneratedValues = false) { song ->
                this[SongTable.id] = song
                this[SongTable.title] = "Song"
                this[SongTable.albumId] = album
                this[SongTable.fileSize] = 0
                this[SongTable.duration] = 0
            }
            EntityChangeTable.batchInsert(songs.withIndex(), shouldReturnGeneratedValues = false) { (index, song) ->
                this[EntityChangeTable.id] = rowIds.getValue(song)
                this[EntityChangeTable.entityType] = EntityType.SONG
                this[EntityChangeTable.entityId] = song
                this[EntityChangeTable.aspect] = EntityChangeAspect.DATA
                this[EntityChangeTable.kind] = EntityChangeKind.UPDATED
                this[EntityChangeTable.changedAt] = 1000L + index / 7
            }
            EntityChangeScopeTable.batchInsert(rowIds.values, shouldReturnGeneratedValues = false) { row ->
                this[EntityChangeScopeTable.changeId] = row
                this[EntityChangeScopeTable.scopeType] = EntityType.ALBUM
                this[EntityChangeScopeTable.scopeId] = album
            }
            for (owner in listOf(caller, other)) {
                UserEntityChangeTable.batchInsert(
                    songs.take(userSize).withIndex(),
                    shouldReturnGeneratedValues = false
                ) { (index, song) ->
                    this[UserEntityChangeTable.userId] = owner
                    this[UserEntityChangeTable.entityType] = EntityType.SONG
                    this[UserEntityChangeTable.entityId] = song
                    this[UserEntityChangeTable.aspect] = EntityChangeAspect.LIKE
                    this[UserEntityChangeTable.kind] = EntityChangeKind.UPDATED
                    this[UserEntityChangeTable.changedAt] = 1000L + index / 3
                }
            }
        }

        for (pull in listOf(service.allChanges(caller, 1000), service.byAlbum(caller, album, 1000))) {
            val changes = withTimeout(60.seconds) { pull.toList() }

            assertEquals(librarySize + userSize, changes.size)
            assertEquals(changes.map { it.changedAt }.sorted(), changes.map { it.changedAt })
            assertEquals(
                songs.toSet(),
                changes.filter { it.aspect == EntityChangeAspect.DATA }.map { it.entityId }.toSet()
            )
            assertEquals(librarySize, changes.count { it.aspect == EntityChangeAspect.DATA })
            assertEquals(
                songs.take(userSize).toSet(),
                changes.filter { it.aspect == EntityChangeAspect.LIKE }.map { it.entityId }.toSet()
            )
            assertEquals(userSize, changes.count { it.aspect == EntityChangeAspect.LIKE })
        }

        db {
            EntityChangeTable.batchInsert(
                List(EntityChangeService.STREAM_CHUNK_SIZE) { UUID.randomUUID() },
                shouldReturnGeneratedValues = false
            ) { artist ->
                this[EntityChangeTable.entityType] = EntityType.ARTIST
                this[EntityChangeTable.entityId] = artist
                this[EntityChangeTable.aspect] = EntityChangeAspect.DATA
                this[EntityChangeTable.kind] = EntityChangeKind.CREATED
                this[EntityChangeTable.changedAt] = 5000L
            }
        }
        val exactlyOneChunk = withTimeout(60.seconds) { service.allChanges(caller, 5000).toList() }
        assertEquals(EntityChangeService.STREAM_CHUNK_SIZE, exactlyOneChunk.size)
        assertEquals(setOf(EntityType.ARTIST), exactlyOneChunk.map { it.entityType }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the window of a fresh table starts where tracking began`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val before = System.currentTimeMillis()
        val window = service.getWindow()
        val after = System.currentTimeMillis()

        assertTrue(window.availableSince in before..after)
        val margin = EntityChangeService.SERVER_TIME_MARGIN_MS
        assertTrue(margin in 60_000..120_000)
        assertTrue(window.serverTime in (before - margin)..(after - margin))
        assertEquals(window.availableSince, service.getWindow().availableSince)
        assertEquals(window.availableSince, service.trackingStartedAt())
        assertEquals(1L, db { EntityChangeTrackingTable.selectAll().count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the window of an old table ends at the retention`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        service.trackingStartedAt()
        val began = System.currentTimeMillis() - 100.days.inWholeMilliseconds
        db { EntityChangeTrackingTable.update { it[startedAt] = began } }
        fun daysAgo(time: Long, count: Int) = time - count.days.inWholeMilliseconds

        val before = System.currentTimeMillis()
        val standard = service.getWindow()
        val shorter = EntityChangeService(EntityChangeConfig(retentionDays = 7)).getWindow()
        val longer = EntityChangeService(EntityChangeConfig(retentionDays = 365)).getWindow()
        val after = System.currentTimeMillis()

        assertTrue(standard.availableSince in daysAgo(before, 30)..daysAgo(after, 30))
        assertTrue(shorter.availableSince in daysAgo(before, 7)..daysAgo(after, 7))
        assertEquals(began, longer.availableSince)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `server time used as the next since does not lose a change written right after it was handed out`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val album = db { insertAlbum() }
        val earlier = db { insertSong(album, "Earlier") }
        val later = db { insertSong(album, "Later") }

        dbQuery { recorder.updated(EntityType.SONG, listOf(earlier)) }
        val window = service.getWindow()
        dbQuery {
            recorder.updated(EntityType.SONG, listOf(later))
            recorder.likesChanged(caller, EntityType.SONG, listOf(later))
        }

        val pulled = service.allChanges(caller, window.serverTime).toList()

        assertTrue(window.serverTime < System.currentTimeMillis())
        assertEquals(
            setOf(later to EntityChangeAspect.DATA, later to EntityChangeAspect.LIKE, earlier to EntityChangeAspect.DATA),
            pulled.map { it.entityId to it.aspect }.toSet()
        )
        assertEquals(
            emptyList<EntityChange>(),
            service.allChanges(caller, System.currentTimeMillis() + 60.seconds.inWholeMilliseconds).toList()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a row whose commit became visible up to a minute after its stamp is returned by the next pull`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val slowLibraryCommit = UUID.randomUUID()
        val slowUserCommit = UUID.randomUUID()
        val tooSlow = UUID.randomUUID()

        val window = service.getWindow()
        val handedOut = System.currentTimeMillis()
        val stamp = handedOut - 55.seconds.inWholeMilliseconds
        db {
            libraryRow(EntityType.ARTIST, slowLibraryCommit, stamp)
            userRow(caller, EntityType.ARTIST, slowUserCommit, stamp)
            libraryRow(EntityType.ARTIST, tooSlow, window.serverTime - 1)
        }

        assertTrue(handedOut - window.serverTime >= 60.seconds.inWholeMilliseconds)
        assertTrue(window.serverTime <= stamp)
        assertEquals(
            setOf(slowLibraryCommit, slowUserCommit),
            service.allChanges(caller, window.serverTime).toList().map { it.entityId }.toSet()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `cleanup deletes only rows older than the retention in both tables with their scopes`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val now = System.currentTimeMillis()
        val old = now - 31.days.inWholeMilliseconds
        val recent = now - 29.days.inWholeMilliseconds
        val oldSong = UUID.randomUUID()
        val recentSong = UUID.randomUUID()
        val album = UUID.randomUUID()
        db {
            libraryRow(EntityType.SONG, oldSong, old, within = listOf(EntityType.ALBUM to album))
            libraryRow(EntityType.ALBUM, album, old, EntityChangeAspect.MEMBERS)
            libraryRow(EntityType.SONG, recentSong, recent, within = listOf(EntityType.ALBUM to album))
            libraryRow(EntityType.ALBUM, album, now)
            userRow(caller, EntityType.SONG, oldSong, old)
            userRow(caller, EntityType.SONG, oldSong, old, EntityChangeAspect.TIMECODES)
            userRow(caller, EntityType.SONG, recentSong, recent)
        }

        val result = service.cleanup()

        assertEquals(mapOf<String, Any?>("deletedEntityChanges" to 2, "deletedUserEntityChanges" to 2), result)
        assertEquals(
            listOf(
                recentSong to EntityChangeAspect.DATA,
                recentSong to EntityChangeAspect.LIKE,
                album to EntityChangeAspect.DATA
            ),
            service.allChanges(caller, 0).toList().map { it.entityId to it.aspect }
        )
        assertEquals(1L, db { EntityChangeScopeTable.selectAll().count() })
        assertEquals(
            mapOf<String, Any?>("deletedEntityChanges" to 0, "deletedUserEntityChanges" to 0),
            EntityChangeService(EntityChangeConfig(retentionDays = 30)).cleanup()
        )
        assertEquals(
            mapOf<String, Any?>("deletedEntityChanges" to 1, "deletedUserEntityChanges" to 1),
            EntityChangeService(EntityChangeConfig(retentionDays = 7)).cleanup()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `by artist returns the members row of an album that joined the artist after the row was written`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val former = db { insertArtist("Former") }
        val current = db { insertArtist("Current") }
        val album = db { insertAlbum("Album").also { linkAlbumArtist(it, former) } }
        db { recorder.membersChanged(EntityType.ALBUM, listOf(album)) }

        db {
            AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId eq album }
            linkAlbumArtist(album, current)
            recorder.updated(EntityType.ALBUM, listOf(album), containersChanged = true)
        }

        assertEquals(
            setOf(
                Triple(EntityType.ALBUM, album, EntityChangeAspect.DATA),
                Triple(EntityType.ALBUM, album, EntityChangeAspect.MEMBERS),
                Triple(EntityType.ARTIST, current, EntityChangeAspect.MEMBERS),
            ),
            service.byArtist(caller, current, 0).toList().map { Triple(it.entityType, it.entityId, it.aspect) }.toSet()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rows written while a pull is in progress do not extend it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val album = db { insertAlbum().also { linkAlbumArtist(it, insertArtist()) } }
        val artist = db { AlbumArtistTable.selectAll().single()[AlbumArtistTable.artistId].value }
        val playlist = db { insertPlaylist(emptyList()) }
        val collection = db {
            UUID.randomUUID().also { collection ->
                CollectionTable.insert {
                    it[id] = collection
                    it[name] = "Collection"
                    it[creator] = caller
                }
            }
        }
        val size = EntityChangeService.STREAM_CHUNK_SIZE * 2 + 10
        val songs = List(size) { UUID.randomUUID() }
        val rowIds = songs.associateWith { UUID.randomUUID() }
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
            PlaylistSongTable.batchInsert(songs.withIndex(), shouldReturnGeneratedValues = false) { (index, song) ->
                this[PlaylistSongTable.playlistId] = playlist
                this[PlaylistSongTable.songId] = song
                this[PlaylistSongTable.position] = index
            }
            CollectionSongTable.batchInsert(songs, shouldReturnGeneratedValues = false) { song ->
                this[CollectionSongTable.collectionId] = collection
                this[CollectionSongTable.songId] = song
            }
            EntityChangeTable.batchInsert(songs.withIndex(), shouldReturnGeneratedValues = false) { (index, song) ->
                this[EntityChangeTable.id] = rowIds.getValue(song)
                this[EntityChangeTable.entityType] = EntityType.SONG
                this[EntityChangeTable.entityId] = song
                this[EntityChangeTable.aspect] = EntityChangeAspect.DATA
                this[EntityChangeTable.kind] = EntityChangeKind.UPDATED
                this[EntityChangeTable.changedAt] = 1000L + index
            }
            EntityChangeScopeTable.batchInsert(
                rowIds.values.flatMap { row -> listOf(row to (EntityType.ALBUM to album), row to (EntityType.ARTIST to artist)) },
                shouldReturnGeneratedValues = false
            ) { (row, scope) ->
                this[EntityChangeScopeTable.changeId] = row
                this[EntityChangeScopeTable.scopeType] = scope.first
                this[EntityChangeScopeTable.scopeId] = scope.second
            }
        }
        val pulls = listOf(
            { service.allChanges(caller, 0) },
            { service.byArtist(caller, artist, 0) },
            { service.byAlbum(caller, album, 0) },
            { service.byPlaylist(caller, playlist, 0) },
            { service.byCollection(caller, collection, 0) },
        )

        val firstRow = rowIds.getValue(songs.first())

        for (pull in pulls) {
            var written = false
            val changes = withTimeout(60.seconds) {
                pull().onEach {
                    if (!written) {
                        written = true
                        db {
                            EntityChangeTable.update({ EntityChangeTable.id eq firstRow }) {
                                it[changedAt] = System.currentTimeMillis() + 1
                            }
                        }
                    }
                }.toList()
            }

            assertEquals(songs, changes.map { it.entityId })
            db { EntityChangeTable.update({ EntityChangeTable.id eq firstRow }) { it[changedAt] = 1000L } }
        }

        val moved = System.currentTimeMillis()
        db { EntityChangeTable.update({ EntityChangeTable.id eq firstRow }) { it[changedAt] = moved } }
        assertEquals(
            songs.drop(1) + songs.first(),
            withTimeout(60.seconds) { service.allChanges(caller, 0).toList() }.map { it.entityId }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class, names = ["POSTGRES"])
    fun `a change committed after the window was handed out is stamped after it and found by the next pull`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val caller = db { insertUser() }
        val artist = db { insertArtist() }
        val recorded = CountDownLatch(1)
        val release = CountDownLatch(1)
        var recordedAt = 0L
        val writer = thread {
            transaction(database) {
                recorder.updated(EntityType.ARTIST, listOf(artist))
                recorder.likesChanged(caller, EntityType.ARTIST, listOf(artist))
                recordedAt = EntityChangeTable.selectAll().single()[EntityChangeTable.changedAt]
                recorded.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(recorded.await(30, TimeUnit.SECONDS))
        Thread.sleep(20)

        val window = service.getWindow()
        val handedOut = window.serverTime + EntityChangeService.SERVER_TIME_MARGIN_MS
        assertEquals(emptyList<EntityChange>(), service.allChanges(caller, 0).toList())
        release.countDown()
        writer.join()

        val stamps = db {
            EntityChangeTable.selectAll().map { it[EntityChangeTable.changedAt] } +
                    UserEntityChangeTable.selectAll().map { it[UserEntityChangeTable.changedAt] }
        }
        assertEquals(2, stamps.size)
        assertEquals(1, stamps.toSet().size)
        assertTrue(recordedAt < handedOut)
        assertTrue(stamps.first() >= handedOut)
        assertEquals(
            setOf(EntityChangeAspect.DATA, EntityChangeAspect.LIKE),
            service.allChanges(caller, handedOut).toList().map { it.aspect }.toSet()
        )
    }
}
