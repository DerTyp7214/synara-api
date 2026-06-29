package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.InsertableCollection
import dev.dertyp.db.*
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.LinkResolverService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class CollectionServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: CollectionService

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }
        database = TestDatabase.connect(dialect, "collection_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                SongTable,
                SongArtistTable,
                AlbumArtistTable,
                UserPlaylistTable,
                UserPlaylistSongTable,
                CollectionTable,
                CollectionSongTable,
                CollectionAlbumTable,
                CollectionArtistTable,
                CollectionPlaylistTable,
            )
        }
        service = CollectionService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertUser(name: String = "user_${UUID.randomUUID()}"): UUID {
        val uid = UUID.randomUUID()
        UserTable.insert {
            it[id] = uid
            it[username] = name
            it[passwordHash] = "x"
        }
        return uid
    }

    private fun insertAlbum(name: String = "Album"): UUID {
        val aid = UUID.randomUUID()
        AlbumTable.insert {
            it[id] = aid
            it[AlbumTable.name] = name
        }
        return aid
    }

    private fun insertSong(albumId: UUID, fileSize: Long): UUID {
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[title] = "Song"
            it[SongTable.albumId] = albumId
            it[SongTable.fileSize] = fileSize
        }
        return sid
    }

    private fun insertArtist(name: String = "Artist"): UUID {
        val aid = UUID.randomUUID()
        ArtistTable.insert {
            it[id] = aid
            it[ArtistTable.name] = name
        }
        return aid
    }

    private fun linkSongArtist(songId: UUID, artistId: UUID) {
        SongArtistTable.insert {
            it[SongArtistTable.songId] = songId
            it[SongArtistTable.artistId] = artistId
        }
    }

    private fun linkAlbumArtist(albumId: UUID, artistId: UUID) {
        AlbumArtistTable.insert {
            it[AlbumArtistTable.albumId] = albumId
            it[AlbumArtistTable.artistId] = artistId
        }
    }

    private fun insertUserPlaylist(creator: UUID, songIds: List<UUID>): UUID {
        val pid = UUID.randomUUID()
        UserPlaylistTable.insert {
            it[id] = pid
            it[name] = "PL"
            it[description] = ""
            it[UserPlaylistTable.creator] = EntityID(creator, UserTable)
        }
        songIds.forEachIndexed { i, sid ->
            UserPlaylistSongTable.insert {
                it[playlistId] = pid
                it[UserPlaylistSongTable.songId] = sid
                it[addedAt] = 1_000L + i
            }
        }
        return pid
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createCollection persists and byId returns metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val imageId = UUID.randomUUID()
        val userId = transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "p"
                it[imageHash] = "h"
                it[origin] = "o"
                it[blurHash] = "bh"
            }
            insertUser()
        }

        val id = service.createCollection(userId, InsertableCollection("My Coll", "desc", imageId))
        val c = service.byId(id)

        assertNotNull(c)
        assertEquals("My Coll", c!!.name)
        assertEquals("desc", c.description)
        assertEquals(userId, c.creator)
        assertEquals(imageId, c.imageId)
        assertEquals("bh", c.blurHash)
        assertEquals(0, c.songCount)
        assertEquals(0L, c.totalSizeBytes)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId returns null for unknown id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assertNull(service.byId(UUID.randomUUID()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateCollection changes metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val id = service.createCollection(userId, InsertableCollection("Old", "old desc"))

        assertTrue(service.updateCollection(id, InsertableCollection("New", "new desc")))

        val c = service.byId(id)
        assertEquals("New", c!!.name)
        assertEquals("new desc", c.description)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allCollections filters by creator and orders by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (user1, user2) = transaction(database) { insertUser() to insertUser() }
        service.createCollection(user1, InsertableCollection("B Coll"))
        service.createCollection(user1, InsertableCollection("A Coll"))
        service.createCollection(user2, InsertableCollection("C Coll"))

        val forUser1 = service.allCollections(user1)
        assertEquals(listOf("A Coll", "B Coll"), forUser1.map { it.name })

        assertEquals(3, service.allCollections(null).size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `addItem is idempotent`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            insertUser() to insertSong(insertAlbum(), 100)
        }
        val id = service.createCollection(userId, InsertableCollection("C"))

        assertTrue(service.addItem(id, CollectionItemType.SONG, songId))
        assertFalse(service.addItem(id, CollectionItemType.SONG, songId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `removeItem removes only when present`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            insertUser() to insertSong(insertAlbum(), 100)
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.SONG, songId)

        assertTrue(service.removeItem(id, CollectionItemType.SONG, songId))
        assertFalse(service.removeItem(id, CollectionItemType.SONG, songId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `delete removes the collection`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            insertUser() to insertSong(insertAlbum(), 100)
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.SONG, songId)

        assertTrue(service.delete(id))
        assertNull(service.byId(id))
    }

    private data class FlowFixture(
        val userId: UUID,
        val album: UUID,
        val directSong1: UUID,
        val directSong2: UUID,
        val artist: UUID,
        val playlist: UUID,
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `per-type flows return only explicitly added ids of that type`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val f = transaction(database) {
            val userId = insertUser()
            val album = insertAlbum()
            insertSong(album, 10)
            FlowFixture(
                userId = userId,
                album = album,
                directSong1 = insertSong(insertAlbum(), 20),
                directSong2 = insertSong(insertAlbum(), 30),
                artist = insertArtist(),
                playlist = insertUserPlaylist(userId, listOf(insertSong(insertAlbum(), 40))),
            )
        }

        val id = service.createCollection(f.userId, InsertableCollection("C"))
        transaction(database) {
            insertItem(id, CollectionItemType.SONG, f.directSong1, 1)
            insertItem(id, CollectionItemType.SONG, f.directSong2, 2)
            insertItem(id, CollectionItemType.ALBUM, f.album, 3)
            insertItem(id, CollectionItemType.ARTIST, f.artist, 4)
            insertItem(id, CollectionItemType.PLAYLIST, f.playlist, 5)
        }

        assertEquals(listOf(f.directSong1, f.directSong2), service.songIds(id).toList())
        assertEquals(listOf(f.album), service.albumIds(id).toList())
        assertEquals(listOf(f.artist), service.artistIds(id).toList())
        assertEquals(listOf(f.playlist), service.playlistIds(id).toList())
    }

    private fun insertItem(collectionId: UUID, type: CollectionItemType, itemId: UUID, addedAt: Long) {
        when (type) {
            CollectionItemType.SONG -> CollectionSongTable.insert {
                it[CollectionSongTable.collectionId] = collectionId
                it[songId] = itemId
                it[CollectionSongTable.addedAt] = addedAt
            }
            CollectionItemType.ALBUM -> CollectionAlbumTable.insert {
                it[CollectionAlbumTable.collectionId] = collectionId
                it[albumId] = itemId
                it[CollectionAlbumTable.addedAt] = addedAt
            }
            CollectionItemType.ARTIST -> CollectionArtistTable.insert {
                it[CollectionArtistTable.collectionId] = collectionId
                it[artistId] = itemId
                it[CollectionArtistTable.addedAt] = addedAt
            }
            CollectionItemType.PLAYLIST -> CollectionPlaylistTable.insert {
                it[CollectionPlaylistTable.collectionId] = collectionId
                it[playlistId] = itemId
                it[CollectionPlaylistTable.addedAt] = addedAt
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stats sum directly added songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, s1, s2) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            Triple(u, insertSong(album, 100), insertSong(album, 200))
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.SONG, s1)
        service.addItem(id, CollectionItemType.SONG, s2)

        val c = service.byId(id)!!
        assertEquals(2, c.songCount)
        assertEquals(300L, c.totalSizeBytes)
        assertEquals(2, c.songItemCount)
        assertEquals(0, c.albumCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stats expand album items into their songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, album) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            insertSong(album, 10)
            insertSong(album, 20)
            insertSong(album, 30)
            u to album
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.ALBUM, album)

        val c = service.byId(id)!!
        assertEquals(3, c.songCount)
        assertEquals(60L, c.totalSizeBytes)
        assertEquals(1, c.albumCount)
        assertEquals(0, c.songItemCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stats expand artist items via direct songs and albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, artist) = transaction(database) {
            val u = insertUser()
            val artist = insertArtist()

            val directSong = insertSong(insertAlbum(), 5)
            linkSongArtist(directSong, artist)

            val artistAlbum = insertAlbum()
            insertSong(artistAlbum, 7)
            insertSong(artistAlbum, 8)
            linkAlbumArtist(artistAlbum, artist)

            u to artist
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.ARTIST, artist)

        val c = service.byId(id)!!
        assertEquals(3, c.songCount)
        assertEquals(20L, c.totalSizeBytes)
        assertEquals(1, c.artistCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stats expand playlist items into their songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, playlist) = transaction(database) {
            val u = insertUser()
            val playlist = insertUserPlaylist(u, listOf(insertSong(insertAlbum(), 11), insertSong(insertAlbum(), 12)))
            u to playlist
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.PLAYLIST, playlist)

        val c = service.byId(id)!!
        assertEquals(2, c.songCount)
        assertEquals(23L, c.totalSizeBytes)
        assertEquals(1, c.playlistCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stats count a song once when reached via both an album and directly`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, album, s1) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            val s1 = insertSong(album, 100)
            insertSong(album, 200)
            Triple(u, album, s1)
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.ALBUM, album)
        service.addItem(id, CollectionItemType.SONG, s1)

        val c = service.byId(id)!!
        assertEquals(2, c.songCount)
        assertEquals(300L, c.totalSizeBytes)
        assertEquals(1, c.albumCount)
        assertEquals(1, c.songItemCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `addItem rejects items whose referenced entity does not exist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val id = service.createCollection(userId, InsertableCollection("C"))

        assertFalse(service.addItem(id, CollectionItemType.SONG, UUID.randomUUID()))
        assertFalse(service.addItem(id, CollectionItemType.ALBUM, UUID.randomUUID()))
        assertFalse(service.addItem(id, CollectionItemType.ARTIST, UUID.randomUUID()))
        assertFalse(service.addItem(id, CollectionItemType.PLAYLIST, UUID.randomUUID()))

        val c = service.byId(id)!!
        assertEquals(0, c.songItemCount)
        assertEquals(0, c.albumCount)
        assertEquals(0, c.artistCount)
        assertEquals(0, c.playlistCount)
        assertEquals(0, c.songCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a referenced entity cascades the link away`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assumeTrue(dialect == DbDialect.POSTGRES, "FK cascade requires foreign key enforcement")

        val (userId, songId) = transaction(database) {
            insertUser() to insertSong(insertAlbum(), 100)
        }
        val id = service.createCollection(userId, InsertableCollection("C"))
        service.addItem(id, CollectionItemType.SONG, songId)
        assertEquals(listOf(songId), service.songIds(id).toList())

        transaction(database) { SongTable.deleteWhere { SongTable.id eq songId } }

        assertEquals(emptyList<UUID>(), service.songIds(id).toList())
        assertEquals(0, service.byId(id)!!.songItemCount)
    }

    private val searchTables = arrayOf(
        UserTable, ImageTable, ImageMetadataTable, AnimatedImageTable,
        ArtistTable, AlbumTable, SongTable, SongArtistTable, SongMusicBrainzTable, SongAudioDataTable,
        GenreTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable, ArtistAliasTable, ArtistMemberTable,
        AlbumArtistTable, PlaylistTable, UserSongTable, UserPlaylistTable, SongGenreTable, ArtistGenreTable,
        AlbumGenreTable, PlaylistSongTable, UserPlaylistSongTable, SyncedLyricsTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable, ScheduledTaskLogTable,
        ArtistSplitAliasTable, SyncServiceTable, SongProviderTable, AlbumProviderTable,
        CollectionTable, CollectionSongTable, CollectionAlbumTable, CollectionArtistTable, CollectionPlaylistTable,
        *allMusicBrainzTables,
    )

    private fun setupSearch(dialect: DbDialect) {
        val storageService = mockk<StorageService>(relaxed = true)
        every { storageService.albumsPath } returns null
        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { mockk<GenreService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<LibraryMergeService>(relaxed = true) }
                single { mockk<LinkResolverService>(relaxed = true) }
                single { storageService }
                single { SongService() }
                single { ArtistService() }
                single { AlbumService() }
                single { UserPlaylistService() }
            })
        }
        database = TestDatabase.connect(dialect, "collection_search_test")
        transaction(database) { SchemaUtils.create(*searchTables) }
        service = CollectionService()
    }

    private fun insertNamedSong(albumId: UUID, title: String): UUID {
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[SongTable.title] = title
            it[SongTable.albumId] = albumId
            it[fileSize] = 0
        }
        return sid
    }

    private fun insertNamedUserPlaylist(creator: UUID, name: String, songIds: List<UUID> = emptyList()): UUID {
        val pid = UUID.randomUUID()
        UserPlaylistTable.insert {
            it[id] = pid
            it[UserPlaylistTable.name] = name
            it[description] = ""
            it[UserPlaylistTable.creator] = EntityID(creator, UserTable)
        }
        songIds.forEachIndexed { i, sid ->
            UserPlaylistSongTable.insert {
                it[playlistId] = pid
                it[UserPlaylistSongTable.songId] = sid
                it[addedAt] = 1_000L + i
            }
        }
        return pid
    }

    private data class SongSearchFixture(
        val userId: UUID,
        val directSong: UUID,
        val collectionAlbum: UUID,
        val albumSong: UUID,
        val artist: UUID,
        val artistSong: UUID,
        val playlist: UUID,
        val playlistSong: UUID,
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch returns explicit and implicit songs flagged by membership`(dialect: DbDialect) = runBlocking {
        setupSearch(dialect)
        val f = transaction(database) {
            val userId = insertUser()
            val artist = insertArtist("Alpha Artist")

            val directSong = insertNamedSong(insertAlbum("Direct Album"), "Alpha One")

            val collectionAlbum = insertAlbum("Second Album")
            val albumSong = insertNamedSong(collectionAlbum, "Alpha Two")

            val artistAlbum = insertAlbum("Third Album")
            val artistSong = insertNamedSong(artistAlbum, "Alpha Three")
            linkSongArtist(artistSong, artist)

            val playlistSong = insertNamedSong(insertAlbum("Fourth Album"), "Alpha Four")
            val playlist = insertNamedUserPlaylist(userId, "PL", listOf(playlistSong))

            insertNamedSong(insertAlbum("Fifth Album"), "Alpha Five")

            SongSearchFixture(userId, directSong, collectionAlbum, albumSong, artist, artistSong, playlist, playlistSong)
        }

        val id = service.createCollection(f.userId, InsertableCollection("C"))
        transaction(database) {
            insertItem(id, CollectionItemType.SONG, f.directSong, 1)
            insertItem(id, CollectionItemType.ALBUM, f.collectionAlbum, 2)
            insertItem(id, CollectionItemType.ARTIST, f.artist, 3)
            insertItem(id, CollectionItemType.PLAYLIST, f.playlist, 4)
        }

        val results = service.rankedSearch(id, "Alpha", explicit = true, page = 0, pageSize = 50, userId = f.userId)
        val byId = results.songs.data.associateBy { it.song.id }

        assertEquals(
            setOf(f.directSong, f.albumSong, f.artistSong, f.playlistSong),
            byId.keys,
        )
        assertTrue(byId.getValue(f.directSong).explicitMember)
        assertFalse(byId.getValue(f.albumSong).explicitMember)
        assertFalse(byId.getValue(f.artistSong).explicitMember)
        assertFalse(byId.getValue(f.playlistSong).explicitMember)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch scopes artists albums and playlists to collection items`(dialect: DbDialect) = runBlocking {
        setupSearch(dialect)
        data class Fixture(val userId: UUID, val artistIn: UUID, val albumIn: UUID, val playlistIn: UUID)
        val f = transaction(database) {
            val userId = insertUser()
            val artistIn = insertArtist("Alpha Artist")
            insertArtist("Alpha Other Artist")
            val albumIn = insertAlbum("Alpha Album")
            insertAlbum("Alpha Other Album")
            val playlistIn = insertNamedUserPlaylist(userId, "Alpha Playlist")
            insertNamedUserPlaylist(userId, "Alpha Other Playlist")
            Fixture(userId, artistIn, albumIn, playlistIn)
        }

        val id = service.createCollection(f.userId, InsertableCollection("C"))
        transaction(database) {
            insertItem(id, CollectionItemType.ARTIST, f.artistIn, 1)
            insertItem(id, CollectionItemType.ALBUM, f.albumIn, 2)
            insertItem(id, CollectionItemType.PLAYLIST, f.playlistIn, 3)
        }

        val results = service.rankedSearch(id, "Alpha", explicit = true, page = 0, pageSize = 50, userId = f.userId)

        assertEquals(listOf(f.artistIn), results.artists.data.map { it.id })
        assertEquals(listOf(f.albumIn), results.albums.data.map { it.id })
        assertEquals(listOf(f.playlistIn), results.playlists.data.map { it.id })
        assertTrue(results.songs.data.isEmpty())
    }
}
