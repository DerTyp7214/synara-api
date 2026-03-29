package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class AlbumServiceTest {
    private lateinit var database: Database
    private lateinit var service: AlbumService
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "album_test")
        transaction(database) {
            SchemaUtils.create(
                AlbumTable,
                AlbumArtistTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                AlbumMusicBrainzTable,
                ImageTable,
                SongTable,
                SongArtistTable,
                SongMusicBrainzTable
            )
        }
        
        startKoin {
            modules(module {
                single { musicBrainzService }
                single { storageService }
            })
        }
        
        every { storageService.albumsPath } returns null
        
        service = AlbumService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return album if it exists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[AlbumTable.id] = id
                it[name] = "Test Album"
                it[songCount] = 10
            }
        }

        val album = service.byId(id)
        assertNotNull(album)
        assertEquals(id, album?.id)
        assertEquals("Test Album", album?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find albums by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Master of Puppets"
                it[songCount] = 8
            }
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Rust in Peace"
                it[songCount] = 9
            }
        }

        val result = service.rankedSearch(0, 10, "Master")
        assertEquals(1, result.data.size)
        assertEquals("Master of Puppets", result.data[0].name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateAlbum should update album metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[AlbumTable.id] = id
                it[name] = "Original Name"
                it[songCount] = 10
            }
        }

        val album = service.byId(id)!!
        val updatedAlbum = album.copy(name = "Updated Name", songCount = 12)
        
        val result = service.updateAlbum(updatedAlbum)
        assertNotNull(result)
        assertEquals("Updated Name", result?.name)
        assertEquals(12, result?.songCount)
        
        val fromDb = service.byId(id)
        assertEquals("Updated Name", fromDb?.name)
        assertEquals(12, fromDb?.songCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateAlbum should update artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId1 = UUID.randomUUID()
        val artistId2 = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId1
                it[name] = "Artist 1"
            }
            ArtistTable.insert {
                it[id] = artistId2
                it[name] = "Artist 2"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId1
            }
        }

        val album = service.byId(albumId)!!
        assertEquals(1, album.artists.size)
        assertEquals("Artist 1", album.artists[0].name)

        val artist2 = transaction(database) {
            val row = ArtistTable.selectAll().where { ArtistTable.id eq artistId2 }.single()
            ArtistService.mapArtist(row)
        }
        val updatedAlbum = album.copy(artists = listOf(artist2))
        
        service.updateAlbum(updatedAlbum)
        
        val fromDb = service.byId(albumId)
        assertEquals(1, fromDb?.artists?.size)
        assertEquals("Artist 2", fromDb?.artists?.get(0)?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byIds should return multiple albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ids = List(3) { UUID.randomUUID() }
        transaction(database) {
            ids.forEachIndexed { index, id ->
                AlbumTable.insert {
                    it[AlbumTable.id] = id
                    it[name] = "Album $index"
                }
            }
        }

        val albums = service.byIds(ids)
        assertEquals(3, albums.size)
        assertEquals(ids.toSet(), albums.map { it.id }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `versions should return other versions of the same album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val coverId = UUID.randomUUID()
        val album1 = UUID.randomUUID()
        val album2 = UUID.randomUUID()
        
        transaction(database) {
            ImageTable.insert {
                it[id] = coverId
                it[path] = "test"
                it[imageHash] = "hash"
                it[origin] = "test"
            }
            AlbumTable.insert {
                it[id] = album1
                it[name] = "Version 1"
                it[cover] = coverId
                it[songCount] = 10
            }
            AlbumTable.insert {
                it[id] = album2
                it[name] = "Version 2"
                it[cover] = coverId
                it[songCount] = 10
            }
        }

        val versions = service.versions(album1)
        assertEquals(1, versions.size)
        assertEquals(album2, versions[0].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byName should find albums by exact name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Unique Name"
            }
        }

        val result = service.byName(0, 10, "Unique Name")
        assertEquals(1, result.data.size)
        assertEquals("Unique Name", result.data[0].name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allAlbums should return all albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            repeat(3) {
                AlbumTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = "Album $it"
                }
            }
        }

        val result = service.allAlbums(0, 10)
        assertEquals(3, result.data.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteAlbums should remove albums and their songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "To Delete"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/to/song.mp3"
            }
        }

        val deleted = service.deleteAlbums(listOf(albumId))
        assertTrue(deleted)
        assertEquals(null, service.byId(albumId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byArtist should find albums by artist id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Artist Album"
                it[songCount] = 10
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
        }

        val result = service.byArtist(0, 10, artistId, singles = false)
        assertEquals(1, result.data.size)
        assertEquals("Artist Album", result.data[0].name)
    }
}
