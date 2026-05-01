package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.MusicBrainzArtist
import dev.dertyp.data.MusicBrainzArtistCredit
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.db.*
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
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)
    private val libraryMergeService = mockk<LibraryMergeService>(relaxed = true)

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { storageService }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { ArtistService() }
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
                SongTable,
                SongArtistTable,
                SongMusicBrainzTable,
                ArtistSplitAliasTable,
                GenreTable,
                ArtistGenreTable,
                SongGenreTable,
                AlbumGenreTable,
                *allMusicBrainzTables
            )
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
            title = "Fetched Album"
        )

        service.setMusicBrainzId(albumId, mbId)

        val mbRelease = transaction(database) {
            MBReleaseTable.selectAll().where { MBReleaseTable.id eq mbId }.singleOrNull()
        }
        assertNotNull(mbRelease)
        assertEquals("Fetched Album", mbRelease!![MBReleaseTable.title])
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
}
