package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.toCredit
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
import io.mockk.spyk
import kotlinx.coroutines.delay
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
                single { LibraryFileDeleter() }
                single { mockk<RedisSearchService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "album_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                AlbumTable,
                AlbumTitleTagTable,
                AlbumArtistTable,
                ArtistTable,
                ArtistMemberTable,
                ArtistMusicBrainzTable,
                ArtistAliasTable,
                FollowedArtistTable,
                AlbumMusicBrainzTable,
                ImageTable,
                ImageMetadataTable,
                AnimatedImageTable,
                SongTable, SongVariantTable,
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
        if (::service.isInitialized) runBlocking { service.stopService() }
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
    fun `byId should return album with isFollowed true for artist if artist is followed by user`(dialect: DbDialect) =
        runBlocking {
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
    fun `rankedSearch should find albums by title tag label`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val editionId = UUID.randomUUID()
        val plainId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = editionId
                it[name] = "The Divine Feminine"
                it[titleTags] = """[{"kind":"VERSION","label":"10th Anniversary"}]"""
                it[songCount] = 10
            }
            AlbumTable.insert {
                it[id] = plainId
                it[name] = "Swimming"
                it[songCount] = 13
            }
        }

        val byLabel = service.rankedSearch(0, 10, "anniversary")
        assertEquals(listOf(editionId), byLabel.data.map { it.id })
        assertEquals("The Divine Feminine", byLabel.data.single().name)

        val byName = service.rankedSearch(0, 10, "Divine")
        assertEquals(listOf(editionId), byName.data.map { it.id })

        val byNameAndLabel = service.rankedSearch(0, 10, "divine feminine anniversary")
        assertEquals(listOf(editionId), byNameAndLabel.data.map { it.id })

        val other = service.rankedSearch(0, 10, "Swimming")
        assertEquals(listOf(plainId), other.data.map { it.id })
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
            ArtistService.mapArtist(row).toCredit()
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

        service.rebuildVersionGroups()

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
    fun `byId should return album with animated cover fields`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val frameImageId = UUID.randomUUID()
        val animatedImageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = frameImageId
                it[path] = "frame.png"
                it[imageHash] = "framehash"
                it[origin] = "test"
                it[blurHash] = "animated_blurhash"
            }
            AnimatedImageTable.insert {
                it[id] = animatedImageId
                it[path] = "cover.mp4"
                it[contentHash] = "animhash"
                it[origin] = "test"
                it[imageId] = frameImageId
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album with Animated Cover"
                it[animatedCover] = animatedImageId
                it[songCount] = 1
            }
        }

        val album = service.byId(albumId)
        assertNotNull(album)
        assertEquals(animatedImageId, album?.animatedCoverId)
        assertEquals(frameImageId, album?.animatedCoverImageId)
        assertEquals("animated_blurhash", album?.animatedCoverBlurHash)
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
    fun `byMusicBrainzId should return alternative versions if direct match is missing`(dialect: DbDialect) =
        runBlocking {
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

        delay(500.milliseconds)

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

        coEvery { musicBrainzService.searchAlbumMb(any(), any()) } returns MusicBrainzRelease(
            id = mbReleaseId,
            title = "Current Album"
        )
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

        coEvery { musicBrainzService.searchAlbumMb(any(), any()) } returns MusicBrainzRelease(
            id = mbReleaseId,
            title = "New Album"
        )
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
        assertNotEquals(
            existingArtistId,
            resolvedArtistId,
            "Should have created a new artist instead of reusing name-match without evidence"
        )

        val mbInfo = transaction(database) {
            ArtistMusicBrainzTable.selectAll().where { ArtistMusicBrainzTable.artistId eq resolvedArtistId }
                .singleOrNull()
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
    fun `byOriginalUrls picks the exact match first and the lowest id among equals across lookup chunks`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val exactHigh = UUID.fromString("00000000-0000-0000-0000-00000000000f")
            val providerLow = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val sharedHigh = UUID.fromString("00000000-0000-0000-0000-00000000000e")
            val sharedLow = UUID.fromString("00000000-0000-0000-0000-000000000002")

            val exactUrl = "https://example.com/album/exact"
            val sharedUrl = "https://example.com/album/shared"
            val fillers = (0 until 6000).map { "https://example.com/missing/$it" }

            transaction(database) {
                fun album(albumId: UUID, albumName: String, url: String?) = AlbumTable.insert {
                    it[id] = albumId
                    it[name] = albumName
                    it[originalId] = url
                }
                album(exactHigh, "Exact", exactUrl)
                album(providerLow, "Provider", null)
                album(sharedHigh, "Shared high", sharedUrl)
                album(sharedLow, "Shared low", sharedUrl)
                AlbumProviderTable.insert {
                    it[AlbumProviderTable.albumId] = providerLow
                    it[provider] = "example"
                    it[externalId] = "exact"
                    it[type] = Type.ALBUM.value
                    it[rawUrl] = exactUrl
                }
            }

            val smallUrls = listOf(exactUrl, sharedUrl)
            val small = service.byOriginalUrls(smallUrls)
            val large = service.byOriginalUrls(fillers.take(5500) + exactUrl + fillers.drop(5500) + sharedUrl)

            assertEquals(exactHigh, small[exactUrl]?.id)
            assertEquals(sharedLow, small[sharedUrl]?.id)
            assertEquals(6002, large.size)
            assertEquals(small.mapValues { it.value?.id }, smallUrls.associateWith { large[it]?.id })
            assertTrue(fillers.all { it in large && large[it] == null })
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
        assertEquals(mbId, updated?.musicBrainzId)

        coVerify { musicBrainzService.searchAlbumMb(match { it.barcode == barcode }, any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult should store musicBrainzId in AlbumMusicBrainzTable for new albums`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val mbId = UUID.randomUUID()
            val artistName = "Test Artist"

            coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
                id = mbId,
                title = "MB Album"
            )

            val albums = listOf(InsertableAlbum("New Album", listOf(artistName), musicBrainzId = mbId))
            service.getOrBulkCreate(albums)

            val storedMbId = transaction(database) {
                val albumId = AlbumTable.select(AlbumTable.id)
                    .where { AlbumTable.name eq "New Album" }
                    .firstOrNull()?.get(AlbumTable.id)?.value ?: return@transaction null
                AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.musicBrainzId)
                    .where { AlbumMusicBrainzTable.albumId eq albumId }
                    .firstOrNull()?.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
            }
            assertEquals(mbId, storedMbId)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult should store musicBrainzId in AlbumMusicBrainzTable for existing albums without one`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val mbId = UUID.randomUUID()
        val artistName = "Test Artist"
        val originalId = "tidal:12345"

        val artistId = transaction(database) {
            ArtistTable.insertAndGetId { it[ArtistTable.name] = artistName }.value
        }
        val albumId = transaction(database) {
            val aId = AlbumTable.insertAndGetId {
                it[AlbumTable.name] = "Existing Album"
                it[AlbumTable.originalId] = originalId
            }.value
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = aId
                it[AlbumArtistTable.artistId] = artistId
            }
            aId
        }

        coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "MB Album"
        )

        val albums = listOf(
            InsertableAlbum("Existing Album", listOf(artistName), originalId = originalId, musicBrainzId = mbId)
        )
        service.getOrBulkCreate(albums)

        val storedMbId = transaction(database) {
            AlbumMusicBrainzTable.select(AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.albumId eq albumId }
                .firstOrNull()?.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value
        }
        assertEquals(mbId, storedMbId, "Existing album should have its MB ID stored")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `syncMusicBrainzForAlbums should sync songs and trigger library merge`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
        val songTitle = "Test Track"

        transaction(database) {
            MBReleaseTable.insert {
                it[id] = mbId
                it[title] = "MB Album"
            }
            MBRecordingTable.insert {
                it[id] = mbRecordingId
                it[title] = songTitle
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
                it[songCount] = 1
            }
            AlbumMusicBrainzTable.insert {
                it[AlbumMusicBrainzTable.albumId] = albumId
                it[AlbumMusicBrainzTable.musicBrainzId] = mbId
                it[lastCheck] = 0L
            }
            SongTable.insert {
                it[id] = songId
                it[SongTable.albumId] = albumId
                it[title] = songTitle
                it[trackNumber] = 5
            }
        }

        coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "MB Album",
            media = listOf(
                MusicBrainzMedia(
                    trackCount = 1,
                    tracks = listOf(
                        MusicBrainzTrack(
                            id = UUID.randomUUID(),
                            position = 1,
                            title = songTitle,
                            recording = MusicBrainzRecording(id = mbRecordingId, title = songTitle)
                        )
                    )
                )
            )
        )
        coEvery { libraryMergeService.mergeDuplicateAlbums() } returns 0

        service.syncMusicBrainzForAlbums(listOf(albumId))

        val (dbTrackNo, dbMbRecordingId) = transaction(database) {
            val songRow = SongTable.selectAll().where { SongTable.id eq songId }.single()
            val mbRow = SongMusicBrainzTable.selectAll().where { SongMusicBrainzTable.songId eq songId }.singleOrNull()
            songRow[SongTable.trackNumber] to mbRow?.get(SongMusicBrainzTable.musicBrainzId)?.value
        }
        assertEquals(1, dbTrackNo, "Track number should be updated from MB")
        assertEquals(mbRecordingId, dbMbRecordingId, "Song should be linked to MB recording")

        delay(500.milliseconds)
        coVerify { libraryMergeService.mergeDuplicateAlbums() }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `syncMusicBrainzForAlbums should skip albums with no AlbumMusicBrainzTable entry`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumId = UUID.randomUUID()

            transaction(database) {
                AlbumTable.insert {
                    it[id] = albumId
                    it[name] = "Album Without MB"
                }
            }

            service.syncMusicBrainzForAlbums(listOf(albumId))

            coVerify(exactly = 0) { musicBrainzService.fetchReleaseById(any(), any()) }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byOriginalIds finds every album when provider lookups span several chunks`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val count = 5003
        val albumIds = List(count) { UUID.randomUUID() }
        val unrelatedId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.batchInsert(albumIds.withIndex()) { (index, id) ->
                this[AlbumTable.id] = id
                this[AlbumTable.name] = "Chunked Album $index"
            }
            AlbumTable.insert {
                it[id] = unrelatedId
                it[name] = "Unrelated Album"
            }
            AlbumProviderTable.batchInsert(albumIds.withIndex()) { (index, id) ->
                this[AlbumProviderTable.albumId] = id
                this[AlbumProviderTable.provider] = "tidal"
                this[AlbumProviderTable.externalId] = "chunk$index"
                this[AlbumProviderTable.type] = Type.ALBUM.value
                this[AlbumProviderTable.rawUrl] = "https://tidal.com/album/chunk$index"
            }
            AlbumProviderTable.insert {
                it[AlbumProviderTable.albumId] = unrelatedId
                it[provider] = "tidal"
                it[externalId] = "unrelated"
                it[type] = Type.ALBUM.value
                it[rawUrl] = "https://tidal.com/album/unrelated"
            }
        }

        val results = service.byOriginalIds(List(count) { "tidal:chunk$it" })

        assertEquals(albumIds.toSet(), results.map { it.id }.toSet())
        assertEquals(count, results.size)
    }

    private fun albumTitleTagKinds(albumId: UUID): Set<TitleTagKind> = transaction(database) {
        AlbumTitleTagTable.selectAll()
            .where { AlbumTitleTagTable.albumId eq albumId }
            .map { it[AlbumTitleTagTable.kind] }
            .toSet()
    }

    private fun albumRow(albumId: UUID) = transaction(database) {
        AlbumTable.selectAll().where { AlbumTable.id eq albumId }.single()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult keeps an edition apart from the album with the same base name`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val released = LocalDate.of(2016, 9, 16)
            val original = InsertableAlbum("The Album", listOf("Edition Artist"), releaseDate = released, songCount = 10)
            val anniversary = InsertableAlbum(
                "The Album (10th Anniversary)",
                listOf("Edition Artist"),
                releaseDate = released,
                songCount = 10
            )

            val result = service.getOrBulkCreateWithResult(listOf(original, anniversary))

            val originalId = result.albumToIds[original]!!
            val anniversaryId = result.albumToIds[anniversary]!!
            assertNotEquals(originalId, anniversaryId)
            assertEquals(setOf(original, anniversary), result.newlyCreated)
            assertEquals(2L, transaction(database) { AlbumTable.selectAll().count() })

            val originalRow = albumRow(originalId)
            assertEquals("The Album", originalRow[AlbumTable.name])
            assertEquals(emptyList<TitleTag>(), originalRow.albumTitleTags())
            assertEquals(emptySet<TitleTagKind>(), albumTitleTagKinds(originalId))

            val anniversaryRow = albumRow(anniversaryId)
            assertEquals("The Album", anniversaryRow[AlbumTable.name])
            assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary")), anniversaryRow.albumTitleTags())
            assertEquals(setOf(TitleTagKind.VERSION), albumTitleTagKinds(anniversaryId))

            val stored = service.byId(anniversaryId)!!
            assertEquals("The Album", stored.name)
            assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "10th Anniversary")), stored.tags)

            val again = service.getOrBulkCreateWithResult(listOf(original, anniversary))
            assertEquals(result.albumToIds, again.albumToIds)
            assertEquals(emptySet<InsertableAlbum>(), again.newlyCreated)
            assertEquals(2L, transaction(database) { AlbumTable.selectAll().count() })
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult splits name and tags on insert and fills the kind table`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val input = InsertableAlbum(
                "Record (Deluxe Edition) (2011 Remaster)",
                listOf("Tag Artist"),
                originalId = "tidal:777"
            )
            val untouched = InsertableAlbum("Live at Wembley (Bootleg)", listOf("Tag Artist"), originalId = "tidal:778")

            val ids = service.getOrBulkCreate(listOf(input, untouched))

            val row = albumRow(ids[input]!!)
            assertEquals("Record", row[AlbumTable.name])
            assertEquals(
                listOf(
                    TitleTag(TitleTagKind.VERSION, "Deluxe Edition"),
                    TitleTag(TitleTagKind.REMASTER, "2011 Remaster"),
                ),
                row.albumTitleTags(),
            )
            assertEquals(setOf(TitleTagKind.VERSION, TitleTagKind.REMASTER), albumTitleTagKinds(ids[input]!!))

            val untouchedRow = albumRow(ids[untouched]!!)
            assertEquals("Live at Wembley (Bootleg)", untouchedRow[AlbumTable.name])
            assertEquals("[]", untouchedRow[AlbumTable.titleTags])
            assertEquals(emptySet<TitleTagKind>(), albumTitleTagKinds(ids[untouched]!!))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateAlbum splits title tags and keeps the kind table in sync`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val id = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[AlbumTable.id] = id
                it[name] = "Record"
                it[songCount] = 10
            }
        }

        val tagged = service.updateAlbum(service.byId(id)!!.copy(name = "Record (Deluxe Edition)"))

        assertEquals("Record", tagged?.name)
        assertEquals(listOf(TitleTag(TitleTagKind.VERSION, "Deluxe Edition")), tagged?.tags)
        assertEquals("Record", albumRow(id)[AlbumTable.name])
        assertEquals(setOf(TitleTagKind.VERSION), albumTitleTagKinds(id))

        val retagged = service.updateAlbum(
            tagged!!.copy(tags = listOf(TitleTag(TitleTagKind.REMASTER, "2011 Remaster")))
        )

        assertEquals("Record", retagged?.name)
        assertEquals(listOf(TitleTag(TitleTagKind.REMASTER, "2011 Remaster")), retagged?.tags)
        assertEquals(setOf(TitleTagKind.REMASTER), albumTitleTagKinds(id))

        val cleared = service.updateAlbum(retagged!!.copy(tags = emptyList()))

        assertEquals("Record", cleared?.name)
        assertEquals(emptyList<TitleTag>(), cleared?.tags)
        assertEquals("[]", albumRow(id)[AlbumTable.titleTags])
        assertEquals(emptySet<TitleTagKind>(), albumTitleTagKinds(id))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult gives a matched album without barcode the incoming one`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val released = LocalDate.of(2020, 1, 1)
            val (byName, byOriginalId) = transaction(database) {
                val artist = ArtistTable.insertAndGetId { it[ArtistTable.name] = "Barcode Artist" }.value
                val first = AlbumTable.insertAndGetId {
                    it[AlbumTable.name] = "Matched By Name"
                    it[AlbumTable.releaseDate] = "2020-01-01"
                    it[AlbumTable.songCount] = 8
                }.value
                val second = AlbumTable.insertAndGetId {
                    it[AlbumTable.name] = "Matched By Id"
                    it[AlbumTable.originalId] = "tidal:4711"
                    it[AlbumTable.barcode] = ""
                }.value
                for (album in listOf(first, second)) {
                    AlbumArtistTable.insert {
                        it[AlbumArtistTable.albumId] = album
                        it[AlbumArtistTable.artistId] = artist
                    }
                }
                first to second
            }

            val result = service.getOrBulkCreateWithResult(
                listOf(
                    InsertableAlbum(
                        "Matched By Name",
                        listOf("Barcode Artist"),
                        releaseDate = released,
                        songCount = 8,
                        barcode = "0602547933515"
                    ),
                    InsertableAlbum(
                        "Matched By Id",
                        listOf("Barcode Artist"),
                        originalId = "tidal:4711",
                        barcode = "0602547933522"
                    ),
                )
            )

            assertEquals(setOf(byName, byOriginalId), result.albumToIds.values.toSet())
            assertEquals(emptySet<InsertableAlbum>(), result.newlyCreated)
            assertEquals("0602547933515", albumRow(byName)[AlbumTable.barcode])
            assertEquals("0602547933522", albumRow(byOriginalId)[AlbumTable.barcode])
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult never overwrites an existing barcode or writes an invalid one`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val (withBarcode, withoutBarcode) = transaction(database) {
                val artist = ArtistTable.insertAndGetId { it[ArtistTable.name] = "Barcode Artist" }.value
                val first = AlbumTable.insertAndGetId {
                    it[AlbumTable.name] = "Has Barcode"
                    it[AlbumTable.originalId] = "tidal:1001"
                    it[AlbumTable.barcode] = "0602547933515"
                }.value
                val second = AlbumTable.insertAndGetId {
                    it[AlbumTable.name] = "No Barcode"
                    it[AlbumTable.originalId] = "tidal:1002"
                }.value
                for (album in listOf(first, second)) {
                    AlbumArtistTable.insert {
                        it[AlbumArtistTable.albumId] = album
                        it[AlbumArtistTable.artistId] = artist
                    }
                }
                first to second
            }

            val result = service.getOrBulkCreateWithResult(
                listOf(
                    InsertableAlbum(
                        "Has Barcode",
                        listOf("Barcode Artist"),
                        originalId = "tidal:1001",
                        barcode = "0602547933522"
                    ),
                    InsertableAlbum("No Barcode", listOf("Barcode Artist"), originalId = "tidal:1002", barcode = "BARCODE"),
                )
            )

            assertEquals(setOf(withBarcode, withoutBarcode), result.albumToIds.values.toSet())
            assertEquals("0602547933515", albumRow(withBarcode)[AlbumTable.barcode])
            assertNull(albumRow(withoutBarcode)[AlbumTable.barcode])
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setMusicBrainzId updates the barcode when the album is mapped to another release`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val albumId = UUID.randomUUID()
            val firstRelease = UUID.randomUUID()
            val secondRelease = UUID.randomUUID()
            val oversized = "9".repeat(40)

            transaction(database) {
                AlbumTable.insert {
                    it[id] = albumId
                    it[name] = "Album"
                    it[songCount] = 1
                }
            }

            coEvery { musicBrainzService.fetchReleaseById(firstRelease, any()) } returns MusicBrainzRelease(
                id = firstRelease,
                title = "Album",
                barcode = "0602547933515"
            )
            coEvery { musicBrainzService.fetchReleaseById(secondRelease, any()) } returns MusicBrainzRelease(
                id = secondRelease,
                title = "Album",
                barcode = oversized
            )

            service.setMusicBrainzId(albumId, firstRelease)
            assertEquals("0602547933515", albumRow(albumId)[AlbumTable.barcode])

            val remapped = service.setMusicBrainzId(albumId, secondRelease)
            assertEquals(secondRelease, remapped?.musicBrainzId)
            assertEquals(oversized.take(32), albumRow(albumId)[AlbumTable.barcode])
        }

    private fun insertAlbumWithBarcode(albumName: String, storedBarcode: String): UUID = transaction(database) {
        val artist = ArtistTable.selectAll().where { ArtistTable.name eq "Barcode Artist" }
            .firstOrNull()?.get(ArtistTable.id)?.value
            ?: ArtistTable.insertAndGetId { it[ArtistTable.name] = "Barcode Artist" }.value
        val album = AlbumTable.insertAndGetId {
            it[AlbumTable.name] = albumName
            it[AlbumTable.barcode] = storedBarcode
        }.value
        AlbumArtistTable.insert {
            it[AlbumArtistTable.albumId] = album
            it[AlbumArtistTable.artistId] = artist
        }
        album
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult matches barcodes regardless of zero padding`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val storedPadded = insertAlbumWithBarcode("Stored Padded", "0093624814337")
        val storedShort = insertAlbumWithBarcode("Stored Short", "602445790000")

        val shortInput = InsertableAlbum("Incoming Short", listOf("Barcode Artist"), barcode = "093624814337")
        val paddedInput = InsertableAlbum("Incoming Padded", listOf("Barcode Artist"), barcode = "00602445790000")

        val result = service.getOrBulkCreateWithResult(listOf(shortInput, paddedInput))

        assertEquals(storedPadded, result.albumToIds[shortInput])
        assertEquals(storedShort, result.albumToIds[paddedInput])
        assertEquals(emptySet<InsertableAlbum>(), result.newlyCreated)
        assertEquals(2L, transaction(database) { AlbumTable.selectAll().count() })
        assertEquals("0093624814337", albumRow(storedPadded)[AlbumTable.barcode])
        assertEquals("602445790000", albumRow(storedShort)[AlbumTable.barcode])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getOrBulkCreateWithResult does not match different barcodes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val stored = insertAlbumWithBarcode("Stored", "0093624814337")

        val input = InsertableAlbum("Incoming", listOf("Barcode Artist"), barcode = "093624814338")
        val result = service.getOrBulkCreateWithResult(listOf(input))

        assertNotEquals(stored, result.albumToIds[input])
        assertEquals(setOf(input), result.newlyCreated)
        assertEquals(2L, transaction(database) { AlbumTable.selectAll().count() })
    }

    private val deluxeTag = TitleTag(TitleTagKind.VERSION, "Deluxe Edition")
    private val remasterTag = TitleTag(TitleTagKind.REMASTER, "2011 Remaster")

    private fun insertCreditedArtist(label: String): UUID = transaction(database) {
        ArtistTable.insertAndGetId { it[name] = label }.value
    }

    private fun insertReleaseGroup(label: String): UUID = transaction(database) {
        MBReleaseGroupTable.insertAndGetId { it[title] = label }.value
    }

    private fun linkToReleaseGroup(linkedAlbum: UUID, releaseGroup: UUID) = transaction(database) {
        val releaseId = MBReleaseTable.insertAndGetId {
            it[title] = "Release"
            it[releaseGroupId] = releaseGroup
        }
        AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
            it[albumId] = linkedAlbum
            it[musicBrainzId] = releaseId
        }
    }

    private fun insertEdition(
        albumName: String,
        creditedTo: UUID? = null,
        editionTags: List<TitleTag> = emptyList(),
        tracks: Int = 10,
        released: String? = null,
        releaseGroup: UUID? = null,
        withExplicitSong: Boolean = false
    ): UUID {
        val editionId = transaction(database) {
            val newId = AlbumTable.insertAndGetId {
                it[name] = albumName
                it[titleTags] = encodeTitleTags(editionTags)
                it[songCount] = tracks
                it[releaseDate] = released
            }
            if (creditedTo != null) {
                AlbumArtistTable.insert {
                    it[albumId] = newId
                    it[artistId] = creditedTo
                }
            }
            SongTable.insert {
                it[title] = "Song"
                it[albumId] = newId
                it[filePath] = "/music/${newId.value}.flac"
                it[explicit] = withExplicitSong
            }
            newId.value
        }
        if (releaseGroup != null) linkToReleaseGroup(editionId, releaseGroup)
        return editionId
    }

    private fun versionGroupOf(memberId: UUID): UUID? = albumRow(memberId)[AlbumTable.versionGroupId]?.value

    private fun lowestId(vararg ids: UUID): UUID = ids.minBy { it.toString() }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byArtistGrouped pages over groups and folds the editions into the main album`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artist = insertCreditedArtist("Grouped Artist")
            val otherArtist = insertCreditedArtist("Other Artist")
            val releaseGroup = insertReleaseGroup("Album")

            val standard = insertEdition("Album", artist, released = "2015-03-01", releaseGroup = releaseGroup)
            val deluxe = insertEdition("Album", artist, listOf(deluxeTag), released = "2016-03-01")
            val remaster =
                insertEdition("Album", artist, listOf(remasterTag), released = "2020-03-01", releaseGroup = releaseGroup)
            val second = insertEdition("Second", artist, released = "2018-01-01")
            val third = insertEdition("Third", artist, released = "2010-01-01")
            val single = insertEdition("Album", artist, tracks = 1, released = "2015-02-01")
            insertEdition("Album", otherArtist, released = "2015-03-01")

            service.rebuildVersionGroups()

            val firstPage = service.byArtistGrouped(0, 2, artist, singles = false, explicit = true)
            assertEquals(3, firstPage.total)
            assertTrue(firstPage.hasNextPage)
            assertEquals(listOf(standard, second), firstPage.data.map { it.id })
            assertEquals(listOf(deluxe, remaster), firstPage.data[0].versions.map { it.id })
            assertTrue(firstPage.data[0].versions.all { it.versions.isEmpty() })
            assertEquals(listOf(deluxeTag), firstPage.data[0].versions[0].tags)
            assertEquals(1, firstPage.data[0].versions[0].artists.size)
            assertTrue(firstPage.data[1].versions.isEmpty())

            val secondPage = service.byArtistGrouped(1, 2, artist, singles = false, explicit = true)
            assertEquals(3, secondPage.total)
            assertFalse(secondPage.hasNextPage)
            assertEquals(listOf(third), secondPage.data.map { it.id })

            val singles = service.byArtistGrouped(0, 10, artist, singles = true, explicit = true)
            assertEquals(1, singles.total)
            assertEquals(listOf(single), singles.data.map { it.id })
            assertTrue(singles.data[0].versions.isEmpty())

            val flat = service.byArtist(0, 10, artist, singles = false)
            assertEquals(5, flat.total)
            assertTrue(flat.data.all { it.versions.isEmpty() })
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allAlbumsGrouped pages over groups in group order with the group total`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")

        val standard = insertEdition("Album", artist, released = "2015-03-01")
        val deluxe = insertEdition("Album", artist, listOf(deluxeTag), released = "2016-03-01")
        val remaster = insertEdition("Album", artist, listOf(deluxeTag, remasterTag), released = "2014-03-01")
        val second = insertEdition("Second", artist)
        val third = insertEdition("Third", artist)

        service.rebuildVersionGroups()

        val expectedOrder = listOf(lowestId(standard, deluxe, remaster) to standard, second to second, third to third)
            .sortedBy { it.first.toString() }
            .map { it.second }

        val firstPage = service.allAlbumsGrouped(0, 2, explicit = true)
        val secondPage = service.allAlbumsGrouped(1, 2, explicit = true)
        assertEquals(3, firstPage.total)
        assertEquals(3, secondPage.total)
        assertTrue(firstPage.hasNextPage)
        assertFalse(secondPage.hasNextPage)
        assertEquals(2, firstPage.data.size)
        assertEquals(1, secondPage.data.size)

        val entries = firstPage.data + secondPage.data
        assertEquals(expectedOrder, entries.map { it.id })
        assertEquals(listOf(deluxe, remaster), entries.single { it.id == standard }.versions.map { it.id })
        assertTrue(entries.filter { it.id != standard }.all { it.versions.isEmpty() })
        assertTrue(entries.flatMap { it.versions }.all { it.versions.isEmpty() })

        val everything = service.allAlbumsGrouped(0, Int.MAX_VALUE, explicit = true)
        assertEquals(3, everything.total)
        assertEquals(expectedOrder, everything.data.map { it.id })
        val returnedIds = everything.data.flatMap { entry -> listOf(entry.id) + entry.versions.map { it.id } }
        assertEquals(5, returnedIds.size)
        assertEquals(setOf(standard, deluxe, remaster, second, third), returnedIds.toSet())

        assertEquals(5, service.allAlbums(0, 10).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the main version follows the explicit preference`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")

        val clean = insertEdition("Album", artist, released = "2015-03-01")
        val explicitDeluxe =
            insertEdition("Album", artist, listOf(deluxeTag), released = "2016-03-01", withExplicitSong = true)
        val allClean = insertEdition("Quiet", artist)
        val allCleanDeluxe = insertEdition("Quiet", artist, listOf(deluxeTag))

        service.rebuildVersionGroups()

        val preferExplicit = rpcService.byArtist(0, 10, artist, singles = false, explicit = true)
        assertEquals(2, preferExplicit.total)
        assertEquals(setOf(explicitDeluxe, allClean), preferExplicit.data.map { it.id }.toSet())
        assertEquals(listOf(clean), preferExplicit.data.single { it.id == explicitDeluxe }.versions.map { it.id })
        assertEquals(listOf(allCleanDeluxe), preferExplicit.data.single { it.id == allClean }.versions.map { it.id })

        val preferClean = rpcService.byArtist(0, 10, artist, singles = false, explicit = false)
        assertEquals(setOf(clean, allClean), preferClean.data.map { it.id }.toSet())
        assertEquals(listOf(explicitDeluxe), preferClean.data.single { it.id == clean }.versions.map { it.id })

        assertEquals(
            setOf(explicitDeluxe, allClean),
            rpcService.allAlbums(0, 10, explicit = true).data.map { it.id }.toSet()
        )
        assertEquals(
            setOf(clean, allClean),
            rpcService.allAlbums(0, 10, explicit = false).data.map { it.id }.toSet()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `versions agrees with the grouped lists and is empty before grouping`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")

        val standard = insertEdition("Album", artist, released = "2015-03-01")
        val explicitDeluxe =
            insertEdition("Album", artist, listOf(deluxeTag), released = "2016-03-01", withExplicitSong = true)
        val remaster = insertEdition("Album", artist, listOf(remasterTag), released = "2020-03-01")
        val alone = insertEdition("Alone", artist)

        assertEquals(emptyList<Album>(), service.versions(standard))
        assertEquals(4, service.allAlbumsGrouped(0, 10, explicit = true).total)

        service.rebuildVersionGroups()

        val entry = service.byArtistGrouped(0, 10, artist, singles = false, explicit = true)
            .data.single { it.versions.isNotEmpty() }
        assertEquals(explicitDeluxe, entry.id)
        assertEquals(listOf(standard, remaster), entry.versions.map { it.id })

        assertEquals(entry.versions.map { it.id }, service.versions(entry.id).map { it.id })
        assertEquals(listOf(explicitDeluxe, remaster), service.versions(standard).map { it.id })
        assertEquals(listOf(explicitDeluxe, standard), rpcService.versions(remaster).map { it.id })
        assertTrue(service.versions(standard).all { it.versions.isEmpty() })
        assertEquals(emptyList<Album>(), service.versions(alone))
        assertEquals(emptyList<Album>(), service.versions(UUID.randomUUID()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rebuildVersionGroups anchors a group at its lowest id and only writes changes`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artist = insertCreditedArtist("Grouped Artist")

            val first = insertEdition("Album", artist)
            val second = insertEdition("Album", artist, listOf(deluxeTag))
            val alone = insertEdition("Alone", artist)

            assertEquals(2, service.rebuildVersionGroups())
            assertEquals(lowestId(first, second), versionGroupOf(first))
            assertEquals(lowestId(first, second), versionGroupOf(second))
            assertEquals(null, versionGroupOf(alone))

            assertEquals(0, service.rebuildVersionGroups())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `version groups rebuild after an insert`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")

        val first = insertEdition("Album", artist)
        val second = insertEdition("Album", artist, listOf(deluxeTag))
        service.rebuildVersionGroups()

        val third = insertEdition("Album", artist, listOf(remasterTag))
        assertEquals(null, versionGroupOf(third))
        assertEquals(2, service.byArtistGrouped(0, 10, artist, singles = false, explicit = true).total)

        service.rebuildVersionGroups()

        val grouped = service.byArtistGrouped(0, 10, artist, singles = false, explicit = true)
        assertEquals(1, grouped.total)
        assertEquals(first, grouped.data.single().id)
        assertEquals(setOf(second, third), grouped.data.single().versions.map { it.id }.toSet())
        assertEquals(lowestId(first, second, third), versionGroupOf(third))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `version groups rebuild after a MusicBrainz link change`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")
        val otherArtist = insertCreditedArtist("Other Artist")
        val releaseGroup = insertReleaseGroup("One")
        val otherReleaseGroup = insertReleaseGroup("Two")

        val first = insertEdition("One", artist, releaseGroup = releaseGroup)
        val second = insertEdition("One Again", otherArtist, releaseGroup = releaseGroup)
        val third = insertEdition("Two", otherArtist, releaseGroup = otherReleaseGroup)
        service.rebuildVersionGroups()

        assertEquals(listOf(second), service.versions(first).map { it.id })
        assertEquals(emptyList<Album>(), service.versions(third))

        linkToReleaseGroup(second, otherReleaseGroup)
        service.rebuildVersionGroups()

        assertEquals(emptyList<Album>(), service.versions(first))
        assertEquals(null, versionGroupOf(first))
        assertEquals(listOf(second), service.versions(third).map { it.id })
        assertEquals(lowestId(second, third), versionGroupOf(second))
        assertEquals(2, service.allAlbumsGrouped(0, 10, explicit = true).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `version groups rebuild after an edition was merged away`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")

        val first = insertEdition("Album", artist)
        val second = insertEdition("Album", artist, listOf(deluxeTag))
        val third = insertEdition("Album", artist, listOf(remasterTag))
        service.rebuildVersionGroups()

        val anchor = lowestId(first, second, third)
        val merged = listOf(first, second, third).filter { it != anchor }.maxBy { it.toString() }
        val kept = listOf(first, second, third).single { it != anchor && it != merged }
        transaction(database) {
            SongTable.update({ SongTable.albumId eq merged }) { it[albumId] = kept }
            AlbumTable.deleteWhere { AlbumTable.id eq merged }
        }

        service.rebuildVersionGroups()

        assertEquals(anchor, versionGroupOf(anchor))
        assertEquals(anchor, versionGroupOf(kept))
        assertEquals(listOf(kept), service.versions(anchor).map { it.id })
        val grouped = service.allAlbumsGrouped(0, 10, explicit = true)
        assertEquals(1, grouped.total)
        assertEquals(1, grouped.data.single().versions.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `version groups rebuild after the anchor album was deleted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artist = insertCreditedArtist("Grouped Artist")

        val first = insertEdition("Album", artist)
        val second = insertEdition("Album", artist, listOf(deluxeTag))
        val third = insertEdition("Album", artist, listOf(remasterTag))
        service.rebuildVersionGroups()

        val anchor = lowestId(first, second, third)
        val remaining = listOf(first, second, third).filter { it != anchor }
        transaction(database) {
            SongTable.deleteWhere { SongTable.albumId eq anchor }
            AlbumTable.deleteWhere { AlbumTable.id eq anchor }
        }

        remaining.forEach { assertEquals(null, versionGroupOf(it)) }
        assertEquals(emptyList<Album>(), service.versions(remaining[0]))
        assertEquals(2, service.allAlbumsGrouped(0, 10, explicit = true).total)

        service.rebuildVersionGroups()

        val newAnchor = lowestId(remaining[0], remaining[1])
        remaining.forEach { assertEquals(newAnchor, versionGroupOf(it)) }
        assertEquals(listOf(remaining[1]), service.versions(remaining[0]).map { it.id })
        assertEquals(1, service.allAlbumsGrouped(0, 10, explicit = true).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `album changes trigger a version group rebuild`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val spied = spyk(service)
        coEvery { spied.rebuildVersionGroups() } returns 0
        val mbId = UUID.randomUUID()
        coEvery { musicBrainzService.fetchReleaseById(mbId, any()) } returns MusicBrainzRelease(
            id = mbId,
            title = "Album"
        )

        val incoming = InsertableAlbum("Fresh Album", listOf("Fresh Artist"), releaseDate = LocalDate.of(2020, 1, 1))
        val created = spied.getOrBulkCreateWithResult(listOf(incoming))
        val createdId = created.albumToIds.values.single()
        assertEquals(setOf(incoming), created.newlyCreated)
        coVerify(timeout = 5000, exactly = 1) { spied.rebuildVersionGroups() }

        assertEquals(emptySet<InsertableAlbum>(), spied.getOrBulkCreateWithResult(listOf(incoming)).newlyCreated)
        coVerify(timeout = 5000, exactly = 1) { spied.rebuildVersionGroups() }

        spied.setMusicBrainzId(createdId, mbId, triggerMerge = false, triggerSync = false)
        coVerify(timeout = 5000, exactly = 2) { spied.rebuildVersionGroups() }

        spied.upsertAlbum(spied.byId(createdId)!!.copy(name = "Renamed Album"))
        coVerify(timeout = 5000, exactly = 3) { spied.rebuildVersionGroups() }

        assertEquals(1, spied.deleteEmptyAlbums())
        coVerify(timeout = 5000, exactly = 4) { spied.rebuildVersionGroups() }

        val withSong = insertEdition("Deleted Album")
        assertTrue(spied.deleteAlbums(listOf(withSong)))
        coVerify(timeout = 5000, exactly = 5) { spied.rebuildVersionGroups() }
    }
}
