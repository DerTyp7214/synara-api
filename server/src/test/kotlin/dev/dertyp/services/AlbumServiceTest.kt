package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class AlbumServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: AlbumService
    private lateinit var rpcService: AlbumRpcService
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)
    private val libraryMergeService = mockk<LibraryMergeService>(relaxed = true)
    
    private val user = User(
        id = UUID.randomUUID(),
        username = "testuser",
        passwordHash = "hash",
        isAdmin = true
    )

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { storageService }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { ArtistService() }
                single { GenreService() }
                single { CachedMusicBrainzService(get(), get()) }
                single { libraryMergeService }
            })
        }

        database = TestDatabase.connect(dialect, "album_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                AlbumTable,
                AlbumArtistTable,
                ArtistTable,
                ArtistMemberTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                FollowedArtistTable,
                AlbumMusicBrainzTable,
                ImageTable,
                ImageMetadataTable,
                SongTable,
                SongArtistTable,
                SongMusicBrainzTable,
                ArtistSplitAliasTable,
                GenreTable,
                ArtistGenreTable,
                SongGenreTable,
                AlbumGenreTable,
                AlbumProviderTable,
                *allMusicBrainzTables
            )
        }
        
        every { storageService.albumsPath } returns null
        
        service = AlbumService()
        rpcService = AlbumRpcService(user, service)
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
    fun `byId should return album with isFollowed true for artist if artist is followed by user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user1"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Followed Artist"
            }
            FollowedArtistTable.insert {
                it[FollowedArtistTable.artistId] = artistId
                it[FollowedArtistTable.userId] = userId
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Followed Artist Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
        }

        val album = service.byId(albumId, userId)
        assertNotNull(album)
        assertEquals(1, album?.artists?.size)
        assertEquals(true, album?.artists?.firstOrNull()?.isFollowed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find albums by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            val unrelatedGroupId = UUID.randomUUID()
            ArtistTable.insert {
                it[id] = unrelatedGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            val johnLennonId = UUID.randomUUID()
            ArtistTable.insert {
                it[id] = johnLennonId
                it[name] = "John Lennon"
            }
            ArtistMemberTable.insert {
                it[artistId] = johnLennonId
                it[groupId] = unrelatedGroupId
            }
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
    fun `rankedSearch should find albums by member name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        val testAlbumId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMemberId
                it[name] = "John Lennon"
            }
            ArtistMemberTable.insert {
                it[artistId] = testMemberId
                it[groupId] = testGroupId
            }
            AlbumTable.insert {
                it[id] = testAlbumId
                it[name] = "Abbey Road"
                it[songCount] = 17
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = testAlbumId
                it[AlbumArtistTable.artistId] = testGroupId
            }
        }

        val result = service.rankedSearch(0, 10, "Lennon")
        assertTrue(result.data.any { it.name == "Abbey Road" }, "Should find the album by member name")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find albums by group name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        val testAlbumId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = testGroupId
                it[name] = "The Beatles"
                it[isGroup] = true
            }
            ArtistTable.insert {
                it[id] = testMemberId
                it[name] = "John Lennon"
            }
            ArtistMemberTable.insert {
                it[artistId] = testMemberId
                it[groupId] = testGroupId
            }
            AlbumTable.insert {
                it[id] = testAlbumId
                it[name] = "Imagine"
                it[songCount] = 10
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = testAlbumId
                it[AlbumArtistTable.artistId] = testMemberId
            }
        }

        val result = service.rankedSearch(0, 10, "Beatles")
        assertTrue(result.data.any { it.name == "Imagine" }, "Should find the album by group name")
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
    fun `versions should return other versions of the same album by Release Group`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseGroupId = UUID.randomUUID()
        val mbId1 = UUID.randomUUID()
        val mbId2 = UUID.randomUUID()
        val albumId1 = UUID.randomUUID()
        val albumId2 = UUID.randomUUID()

        transaction(database) {
            MBReleaseGroupTable.insert {
                it[id] = releaseGroupId
                it[title] = "Release Group"
            }
            MBReleaseTable.insert {
                it[id] = mbId1
                it[title] = "Release 1"
                it[MBReleaseTable.releaseGroupId] = releaseGroupId
            }
            MBReleaseTable.insert {
                it[id] = mbId2
                it[title] = "Release 2"
                it[MBReleaseTable.releaseGroupId] = releaseGroupId
            }
            AlbumTable.insert {
                it[id] = albumId1
                it[name] = "Album 1"
                it[songCount] = 10
            }
            AlbumTable.insert {
                it[id] = albumId2
                it[name] = "Album 2"
                it[songCount] = 10
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId1
                it[musicBrainzId] = mbId1
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId2
                it[musicBrainzId] = mbId2
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(mbId1, any()) } returns MusicBrainzRelease(
            id = mbId1,
            title = "Release 1",
            releaseGroup = MusicBrainzReleaseGroup(id = releaseGroupId, title = "Release Group")
        )

        val versions = service.versions(albumId1)
        assertEquals(1, versions.size)
        assertEquals(albumId2, versions[0].id)
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
    fun `rankedSearch should find albums by MusicBrainz metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbReleaseId = UUID.randomUUID()
        val mbArtistId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Library Artist"
            }
            MBArtistTable.insert {
                it[id] = mbArtistId
                it[name] = "MB Artist Name"
                it[sortName] = "MB Artist Name"
            }
            ArtistMusicBrainzTable.insert {
                it[this.artistId] = artistId
                it[musicBrainzId] = mbArtistId
            }
            MBArtistAliasTable.insert {
                it[MBArtistAliasTable.artistId] = mbArtistId
                it[name] = "MB Artist Alias"
                it[sortName] = "MB Artist Alias"
            }

            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Library Title"
                it[songCount] = 10
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            MBReleaseTable.insert {
                it[id] = mbReleaseId
                it[title] = "MusicBrainz Title"
                it[disambiguation] = "Special Version"
            }
            AlbumMusicBrainzTable.insert {
                it[this.albumId] = albumId
                it[musicBrainzId] = mbReleaseId
            }
        }

        val mbTitleResult = service.rankedSearch(0, 10, "MusicBrainz")
        assertEquals(1, mbTitleResult.data.size)
        assertEquals(albumId, mbTitleResult.data[0].id)

        val mbDisambiguationResult = service.rankedSearch(0, 10, "Special")
        assertEquals(1, mbDisambiguationResult.data.size)
        assertEquals(albumId, mbDisambiguationResult.data[0].id)

        val mbArtistNameResult = service.rankedSearch(0, 10, "MB Artist Name")
        assertEquals(1, mbArtistNameResult.data.size)
        assertEquals(albumId, mbArtistNameResult.data[0].id)

        val mbArtistAliasResult = service.rankedSearch(0, 10, "Artist Alias")
        assertEquals(1, mbArtistAliasResult.data.size)
        assertEquals(albumId, mbArtistAliasResult.data[0].id)
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
    fun `byId should return album with cover blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "album_blurhash"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album with Cover"
                it[cover] = imageId
                it[songCount] = 10
                it[releaseDate] = "2023-01-01"
            }
        }

        val album = service.byId(albumId)
        assertNotNull(album)
        assertEquals(imageId, album?.coverId)
        assertEquals("album_blurhash", album?.blurHash)
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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreate should match existing album by metadata and artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistName = "Test Artist"
        val albumName = "Matching Album"
        val releaseDate = LocalDate.of(2024, 1, 1)
        val isoDate = "2024-01-01"
        
        val artistId = transaction(database) {
            ArtistTable.insertAndGetId {
                it[ArtistTable.name] = artistName
            }.value
        }
        val albumId = transaction(database) {
            val aId = AlbumTable.insertAndGetId {
                it[AlbumTable.name] = albumName
                it[AlbumTable.songCount] = 10
                it[AlbumTable.releaseDate] = isoDate
            }.value
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = aId
                it[AlbumArtistTable.artistId] = artistId
            }
            aId
        }

        val albums = listOf(
            InsertableAlbum(albumName, listOf(artistName), songCount = 10, releaseDate = releaseDate)
        )
        val result = service.getOrBulkCreate(albums)
        
        assertEquals(1, result.size)
        assertEquals(albumId, result.values.first(), "Should return existing album ID when metadata and artists match")

        val albumsDifferentArtist = listOf(
            InsertableAlbum(albumName, listOf("Different Artist"), songCount = 10, releaseDate = releaseDate)
        )
        val result2 = service.getOrBulkCreate(albumsDifferentArtist)
        
        assertEquals(1, result2.size)
        assertNotEquals(albumId, result2.values.first(), "Should create a new album if artists don't match")
        
        val newAlbum = service.byId(result2.values.first())
        assertNotNull(newAlbum)
        assertEquals("Different Artist", newAlbum?.artists?.firstOrNull()?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteEmptyAlbums should remove albums with no songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        transaction(database) {
            AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Empty"
            }
            val nonEmptyId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = nonEmptyId
                it[name] = "Non-Empty"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song"
                it[SongTable.albumId] = nonEmptyId
                it[filePath] = "path"
            }
        }

        val deletedCount = service.deleteEmptyAlbums()
        assertEquals(1, deletedCount)
        
        val albums = service.allAlbums(0, 10).data
        assertEquals(1, albums.size)
        assertEquals("Non-Empty", albums[0].name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreate should match existing album by barcode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistName = "Test Artist"
        val barcode = "123456789012"
        
        val artistId = transaction(database) {
            ArtistTable.insertAndGetId {
                it[ArtistTable.name] = artistName
            }.value
        }
        val albumId = transaction(database) {
            val aId = AlbumTable.insertAndGetId {
                it[AlbumTable.name] = "Original Name"
                it[AlbumTable.barcode] = barcode
            }.value
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = aId
                it[AlbumArtistTable.artistId] = artistId
            }
            aId
        }

        val albums = listOf(
            InsertableAlbum("New Name", listOf(artistName), barcode = barcode)
        )
        val result = service.getOrBulkCreate(albums)
        
        assertEquals(1, result.size)
        assertEquals(albumId, result.values.first(), "Should return existing album ID when barcode matches")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byMusicBrainzId should return matching albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbId = UUID.randomUUID()
        val albumId1 = UUID.randomUUID()
        val albumId2 = UUID.randomUUID()

        transaction(database) {
            MBReleaseTable.insert {
                it[id] = mbId
                it[title] = "MB Title"
            }
            AlbumTable.insert {
                it[id] = albumId1
                it[name] = "Album 1"
            }
            AlbumTable.insert {
                it[id] = albumId2
                it[name] = "Album 2"
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId1
                it[musicBrainzId] = mbId
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId2
                it[musicBrainzId] = mbId
            }
        }

        val results = service.byMusicBrainzId(mbId)
        assertEquals(2, results.size)
        assertTrue(results.any { it.id == albumId1 })
        assertTrue(results.any { it.id == albumId2 })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byMusicBrainzId should return alternative versions if direct match is missing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val requestedMbId = UUID.randomUUID()
        val siblingMbId = UUID.randomUUID()
        val releaseGroupId = UUID.randomUUID()
        val localAlbumId = UUID.randomUUID()

        transaction(database) {
            MBReleaseGroupTable.insert {
                it[id] = releaseGroupId
                it[title] = "Release Group Title"
            }
            MBReleaseTable.insert {
                it[id] = siblingMbId
                it[title] = "Sibling Release"
                it[MBReleaseTable.releaseGroupId] = releaseGroupId
            }
            AlbumTable.insert {
                it[id] = localAlbumId
                it[name] = "Local Album"
                it[songCount] = 10
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = localAlbumId
                it[musicBrainzId] = siblingMbId
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(requestedMbId, any()) } returns MusicBrainzRelease(
            id = requestedMbId,
            title = "Requested Release",
            releaseGroup = MusicBrainzReleaseGroup(id = releaseGroupId, title = "Release Group Title")
        )

        val results = service.byMusicBrainzId(requestedMbId)
        assertEquals(1, results.size)
        assertEquals(localAlbumId, results[0].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byMusicBrainzId should return albums if mbId is a Release Group ID`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseGroupId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            MBReleaseGroupTable.insert {
                it[id] = releaseGroupId
                it[title] = "Release Group"
            }
            MBReleaseTable.insert {
                it[id] = mbId
                it[title] = "Release"
                it[MBReleaseTable.releaseGroupId] = releaseGroupId
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
                it[songCount] = 10
            }
            AlbumMusicBrainzTable.insert {
                it[this.albumId] = albumId
                it[musicBrainzId] = mbId
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(releaseGroupId, any()) } returns null

        val results = service.byMusicBrainzId(releaseGroupId)
        assertEquals(1, results.size)
        assertEquals(albumId, results[0].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byMusicBrainzIds should return matches for multiple IDs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbId1 = UUID.randomUUID()
        val mbId2 = UUID.randomUUID()
        val albumId1 = UUID.randomUUID()
        val albumId2 = UUID.randomUUID()

        transaction(database) {
            MBReleaseTable.insert {
                it[id] = mbId1
                it[title] = "Release 1"
            }
            MBReleaseTable.insert {
                it[id] = mbId2
                it[title] = "Release 2"
            }
            AlbumTable.insert {
                it[id] = albumId1
                it[name] = "Album 1"
            }
            AlbumTable.insert {
                it[id] = albumId2
                it[name] = "Album 2"
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId1
                it[musicBrainzId] = mbId1
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId2
                it[musicBrainzId] = mbId2
            }
        }

        val results = service.byMusicBrainzIds(listOf(mbId1, mbId2))
        assertEquals(2, results.size)
        assertEquals(albumId1, results[0]?.id)
        assertEquals(albumId2, results[1]?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byMusicBrainzIds should handle mix of direct and RG fallback`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val directMbId = UUID.randomUUID()
        val fallbackMbId = UUID.randomUUID()
        val siblingMbId = UUID.randomUUID()
        val releaseGroupId = UUID.randomUUID()
        val albumId1 = UUID.randomUUID()
        val albumId2 = UUID.randomUUID()

        transaction(database) {
            MBReleaseTable.insert {
                it[id] = directMbId
                it[title] = "Direct Release"
            }
            AlbumTable.insert {
                it[id] = albumId1
                it[name] = "Direct Album"
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId1
                it[musicBrainzId] = directMbId
            }

            MBReleaseGroupTable.insert {
                it[id] = releaseGroupId
                it[title] = "RG"
            }
            MBReleaseTable.insert {
                it[id] = siblingMbId
                it[title] = "Sibling"
                it[MBReleaseTable.releaseGroupId] = releaseGroupId
            }
            AlbumTable.insert {
                it[id] = albumId2
                it[name] = "Fallback Album"
                it[songCount] = 10
            }
            AlbumMusicBrainzTable.insert {
                it[albumId] = albumId2
                it[musicBrainzId] = siblingMbId
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(fallbackMbId, any()) } returns MusicBrainzRelease(
            id = fallbackMbId,
            title = "Requested",
            releaseGroup = MusicBrainzReleaseGroup(id = releaseGroupId, title = "RG")
        )

        val results = service.byMusicBrainzIds(listOf(directMbId, fallbackMbId))
        assertEquals(2, results.size)
        assertEquals(albumId1, results[0]?.id)
        assertEquals(albumId2, results[1]?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return album with genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        val genreId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[AlbumTable.id] = id
                it[name] = "Album with Genre"
                it[songCount] = 1
            }
            GenreTable.insert {
                it[GenreTable.id] = genreId
                it[name] = "pop"
            }
            AlbumGenreTable.insert {
                it[AlbumGenreTable.albumId] = id
                it[AlbumGenreTable.genreId] = genreId
            }
        }

        val album = service.byId(id)
        assertNotNull(album)
        assertEquals(1, album?.genres?.size)
        assertEquals("pop", album?.genres?.firstOrNull()?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setMusicBrainzId should fetch metadata if not in cache`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
                it[songCount] = 1
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "Fetched Album",
            barcode = "123456789012"
        )

        service.setMusicBrainzId(albumId, mbId)

        val (dbTitle, dbBarcode) = transaction(database) {
            val row = MBReleaseTable.selectAll().where { MBReleaseTable.id eq mbId }.single()
            val albumRow = AlbumTable.selectAll().where { AlbumTable.id eq albumId }.single()
            row[MBReleaseTable.title] to albumRow[AlbumTable.barcode]
        }
        assertEquals("Fetched Album", dbTitle)
        assertEquals("123456789012", dbBarcode)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setMusicBrainzId should trigger duplicate album merge`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "Album"
        )
        coEvery { libraryMergeService.mergeDuplicateAlbums() } returns 0

        service.setMusicBrainzId(albumId, mbId)

        kotlinx.coroutines.delay(500.milliseconds)

        coVerify { libraryMergeService.mergeDuplicateAlbums() }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId should resolve artist with evidence from other items`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val otherAlbumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbReleaseId = UUID.randomUUID()
        val mbArtistId = UUID.randomUUID()

        transaction(database) {
            MBArtistTable.insert {
                it[id] = mbArtistId
                it[name] = "Artist Name"
                it[sortName] = "Artist Name"
            }
            MBReleaseTable.insert {
                it[id] = mbReleaseId
                it[title] = "Other Album"
            }
            MBReleaseArtistCreditTable.insert {
                it[releaseId] = mbReleaseId
                it[MBReleaseArtistCreditTable.artistId] = mbArtistId
                it[name] = "Artist Name"
                it[position] = 0
            }

            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist Name"
            }

            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Current Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }

            AlbumTable.insert {
                it[id] = otherAlbumId
                it[name] = "Other Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = otherAlbumId
                it[AlbumArtistTable.artistId] = artistId
            }
            AlbumMusicBrainzTable.insert {
                it[AlbumMusicBrainzTable.albumId] = otherAlbumId
                it[AlbumMusicBrainzTable.musicBrainzId] = mbReleaseId
                it[lastCheck] = 0
            }
        }

        coEvery { musicBrainzService.searchAlbumMb(any(), any()) } returns MusicBrainzRelease(id = mbReleaseId, title = "Current Album")
        coEvery { musicBrainzService.fetchReleaseById(mbReleaseId, any()) } returns MusicBrainzRelease(
            id = mbReleaseId,
            title = "Current Album",
            artistCredit = listOf(
                MusicBrainzArtistCredit(
                    name = "Artist Name",
                    artist = MusicBrainzArtist(id = mbArtistId, name = "Artist Name", sortName = "Artist Name")
                )
            )
        )

        service.fetchMusicBrainzId(albumId)

        val updatedArtist = transaction(database) {
            ArtistMusicBrainzTable.selectAll().where { ArtistMusicBrainzTable.artistId eq artistId }.singleOrNull()
        }
        assertNotNull(updatedArtist, "Artist should have been assigned an MBID because of evidence from other album")
        assertEquals(mbArtistId, updatedArtist!![ArtistMusicBrainzTable.musicBrainzId]?.value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId should create new artist if no evidence exists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val existingArtistId = UUID.randomUUID()
        val mbReleaseId = UUID.randomUUID()
        val mbArtistId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = existingArtistId
                it[name] = "Same Name"
            }

            AlbumTable.insert {
                it[id] = albumId
                it[name] = "New Album"
                it[songCount] = 1
            }
        }

        coEvery { musicBrainzService.searchAlbumMb(any(), any()) } returns MusicBrainzRelease(id = mbReleaseId, title = "New Album")
        coEvery { musicBrainzService.fetchReleaseById(mbReleaseId, any()) } returns MusicBrainzRelease(
            id = mbReleaseId,
            title = "New Album",
            artistCredit = listOf(
                MusicBrainzArtistCredit(
                    name = "Same Name",
                    artist = MusicBrainzArtist(id = mbArtistId, name = "Same Name", sortName = "Same Name")
                )
            )
        )

        service.fetchMusicBrainzId(albumId)

        val artistsOnAlbum = transaction(database) {
            AlbumArtistTable.selectAll().where { AlbumArtistTable.albumId eq albumId }
                .map { it[AlbumArtistTable.artistId].value }
        }
        
        assertEquals(1, artistsOnAlbum.size)
        val resolvedArtistId = artistsOnAlbum.first()
        assertNotEquals(existingArtistId, resolvedArtistId, "Should have created a new artist instead of reusing name-match without evidence")
        
        val mbInfo = transaction(database) {
            ArtistMusicBrainzTable.selectAll().where { ArtistMusicBrainzTable.artistId eq resolvedArtistId }.singleOrNull()
        }
        assertNotNull(mbInfo)
        assertEquals(mbArtistId, mbInfo!![ArtistMusicBrainzTable.musicBrainzId]?.value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byOriginalIds should find albums via AlbumProviderTable and AlbumTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId1 = UUID.randomUUID()
        val albumId2 = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId1
                it[name] = "Album 1"
                it[originalId] = "tiddl:orig1"
            }
            AlbumArtistTable.insert {
                it[this.albumId] = albumId1
                it[this.artistId] = artistId
            }
            AlbumTable.insert {
                it[id] = albumId2
                it[name] = "Album 2"
                it[originalId] = "spotify:orig2"
            }
            AlbumArtistTable.insert {
                it[this.albumId] = albumId2
                it[this.artistId] = artistId
            }
            AlbumProviderTable.insert {
                it[AlbumProviderTable.albumId] = albumId2
                it[provider] = "tidal"
                it[externalId] = "ext2"
                it[type] = Type.ALBUM.value
                it[rawUrl] = "https://tidal.com/album/ext2"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId1
                it[filePath] = "path1"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId2
                it[filePath] = "path2"
            }
        }

        val results = service.byOriginalIds(listOf("tiddl:orig1", "tidal:ext2"))
        assertEquals(2, results.size)
        assertTrue(results.any { it.id == albumId1 })
        assertTrue(results.any { it.id == albumId2 })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byOriginalUrls should find albums via AlbumProviderTable and AlbumTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId1 = UUID.randomUUID()
        val albumId2 = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        val url1 = "https://tidal.com/album/1"
        val url2 = "https://tidal.com/album/2"
        val url2alt = "tidal:2"

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId1
                it[name] = "Tidal Album 1"
                it[originalId] = url1
            }
            AlbumArtistTable.insert {
                it[this.albumId] = albumId1
                it[this.artistId] = artistId
            }
            AlbumTable.insert {
                it[id] = albumId2
                it[name] = "Tidal Album 2"
                it[originalId] = "spotify:something"
            }
            AlbumArtistTable.insert {
                it[this.albumId] = albumId2
                it[this.artistId] = artistId
            }
            AlbumProviderTable.insert {
                it[AlbumProviderTable.albumId] = albumId2
                it[provider] = "tidal"
                it[externalId] = "2"
                it[type] = Type.ALBUM.value
                it[rawUrl] = url2
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId1
                it[filePath] = "path1"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId2
                it[filePath] = "path2"
            }
        }

        val result = service.byOriginalUrls(listOf(url1, url2, url2alt))
        
        assertEquals(3, result.size)
        assertEquals(albumId1, result[url1]?.id)
        assertEquals(albumId2, result[url2]?.id)
        assertEquals(albumId2, result[url2alt]?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `extendedMetadata should return full album information`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            AlbumProviderTable.insert {
                it[this.albumId] = albumId
                it[provider] = "spotify"
                it[externalId] = "456"
                it[rawUrl] = "https://open.spotify.com/album/456"
                it[addedAt] = 1000L
            }
        }

        val metadata = rpcService.extendedMetadata(albumId)
        assertNotNull(metadata)
        metadata!!
        assertEquals(1, metadata.providers.size)
        assertEquals("spotify", metadata.providers[0].provider)
        assertEquals("456", metadata.providers[0].externalId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `syncSongsWithMusicBrainz should match and sync by ISRC`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val isrc = "USAT20300184"
        val mbRecordingId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[SongTable.albumId] = albumId
                it[title] = "Original Title"
                it[SongTable.isrc] = isrc
                it[trackNumber] = 5
            }
            MBRecordingTable.insert {
                it[id] = mbRecordingId
                it[title] = "MB Title"
            }
        }

        val mbTrack = MusicBrainzTrack(
            id = UUID.randomUUID(),
            position = 1,
            recording = MusicBrainzRecording(
                id = mbRecordingId,
                title = "MB Title",
                isrcs = listOf(isrc),
                artistCredit = emptyList()
            )
        )

        service.syncSongsWithMusicBrainz(albumId, listOf(Triple(1, 1, mbTrack)))

        val (dbTrackNo, dbMbId) = transaction(database) {
            val songRow = SongTable.selectAll().where { SongTable.id eq songId }.single()
            val mbRow = SongMusicBrainzTable.selectAll().where { SongMusicBrainzTable.songId eq songId }.singleOrNull()
            songRow[SongTable.trackNumber] to mbRow?.get(SongMusicBrainzTable.musicBrainzId)?.value
        }

        assertEquals(1, dbTrackNo)
        assertEquals(mbRecordingId, dbMbId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId should match by Barcode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val barcode = "123456789012"
        val mbId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
                it[AlbumTable.barcode] = barcode
            }
        }

        coEvery { musicBrainzService.searchAlbumMb(match { it.barcode == barcode }, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "Matched Album",
            barcode = barcode,
            artistCredit = emptyList()
        )
        coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "Matched Album",
            barcode = barcode,
            artistCredit = emptyList()
        )

        val updated = service.fetchMusicBrainzId(albumId, user.id)
        assertNotNull(updated)
        assertEquals(mbId, updated?.musicbrainzId)

        coVerify { musicBrainzService.searchAlbumMb(match { it.barcode == barcode }, any()) }
    }
}
