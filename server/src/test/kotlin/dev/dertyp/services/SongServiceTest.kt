package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
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
import java.util.UUID

class SongServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var songService: SongService
    private lateinit var rpcService: SongRpcService
    
    private val musicBrainzService = mockk<MusicBrainzService>(relaxed = true)
    private val environment = mockk<ApplicationEnvironment>()
    private val storageService = mockk<StorageService>(relaxed = true)
    
    private val user = User(
        id = UUID.randomUUID(),
        username = "testuser",
        passwordHash = "hash",
        isAdmin = true
    )

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { environment }
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { CachedMusicBrainzService(get(), get()) }
                single { mockk<ImageService>(relaxed = true) }
                single { storageService }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { AlbumService() }
                single { ArtistService() }
                single { GenreService() }
                single { LibraryMergeService() }
            })
        }

        database = TestDatabase.connect(dialect, "song_rpc_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                SongTable,
                AlbumTable,
                ArtistTable,
                ArtistMemberTable,
                SongArtistTable,
                AlbumArtistTable,
                SongMusicBrainzTable,
                AlbumMusicBrainzTable,
                ArtistMusicBrainzTable,
                UserSongTable,
                UserCapabilityTable,
                ArtistAliasTable,
                FollowedArtistTable,
                PlaylistSongTable,
                UserPlaylistSongTable,
                ImageTable,
                ImageMetadataTable,
                ArtistSplitAliasTable,
                GenreTable,
                ArtistGenreTable,
                SongGenreTable,
                AlbumGenreTable,
                SongProviderTable,
                AlbumProviderTable,
                SongAudioDataTable,
                *allMusicBrainzTables
            )
            
            UserTable.insert {
                it[id] = user.id
                it[username] = user.username
                it[passwordHash] = user.passwordHash
                it[isAdmin] = user.isAdmin
            }
        }

        songService = SongService()
        rpcService = SongRpcService(user, songService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return song with full metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/to/song.mp3"
                it[duration] = 180000
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val song = rpcService.byId(songId)
        assertNotNull(song)
        assertEquals("Test Song", song?.title)
        assertEquals("Test Album", song?.album?.name)
        assertEquals(1, song?.artists?.size)
        assertEquals("Test Artist", song?.artists?.firstOrNull()?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return song with followed artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Followed Artist"
            }
            FollowedArtistTable.insert {
                it[FollowedArtistTable.artistId] = artistId
                it[FollowedArtistTable.userId] = user.id
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
                it[songCount] = 1
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[SongTable.albumId] = albumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val song = rpcService.byId(songId)
        assertNotNull(song)
        assertEquals(true, song?.artists?.firstOrNull()?.isFollowed)
        assertEquals(true, song?.album?.artists?.firstOrNull()?.isFollowed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLiked should update UserSongTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Likable Song"
                it[SongTable.albumId] = albumId
            }
        }

        val updated = rpcService.setLiked(songId, true, null)
        assertNotNull(updated)
        assertEquals(true, updated?.isFavourite)

        val retrieved = rpcService.byId(songId)
        assertEquals(true, retrieved?.isFavourite)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should return matching songs by title`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
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
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Searching for this"
                it[SongTable.albumId] = albumId
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Not this one"
                it[SongTable.albumId] = albumId
            }
        }

        val result = rpcService.rankedSearch(0, 10, "Searching", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("Searching for this", result.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find songs by artist and album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Unique Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Legendary Album"
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Some Track"
                it[SongTable.albumId] = albumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val artistResult = rpcService.rankedSearch(0, 10, "Unique", explicit = false, liked = false)
        assertEquals(1, artistResult.data.size)
        assertEquals("Some Track", artistResult.data[0].title)

        val albumResult = rpcService.rankedSearch(0, 10, "Legendary", explicit = false, liked = false)
        assertEquals(1, albumResult.data.size)
        assertEquals("Some Track", albumResult.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find songs by artist member name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        val testAlbumId = UUID.randomUUID()
        val testSongId = UUID.randomUUID()

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
            }
            SongTable.insert {
                it[id] = testSongId
                it[title] = "Come Together"
                it[SongTable.albumId] = testAlbumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = testSongId
                it[SongArtistTable.artistId] = testGroupId
            }
        }

        val result = rpcService.rankedSearch(0, 10, "Lennon", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("Come Together", result.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find songs by artist group name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val testGroupId = UUID.randomUUID()
        val testMemberId = UUID.randomUUID()
        val testAlbumId = UUID.randomUUID()
        val testSongId = UUID.randomUUID()

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
                it[name] = "Imagine Album"
            }
            SongTable.insert {
                it[id] = testSongId
                it[title] = "Imagine"
                it[SongTable.albumId] = testAlbumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = testSongId
                it[SongArtistTable.artistId] = testMemberId
            }
        }

        val result = rpcService.rankedSearch(0, 10, "Beatles", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("Imagine", result.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byMusicBrainzId should return matching songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            MBRecordingTable.insert {
                it[id] = mbId
                it[title] = "MB Title"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Song 1"
                it[SongTable.albumId] = albumId
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Song 2"
                it[SongTable.albumId] = albumId
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId1
                it[musicBrainzId] = mbId
            }
            SongMusicBrainzTable.insert {
                it[songId] = songId2
                it[musicBrainzId] = mbId
            }
        }

        val results = songService.byMusicBrainzId(mbId, user.id)
        assertEquals(2, results.size)
        assertTrue(results.any { it.id == songId1 })
        assertTrue(results.any { it.id == songId2 })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find songs by MusicBrainz ID`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val mbId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")

        transaction(database) {
            MBRecordingTable.insert {
                it[id] = mbId
                it[title] = "MBID Song"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "MBID Song"
                it[SongTable.albumId] = albumId
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = songId
                it[musicBrainzId] = mbId
            }
        }

        val result = rpcService.rankedSearch(0, 10, mbId.toString(), explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("MBID Song", result.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should find songs by MusicBrainz metadata`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
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
                it[name] = "Library Album"
            }
            MBReleaseTable.insert {
                it[id] = mbReleaseId
                it[title] = "MB Release Title"
            }
            AlbumMusicBrainzTable.insert {
                it[this.albumId] = albumId
                it[musicBrainzId] = mbReleaseId
            }

            SongTable.insert {
                it[id] = songId
                it[title] = "Library Song"
                it[SongTable.albumId] = albumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
            MBRecordingTable.insert {
                it[id] = mbRecordingId
                it[title] = "MB Recording Title"
            }
            SongMusicBrainzTable.insert {
                it[this.songId] = songId
                it[musicBrainzId] = mbRecordingId
            }
        }

        val mbRecordingResult = rpcService.rankedSearch(0, 10, "Recording", explicit = false, liked = false)
        assertEquals(1, mbRecordingResult.data.size)
        assertEquals(songId, mbRecordingResult.data[0].id)

        val mbReleaseResult = rpcService.rankedSearch(0, 10, "Release", explicit = false, liked = false)
        assertEquals(1, mbReleaseResult.data.size)
        assertEquals(songId, mbReleaseResult.data[0].id)

        val mbArtistNameResult = rpcService.rankedSearch(0, 10, "MB Artist Name", explicit = false, liked = false)
        assertEquals(1, mbArtistNameResult.data.size)
        assertEquals(songId, mbArtistNameResult.data[0].id)

        val mbArtistAliasResult = rpcService.rankedSearch(0, 10, "Artist Alias", explicit = false, liked = false)
        assertEquals(1, mbArtistAliasResult.data.size)
        assertEquals(songId, mbArtistAliasResult.data[0].id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should support negative keywords`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Keep This"
                it[SongTable.albumId] = albumId
                it[filePath] = "/keep"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Remove This"
                it[SongTable.albumId] = albumId
                it[filePath] = "/remove"
            }
        }

        val result = rpcService.rankedSearch(0, 10, "This -Remove", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        assertEquals("Keep This", result.data[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rankedSearch should return one song for multiple artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val artistId1 = UUID.randomUUID()
        val artistId2 = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId1
                it[name] = "Artist One"
            }
            ArtistTable.insert {
                it[id] = artistId2
                it[name] = "Artist Two"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Multiple Artists Track"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path"
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId1
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId2
            }
        }

        val result = rpcService.rankedSearch(0, 10, "Multiple Artists", explicit = false, liked = false)
        assertEquals(1, result.data.size)
        val song = result.data[0]
        assertEquals("Multiple Artists Track", song.title)
        assertEquals(2, song.artists.size)

        val result2 = rpcService.rankedSearch(0, 10, "Artist", explicit = false, liked = false)
        assertEquals(1, result2.data.size)
        val song2 = result2.data[0]
        assertEquals("Multiple Artists Track", song2.title)
        assertEquals(2, song2.artists.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should handle new songs and bitrate comparison`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = InsertableAlbum("Batch Album", listOf("Batch Artist"))
        val songs = listOf(
            InsertableSong(
                title = "Song 1",
                artists = listOf("Batch Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = "/path/1",
                bitRate = 128000
            ),
            InsertableSong(
                title = "Song 1",
                artists = listOf("Batch Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = "/path/1-high",
                bitRate = 320000
            ),
            InsertableSong(
                title = "Song 2",
                artists = listOf("Batch Artist"),
                album = album,
                duration = 200,
                explicit = false,
                path = "/path/2",
                bitRate = 256000
            )
        )

        val result = songService.createBatch(songs)
        assertEquals(2, result.size)
        
        val insertedSongs = result.map { it.value.title }.toSet()
        assertTrue(insertedSongs.contains("Song 1"))
        assertTrue(insertedSongs.contains("Song 2"))
        
        val song1 = rpcService.rankedSearch(0, 10, "Song 1", explicit = false, liked = false).data[0]
        assertEquals(320000, song1.bitRate)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should skip existing songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        
        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Existing Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Existing Album"
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            val existingSongId = SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Existing Song"
                it[SongTable.albumId] = albumId
                it[trackNumber] = 1
                it[discNumber] = 1
            }[SongTable.id].value
            SongArtistTable.insert {
                it[SongArtistTable.songId] = existingSongId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val album = InsertableAlbum("Existing Album", listOf("Existing Artist"))
        val songs = listOf(
            InsertableSong(
                title = "Existing Song",
                artists = listOf("Existing Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = "/path/exists",
                trackNumber = 1,
                discNumber = 1
            ),
            InsertableSong(
                title = "New Song",
                artists = listOf("Existing Artist"),
                album = album,
                duration = 200,
                explicit = false,
                path = "/path/new",
                trackNumber = 2,
                discNumber = 1
            )
        )

        val result = songService.createBatch(songs)
        assertEquals(1, result.size)
        assertEquals("New Song", result.values.first().title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should update dirty songs by path`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val path = "/path/to/song.flac"

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Dirty Title \uD83C\uDD74"
                it[SongTable.albumId] = albumId
                it[filePath] = path
                it[explicit] = false
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val album = InsertableAlbum("Album", listOf("Artist"))
        val songs = listOf(
            InsertableSong(
                title = "Clean Title",
                artists = listOf("Artist"),
                album = album,
                duration = 100,
                explicit = true,
                path = path
            )
        )

        val result = songService.createBatch(songs)
        assertTrue(result.isEmpty(), "Should not create new song")

        val fromDb = songService.byId(songId)
        assertEquals("Clean Title", fromDb?.title)
        assertEquals(true, fromDb?.explicit)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allSongIds should filter by tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "High Quality"
                it[SongTable.albumId] = albumId
                it[sampleRate] = 96000
                it[bitsPerSample] = 24
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Normal Quality"
                it[SongTable.albumId] = albumId
                it[sampleRate] = 44100
                it[bitsPerSample] = 16
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "With Lyrics"
                it[SongTable.albumId] = albumId
                it[lyrics] = "La la la"
            }
        }

        val highQuality = songService.allSongIds(true, tags = listOf(SongTag.Q_96)).toList()
        assertEquals(1, highQuality.size)
        
        val bitDepth24 = songService.allSongIds(true, tags = listOf(SongTag.B_24)).toList()
        assertEquals(1, bitDepth24.size)

        val withLyrics = songService.allSongIds(true, tags = listOf(SongTag.HAS_LYRICS)).toList()
        assertEquals(1, withLyrics.size)

        val notHighQuality = songService.allSongIds(true, tags = listOf(SongTag.Q_96), invertTags = true).toList()
        assertEquals(2, notHighQuality.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allSongIds should filter by custom upload tag`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val customPath = "/custom/path"
        every { storageService.customAudioPath } returns customPath
        
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Custom Upload"
                it[SongTable.albumId] = albumId
                it[filePath] = "$customPath/song.mp3"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Normal Song"
                it[SongTable.albumId] = albumId
                it[filePath] = "/other/path/song.mp3"
            }
        }

        val customSongs = songService.allSongIds(true, tags = listOf(SongTag.CUSTOM_UPLOAD)).toList()
        assertEquals(1, customSongs.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteSongs should clean up empty albums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "To Be Deleted"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Only Song"
                it[SongTable.albumId] = albumId
                it[filePath] = "/tmp/song.mp3"
            }
        }

        songService.deleteSongs(listOf(songId))
        
        val album = transaction(database) {
            AlbumTable.selectAll().where { AlbumTable.id eq albumId }.singleOrNull()
        }
        assertNull(album, "Album should be deleted when its last song is removed")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `querySongs should handle explicit and non-explicit versions correctly`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Shared Album"
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Versioned Song"
                it[SongTable.albumId] = albumId
                it[explicit] = true
                it[duration] = 100
                it[trackNumber] = 1
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "Versioned Song"
                it[SongTable.albumId] = albumId
                it[explicit] = false
                it[duration] = 100
                it[trackNumber] = 1
            }
        }

        val resultExplicit = rpcService.allSongs(0, 10, explicit = true, tags = emptyList(), invertTags = false)
        assertEquals(1, resultExplicit.data.size)
        assertTrue(resultExplicit.data[0].explicit)

        val resultNonExplicit = rpcService.allSongs(0, 10, explicit = false, tags = emptyList(), invertTags = false)
        assertEquals(1, resultNonExplicit.data.size)
        assertFalse(resultNonExplicit.data[0].explicit)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allSongs should support pagination`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            repeat(5) { i ->
                SongTable.insert {
                    it[id] = UUID.randomUUID()
                    it[title] = "Song $i"
                    it[SongTable.albumId] = albumId
                }
            }
        }

        val firstPage = rpcService.allSongs(0, 2, true, emptyList(), false)
        assertEquals(2, firstPage.data.size)
        assertTrue(firstPage.hasNextPage)
        assertEquals(5, firstPage.total)

        val secondPage = rpcService.allSongs(1, 2, true, emptyList(), false)
        assertEquals(2, secondPage.data.size)
        assertTrue(secondPage.hasNextPage)

        val lastPage = rpcService.allSongs(2, 2, true, emptyList(), false)
        assertEquals(1, lastPage.data.size)
        assertFalse(lastPage.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `songIdsByArtist should find songs via song-artist and album-artist links`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val directSongId = UUID.randomUUID()
        val albumSongId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = albumSongId
                it[title] = "Album Song"
                it[SongTable.albumId] = albumId
            }
            
            val otherAlbumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = otherAlbumId
                it[name] = "Other Album"
            }
            SongTable.insert {
                it[id] = directSongId
                it[title] = "Direct Song"
                it[SongTable.albumId] = otherAlbumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = directSongId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val result = songService.songIdsByArtist(artistId).toList()
        assertEquals(2, result.size)
        assertTrue(result.contains(directSongId))
        assertTrue(result.contains(albumSongId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allSongIds should handle all quality tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "44.1kHz"
                it[SongTable.albumId] = albumId
                it[sampleRate] = 44100
                it[bitsPerSample] = 16
            }
            SongTable.insert {
                it[id] = UUID.randomUUID()
                it[title] = "192kHz"
                it[SongTable.albumId] = albumId
                it[sampleRate] = 192000
                it[bitsPerSample] = 24
            }
            val mbSongId = UUID.randomUUID()
            val mbId = UUID.randomUUID()
            transaction(database) {
                MBRecordingTable.insert {
                    it[id] = mbId
                    it[title] = "MBID"
                }
            }
            SongTable.insert {
                it[id] = mbSongId
                it[title] = "MBID"
                it[SongTable.albumId] = albumId
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = mbSongId
                it[musicBrainzId] = mbId
            }
        }

        assertEquals(1, songService.allSongIds(true, tags = listOf(SongTag.Q_44_48)).toList().size)
        assertEquals(1, songService.allSongIds(true, tags = listOf(SongTag.Q_192)).toList().size)
        assertEquals(1, songService.allSongIds(true, tags = listOf(SongTag.B_16)).toList().size)
        assertEquals(1, songService.allSongIds(true, tags = listOf(SongTag.HAS_MUSICBRAINZ_ID)).toList().size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return song with cover blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "song_blurhash"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song with Cover"
                it[cover] = imageId
                it[filePath] = "test.flac"
                it[duration] = 1000
                it[explicit] = false
                it[trackNumber] = 1
                it[discNumber] = 1
                it[sampleRate] = 44100
                it[bitsPerSample] = 16
                it[bitRate] = 128000
                it[fileSize] = 1024
                it[albumId] = UUID.randomUUID().also { albumId ->
                    AlbumTable.insert { album ->
                        album[id] = albumId
                        album[name] = "Album"
                    }
                }
            }
        }

        val song = songService.byId(songId)
        assertNotNull(song)
        assertEquals(imageId, song?.coverId)
        assertEquals("song_blurhash", song?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return song with genres`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val genreId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[SongTable.albumId] = albumId
            }
            GenreTable.insert {
                it[id] = genreId
                it[name] = "rock"
            }
            SongGenreTable.insert {
                it[SongGenreTable.songId] = songId
                it[SongGenreTable.genreId] = genreId
            }
        }

        val song = rpcService.byId(songId)
        assertNotNull(song)
        assertEquals(1, song?.genres?.size)
        assertEquals("rock", song?.genres?.firstOrNull()?.name)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byId should return correct blurHashes for song, album and artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songImageId = UUID.randomUUID()
        val albumImageId = UUID.randomUUID()
        val artistImageId = UUID.randomUUID()

        transaction(database) {
            ImageTable.insert {
                it[id] = songImageId
                it[path] = "song.jpg"
                it[imageHash] = "song_hash"
                it[origin] = "test"
                it[blurHash] = "song_blurhash"
            }
            ImageTable.insert {
                it[id] = albumImageId
                it[path] = "album.jpg"
                it[imageHash] = "album_hash"
                it[origin] = "test"
                it[blurHash] = "album_blurhash"
            }
            ImageTable.insert {
                it[id] = artistImageId
                it[path] = "artist.jpg"
                it[imageHash] = "artist_hash"
                it[origin] = "test"
                it[blurHash] = "artist_blurhash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
                it[image] = artistImageId
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
                it[cover] = albumImageId
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = albumId
                it[AlbumArtistTable.artistId] = artistId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[cover] = songImageId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }
        }

        val song = songService.byId(songId, user.id)
        assertNotNull(song)
        assertEquals("song_blurhash", song?.blurHash)
        assertEquals("album_blurhash", song?.album?.blurHash)
        assertEquals("artist_blurhash", song?.artists?.firstOrNull()?.blurHash)
        assertEquals("artist_blurhash", song?.album?.artists?.firstOrNull()?.blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setMusicBrainzId should fetch metadata if not in cache`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/song.mp3"
            }
        }

        coEvery { musicBrainzService.fetchRecordingById(mbId, any()) } returns MusicBrainzRecording(
            id = mbId,
            title = "Fetched Title"
        )

        val updated = rpcService.setMusicBrainzId(songId, mbId)
        assertNotNull(updated)

        val mbRecording = transaction(database) {
            MBRecordingTable.selectAll().where { MBRecordingTable.id eq mbId }.singleOrNull()
        }
        assertNotNull(mbRecording)
        assertEquals("Fetched Title", mbRecording!![MBRecordingTable.title])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId should resolve artist with evidence from other items`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val otherSongId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
        val otherMbRecordingId = UUID.randomUUID()
        val mbArtistId = UUID.randomUUID()

        transaction(database) {
            MBArtistTable.insert {
                it[id] = mbArtistId
                it[name] = "Artist Name"
                it[sortName] = "Artist Name"
            }
            MBRecordingTable.insert {
                it[id] = otherMbRecordingId
                it[title] = "Other Song"
            }
            MBRecordingArtistCreditTable.insert {
                it[recordingId] = otherMbRecordingId
                it[MBRecordingArtistCreditTable.artistId] = mbArtistId
                it[name] = "Artist Name"
                it[position] = 0
            }

            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist Name"
            }

            val albumId = UUID.randomUUID()
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert {
                it[id] = songId
                it[title] = "Current Song"
                it[SongTable.albumId] = albumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = songId
                it[SongArtistTable.artistId] = artistId
            }

            SongTable.insert {
                it[id] = otherSongId
                it[title] = "Other Song"
                it[SongTable.albumId] = albumId
            }
            SongArtistTable.insert {
                it[SongArtistTable.songId] = otherSongId
                it[SongArtistTable.artistId] = artistId
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = otherSongId
                it[musicBrainzId] = otherMbRecordingId
                it[lastCheck] = 0
            }
        }

        val mbRecording = MusicBrainzRecording(
            id = mbRecordingId,
            title = "Current Song",
            artistCredit = listOf(
                MusicBrainzArtistCredit(
                    name = "Artist Name",
                    artist = MusicBrainzArtist(id = mbArtistId, name = "Artist Name", sortName = "Artist Name")
                )
            )
        )
        coEvery { musicBrainzService.searchMb(any(), any()) } returns mbRecording
        coEvery { musicBrainzService.fetchRecordingById(mbRecordingId, any()) } returns mbRecording

        songService.fetchMusicBrainzId(songId, user.id)

        val updatedArtist = transaction(database) {
            ArtistMusicBrainzTable.selectAll().where { ArtistMusicBrainzTable.artistId eq artistId }.singleOrNull()
        }
        assertNotNull(updatedArtist, "Artist should have been assigned an MBID because of evidence from other song")
        assertEquals(mbArtistId, updatedArtist!![ArtistMusicBrainzTable.musicBrainzId]?.value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId should create new artist if no evidence exists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val existingArtistId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
        val mbArtistId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert {
                it[id] = existingArtistId
                it[name] = "Same Name"
            }

            val albumId = UUID.randomUUID()
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
            SongTable.insert {
                it[id] = songId
                it[title] = "New Song"
                it[SongTable.albumId] = albumId
            }
        }

        val mbRecording = MusicBrainzRecording(
            id = mbRecordingId,
            title = "New Song",
            artistCredit = listOf(
                MusicBrainzArtistCredit(
                    name = "Same Name",
                    artist = MusicBrainzArtist(id = mbArtistId, name = "Same Name", sortName = "Same Name")
                )
            )
        )
        coEvery { musicBrainzService.searchMb(any(), any()) } returns mbRecording
        coEvery { musicBrainzService.fetchRecordingById(mbRecordingId, any()) } returns mbRecording

        songService.fetchMusicBrainzId(songId, user.id)

        val artistsOnSong = transaction(database) {
            SongArtistTable.selectAll().where { SongArtistTable.songId eq songId }
                .map { it[SongArtistTable.artistId].value }
        }
        
        assertEquals(1, artistsOnSong.size)
        val resolvedArtistId = artistsOnSong.first()
        assertNotEquals(existingArtistId, resolvedArtistId, "Should have created a new artist instead of reusing name-match without evidence")
        
        val mbInfo = transaction(database) {
            ArtistMusicBrainzTable.selectAll().where { ArtistMusicBrainzTable.artistId eq resolvedArtistId }.singleOrNull()
        }
        assertNotNull(mbInfo)
        assertEquals(mbArtistId, mbInfo!![ArtistMusicBrainzTable.musicBrainzId]?.value)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `moveSongs should handle large number of songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songCount = 80000
        val oldPath = "/old/storage"
        val newPath = "/new/storage"

        transaction(database) {
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Massive Album"
            }[AlbumTable.id]

            SongTable.batchInsert((1..songCount)) { i ->
                this[SongTable.id] = UUID.randomUUID()
                this[SongTable.title] = "Song $i"
                this[SongTable.albumId] = albumId.value
                this[SongTable.filePath] = "$oldPath/song_$i.mp3"
                this[SongTable.duration] = 100
            }
        }

        val moved = songService.moveSongs(oldPath, newPath)
        assertEquals(songCount, moved)

        transaction(database) {
            val count = SongTable.selectAll().where { SongTable.filePath like "$newPath%" }.count()
            assertEquals(songCount.toLong(), count)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byOriginalUrls should find songs via SongProviderTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        val url1 = "https://tidal.com/track/1"
        val url2 = "https://youtube.com/watch?v=2"
        val url2alt = "https://youtu.be/2"
        val url3 = "https://spotify.com/track/3"

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId1
                it[title] = "Tidal Song"
                it[SongTable.albumId] = albumId
                it[originalUrl] = url1
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Youtube Song"
                it[SongTable.albumId] = albumId
                it[originalUrl] = ""
            }
            SongProviderTable.insert {
                it[SongProviderTable.songId] = songId2
                it[provider] = "youtube"
                it[externalId] = "2"
                it[type] = Type.SONG.value
                it[rawUrl] = url2
            }
        }

        val result = rpcService.byOriginalUrls(listOf(url1, url2, url2alt, url3))
        
        assertEquals(4, result.size)
        assertEquals(songId1, result[url1]?.id)
        assertEquals(songId2, result[url2]?.id)
        assertEquals(songId2, result[url2alt]?.id)
        assertNull(result[url3])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should populate SongProviderTable`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = InsertableAlbum("Provider Album", listOf("Provider Artist"))
        val song = InsertableSong(
            title = "Provider Song",
            artists = listOf("Provider Artist"),
            album = album,
            duration = 100,
            explicit = false,
            path = "/path/provider",
            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

        val result = songService.createBatch(listOf(song))
        val songId = result.values.first().id

        val providerInfo = transaction(database) {
            SongProviderTable.selectAll().where { SongProviderTable.songId eq songId }.singleOrNull()
        }

        assertNotNull(providerInfo)
        providerInfo?.let {
            assertEquals("youtube", it[SongProviderTable.provider])
            assertEquals("dQw4w9WgXcQ", it[SongProviderTable.externalId])
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", it[SongProviderTable.rawUrl])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `extendedMetadata should return full song information`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[this.albumId] = albumId
                it[inserted] = 1000L
            }
            SongProviderTable.insert {
                it[this.songId] = songId
                it[provider] = "spotify"
                it[externalId] = "123"
                it[type] = Type.SONG.value
                it[rawUrl] = "https://open.spotify.com/track/123"
                it[addedAt] = 2000L
            }
            SongAudioDataTable.insert {
                it[this.songId] = songId
                it[bpm] = 120.5
                it[key] = "C"
                it[scale] = "Major"
            }
        }

        val metadata = rpcService.extendedMetadata(songId)
        assertNotNull(metadata)
        metadata!!
        assertEquals(1, metadata.providers.size)
        assertEquals("spotify", metadata.providers[0].provider)
        assertEquals("123", metadata.providers[0].externalId)
        assertEquals(120.5, metadata.audioData?.bpm)
        assertEquals("C", metadata.audioData?.key)
        assertEquals(AudioScale.Major, metadata.audioData?.scale)
        assertEquals(1000L, metadata.insertedAt)
    }
}
