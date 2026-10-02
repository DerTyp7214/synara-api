package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.DbDialect
import dev.dertyp.StreamInfo
import dev.dertyp.TestDatabase
import dev.dertyp.audio.LosslessFormat
import dev.dertyp.audio.Transcoder
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.ClientInfo
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.core.date
import dev.dertyp.core.date.getDateFromISO
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.import.Type
import dev.dertyp.testing.FakeCredentialProvider
import dev.dertyp.services.metadata.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
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
    private val transcoder = mockk<Transcoder>()
    private val storageService = mockk<StorageService>(relaxed = true)
    private val fingerprintService = mockk<AcoustIdFingerprintService>()
    private var acoustIdQueue: HttpClientQueueService? = null
    private val acoustIdRequests = mutableListOf<Url>()
    
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
                single { transcoder }
                single { musicBrainzService }
                single { MusicBrainzCacheService() }
                single { CachedMusicBrainzService(get(), get()) }
                single { AcoustIdService(fingerprintService) }
                single<CredentialProvider> { FakeCredentialProvider(ResolvedCredential.ApiKey(CredentialNames.ACOUSTID_API, "testKey")) }
                single { mockk<ImageService>(relaxed = true) }
                single { storageService }
                single { mockk<MetadataFetchingService>(relaxed = true) }
                single { AlbumService() }
                single { ArtistService() }
                single { GenreService() }
                single { LibraryMergeService() }
                single { LibraryFileDeleter() }
                single { mockk<RedisSearchService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "song_rpc_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                SongTable, SongVariantTable,
                AlbumTable,
                ArtistTable,
                ArtistMemberTable,
                SongArtistTable,
                AlbumArtistTable,
                SongMusicBrainzTable,
                SongAcoustIdTable,
                SongTitleTagTable,
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
                AnimatedImageTable,
                ArtistSplitAliasTable,
                GenreTable,
                ArtistGenreTable,
                SongGenreTable,
                AlbumGenreTable,
                SongProviderTable,
                AlbumProviderTable,
                SongAudioDataTable,
                TimecodeTagTable,
                *allMusicBrainzTables
            )
            
            UserTable.insert {
                it[id] = user.id
                it[username] = user.username
                it[passwordHash] = user.passwordHash
                it[isAdmin] = user.isAdmin
            }
        }

        coEvery { fingerprintService.fingerprint(any()) } returns null

        songService = SongService()
        rpcService = SongRpcService(user, songService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        acoustIdQueue?.let {
            runBlocking { it.stopService() }
            unmockkObject(ApiClient)
        }
        acoustIdQueue = null
        acoustIdRequests.clear()
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

    private fun insertSongWithPath(path: String): UUID {
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
                it[songCount] = 1
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[SongTable.albumId] = albumId
                it[filePath] = path
                it[format] = songService.formatOf(path)
            }
        }
        return songId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolveRawStream serves wav raw to new clients and flac to legacy clients`(dialect: DbDialect, @TempDir tempDir: Path) = runBlocking {
        setup(dialect)
        val wav = tempDir.resolve("song.wav").toFile().apply { writeText("wav bytes") }
        val flacFallback = tempDir.resolve("song.flac").toFile().apply { writeText("flac bytes") }
        val songId = insertSongWithPath(wav.absolutePath)
        val song = songService.byId(songId)!!

        coEvery { transcoder.losslessFlacFallback(any(), wav) } returns
            StreamInfo(flacFallback, LosslessFormat.FLAC.contentType, flacFallback.length(), flacFallback.name)

        val modern = songService.resolveRawStream(song, ClientInfo(ApiVersion.CURRENT))!!
        assertEquals(wav, modern.file)
        assertEquals(LosslessFormat.WAV.contentType, modern.contentType)

        val legacy = songService.resolveRawStream(song, ClientInfo.LEGACY)!!
        assertEquals(flacFallback, legacy.file)
        assertEquals(LosslessFormat.FLAC.contentType, legacy.contentType)

        assertEquals(flacFallback.length(), songService.getStreamSize(songId, ClientInfo.LEGACY))
        assertEquals(wav.length(), songService.getStreamSize(songId, ClientInfo(ApiVersion.CURRENT)))

        val legacyRpc = SongRpcService(user, songService, ClientInfo.LEGACY)
        assertEquals(flacFallback, legacyRpc.getFile("streamSong", listOf(songId))?.file)
        assertEquals(flacFallback, legacyRpc.getFile("downloadSong", listOf(songId, 0))?.file)
        val modernRpc = SongRpcService(user, songService, ClientInfo(ApiVersion.CURRENT))
        assertEquals(wav, modernRpc.getFile("streamSong", listOf(songId))?.file)
        assertEquals("wav bytes", modernRpc.streamSong(songId, 0, 4096)!!.toList().reduce { a, b -> a + b }.decodeToString())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolveRawStream never falls back for flac files`(dialect: DbDialect, @TempDir tempDir: Path) = runBlocking {
        setup(dialect)
        val flac = tempDir.resolve("song.flac").toFile().apply { writeText("flac bytes") }
        val songId = insertSongWithPath(flac.absolutePath)
        val song = songService.byId(songId)!!

        val legacy = songService.resolveRawStream(song, ClientInfo.LEGACY)!!
        assertEquals(flac, legacy.file)
        assertEquals(LosslessFormat.FLAC.contentType, legacy.contentType)
        coVerify(exactly = 0) { transcoder.losslessFlacFallback(any(), any()) }

        assertEquals("flac", transaction(database) { SongTable.select(SongTable.format).where { SongTable.id eq songId }.single()[SongTable.format] })
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
    fun `setLikeLevel from NONE to SUPER sets favourite, level SUPER and superLikedAt`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/none-to-super.mp3")

        val updated = rpcService.setLikeLevel(songId, LikeLevel.SUPER)

        assertNotNull(updated)
        assertEquals(true, updated?.isFavourite)
        assertEquals(LikeLevel.SUPER, updated?.likeLevel)
        assertNotNull(updated?.superLikedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLikeLevel from SUPER to SUPER keeps the original superLikedAt`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/super-to-super.mp3")

        val first = rpcService.setLikeLevel(songId, LikeLevel.SUPER)
        assertNotNull(first?.superLikedAt)

        val second = rpcService.setLikeLevel(songId, LikeLevel.SUPER)

        assertEquals(LikeLevel.SUPER, second?.likeLevel)
        assertEquals(first?.superLikedAt, second?.superLikedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLikeLevel from SUPER to LIKE clears superLikedAt, stays liked and leaves updatedAt unchanged`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/super-to-like.mp3")
        rpcService.setLikeLevel(songId, LikeLevel.SUPER)
        val updatedAtAfterSuper = transaction(database) {
            UserSongTable.selectAll().where { UserSongTable.songId eq songId }.single()[UserSongTable.updatedAt]
        }

        val updated = rpcService.setLikeLevel(songId, LikeLevel.LIKE)

        assertEquals(LikeLevel.LIKE, updated?.likeLevel)
        assertEquals(true, updated?.isFavourite)
        assertNull(updated?.superLikedAt)
        val updatedAtAfterLike = transaction(database) {
            UserSongTable.selectAll().where { UserSongTable.songId eq songId }.single()[UserSongTable.updatedAt]
        }
        assertEquals(updatedAtAfterSuper, updatedAtAfterLike)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLikeLevel from LIKE to SUPER does not change updatedAt`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/like-to-super.mp3")
        rpcService.setLikeLevel(songId, LikeLevel.LIKE)
        val updatedAtAfterLike = transaction(database) {
            UserSongTable.selectAll().where { UserSongTable.songId eq songId }.single()[UserSongTable.updatedAt]
        }

        val updated = rpcService.setLikeLevel(songId, LikeLevel.SUPER)

        assertEquals(LikeLevel.SUPER, updated?.likeLevel)
        assertNotNull(updated?.superLikedAt)
        val updatedAtAfterSuper = transaction(database) {
            UserSongTable.selectAll().where { UserSongTable.songId eq songId }.single()[UserSongTable.updatedAt]
        }
        assertEquals(updatedAtAfterLike, updatedAtAfterSuper)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLikeLevel from SUPER to NONE clears favourite and superLikedAt`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/super-to-none.mp3")
        rpcService.setLikeLevel(songId, LikeLevel.SUPER)

        val updated = rpcService.setLikeLevel(songId, LikeLevel.NONE)

        assertEquals(LikeLevel.NONE, updated?.likeLevel)
        assertEquals(false, updated?.isFavourite)
        assertNull(updated?.superLikedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setLiked keeps SUPER level when liking an already super liked song and clears superLikedAt when unliking`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/setliked-super.mp3")
        rpcService.setLikeLevel(songId, LikeLevel.SUPER)

        val stillSuper = rpcService.setLiked(songId, true, null)
        assertEquals(LikeLevel.SUPER, stillSuper?.likeLevel)
        assertNotNull(stillSuper?.superLikedAt)

        val unliked = rpcService.setLiked(songId, false, null)
        assertEquals(LikeLevel.NONE, unliked?.likeLevel)
        assertEquals(false, unliked?.isFavourite)
        assertNull(unliked?.superLikedAt)
    }

    private fun insertDistinctSong(title: String): UUID {
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = title
                it[songCount] = 1
            }
            SongTable.insert {
                it[id] = songId
                it[SongTable.title] = title
                it[SongTable.albumId] = albumId
                it[filePath] = "/$title.mp3"
            }
        }
        return songId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `superLikedSongs returns only the user's super liked songs, newest first, and never another user's`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val otherUserId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = otherUserId
                it[username] = "otheruser"
                it[passwordHash] = "hash"
            }
        }

        val liked = insertDistinctSong("Liked Song")
        val superOld = insertDistinctSong("Super Old")
        val superNew = insertDistinctSong("Super New")
        val otherUsersSuper = insertDistinctSong("Others Super")

        songService.setLikeLevelReturning(liked, user.id, LikeLevel.LIKE)
        songService.setLikeLevelReturning(superOld, user.id, LikeLevel.SUPER)
        songService.setLikeLevelReturning(superNew, user.id, LikeLevel.SUPER)
        songService.setLikeLevelReturning(otherUsersSuper, otherUserId, LikeLevel.SUPER)

        transaction(database) {
            UserSongTable.update({ UserSongTable.songId eq superOld }) { it[UserSongTable.superLikedAt] = 1_000L }
            UserSongTable.update({ UserSongTable.songId eq superNew }) { it[UserSongTable.superLikedAt] = 2_000L }
        }

        val result = songService.superLikedSongs(0, 50, explicit = true, userId = user.id)
        assertEquals(listOf(superNew, superOld), result.data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `superLikedSongIds returns ids in the same order and filter as superLikedSongs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val explicitSuper = insertSongWithPath("/ids-explicit-super.mp3")
        transaction(database) {
            SongTable.update({ SongTable.id eq explicitSuper }) { it[explicit] = true }
        }
        val superOld = insertSongWithPath("/ids-super-old.mp3")
        val superNew = insertSongWithPath("/ids-super-new.mp3")
        val liked = insertSongWithPath("/ids-liked.mp3")

        songService.setLikeLevelReturning(explicitSuper, user.id, LikeLevel.SUPER)
        songService.setLikeLevelReturning(superOld, user.id, LikeLevel.SUPER)
        songService.setLikeLevelReturning(superNew, user.id, LikeLevel.SUPER)
        songService.setLikeLevelReturning(liked, user.id, LikeLevel.LIKE)

        transaction(database) {
            UserSongTable.update({ UserSongTable.songId eq superOld }) { it[UserSongTable.superLikedAt] = 1_000L }
            UserSongTable.update({ UserSongTable.songId eq explicitSuper }) { it[UserSongTable.superLikedAt] = 2_000L }
            UserSongTable.update({ UserSongTable.songId eq superNew }) { it[UserSongTable.superLikedAt] = 3_000L }
        }

        assertEquals(listOf(superNew, explicitSuper, superOld), songService.superLikedSongIds(true, user.id).toList())
        assertEquals(listOf(superNew, superOld), songService.superLikedSongIds(false, user.id).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `likeLevel is NONE without a userSong row and LIKE for a plain like`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val untouched = insertSongWithPath("/untouched.mp3")
        val liked = insertSongWithPath("/plain-like.mp3")
        rpcService.setLiked(liked, true, null)

        val untouchedSong = rpcService.byId(untouched)
        val likedSong = rpcService.byId(liked)

        assertEquals(LikeLevel.NONE, untouchedSong?.likeLevel)
        assertNull(untouchedSong?.superLikedAt)
        assertEquals(LikeLevel.LIKE, likedSong?.likeLevel)
        assertNull(likedSong?.superLikedAt)
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
                audio = AudioInfo("flac", 44100, 16, 128000, 0, 2)
            ),
            InsertableSong(
                title = "Song 1",
                artists = listOf("Batch Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = "/path/1-high",
                audio = AudioInfo("flac", 44100, 16, 320000, 0, 2)
            ),
            InsertableSong(
                title = "Song 2",
                artists = listOf("Batch Artist"),
                album = album,
                duration = 200,
                explicit = false,
                path = "/path/2",
                audio = AudioInfo("flac", 44100, 16, 256000, 0, 2)
            )
        )

        val result = songService.createBatch(songs)
        assertEquals(2, result.size)
        
        val insertedSongs = result.map { it.value.title }.toSet()
        assertTrue(insertedSongs.contains("Song 1"))
        assertTrue(insertedSongs.contains("Song 2"))
        
        val song1 = rpcService.rankedSearch(0, 10, "Song 1", explicit = false, liked = false).data[0]
        assertEquals(320000L, song1.audio?.bitRate)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch orders artists by the musicbrainz credit of the imported recording and release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mainId = UUID.randomUUID()
        val guestId = UUID.randomUUID()
        val mainMbId = UUID.randomUUID()
        val guestMbId = UUID.randomUUID()
        val recordingId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = mainId; it[name] = "Main" }
            ArtistTable.insert { it[id] = guestId; it[name] = "Guest" }
            ArtistTable.insert { it[id] = UUID.fromString("ffffffff-0000-0000-0000-000000000001"); it[name] = "Late" }
            ArtistTable.insert { it[id] = UUID.fromString("00000000-0000-0000-0000-000000000001"); it[name] = "Early" }
            MBArtistTable.insert { it[id] = mainMbId; it[name] = "Main"; it[sortName] = "Main" }
            MBArtistTable.insert { it[id] = guestMbId; it[name] = "Guest"; it[sortName] = "Guest" }
            ArtistMusicBrainzTable.insert { it[artistId] = mainId; it[musicBrainzId] = mainMbId }
            ArtistMusicBrainzTable.insert { it[artistId] = guestId; it[musicBrainzId] = guestMbId }
        }

        val credits = listOf(
            MusicBrainzArtistCredit(name = "Main", joinphrase = " feat. ", artist = MusicBrainzArtist(id = mainMbId, name = "Main", sortName = "Main")),
            MusicBrainzArtistCredit(name = "Guest", joinphrase = "", artist = MusicBrainzArtist(id = guestMbId, name = "Guest", sortName = "Guest")),
        )
        coEvery { musicBrainzService.fetchRecordingById(recordingId, any()) } returns
            MusicBrainzRecording(id = recordingId, title = "Duet", artistCredit = credits)
        coEvery { musicBrainzService.fetchReleaseById(releaseId, any()) } returns
            MusicBrainzRelease(id = releaseId, title = "Duets", artistCredit = credits)

        val tagArtists = listOf("Extra", "Guest", "Late", "Main", "Early")
        val album = InsertableAlbum("Duets", tagArtists, musicBrainzId = releaseId)
        val result = songService.createBatch(
            listOf(
                InsertableSong(
                    title = "Duet",
                    artists = tagArtists,
                    album = album,
                    duration = 100,
                    explicit = false,
                    path = "/path/duet",
                    musicBrainzId = recordingId,
                )
            )
        )

        val song = songService.byId(result.keys.single())!!
        assertEquals(listOf("Main", "Guest", "Extra", "Late", "Early"), song.artists.map { it.name })
        assertEquals(listOf(" feat. ", "", null, null, null), song.artists.map { it.joinPhrase })
        assertEquals(listOf("Main", "Guest", "Extra", "Late", "Early"), song.album!!.artists.map { it.name })
        assertEquals(listOf(" feat. ", "", null, null, null), song.album!!.artists.map { it.joinPhrase })
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
    fun `byId decodes title tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Tagged Album"
                it[songCount] = 1
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[titleTags] = """[{"kind":"REMIX","label":"Skrillex Remix"}]"""
                it[SongTable.albumId] = albumId
                it[filePath] = "/path/to/tagged.mp3"
            }
        }

        val song = songService.byId(songId)
        assertNotNull(song)
        assertEquals("Song", song?.title)
        assertEquals(listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix")), song?.tags)

        val userSong = songService.byId(songId, user.id)
        assertEquals(listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix")), userSong?.tags)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch splits title tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val path = "/path/to/remix.flac"
        val album = InsertableAlbum("Tag Album", listOf("Tag Artist"))
        val songs = listOf(
            InsertableSong(
                title = "Song (Skrillex Remix) (feat. X)",
                artists = listOf("Tag Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = path
            )
        )

        val created = songService.createBatch(songs)
        assertEquals(1, created.size)
        val createdSong = created.values.first()
        assertEquals("Song", createdSong.title)
        assertEquals(
            listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix"), TitleTag(TitleTagKind.FEAT, "feat. X")),
            createdSong.tags
        )

        val row = transaction(database) {
            SongTable.select(SongTable.title, SongTable.titleTags)
                .where { SongTable.filePath eq path }
                .single()
        }
        assertEquals("Song", row[SongTable.title])
        assertEquals(
            listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix"), TitleTag(TitleTagKind.FEAT, "feat. X")),
            decodeTitleTags(row[SongTable.titleTags])
        )

        val again = songService.createBatch(songs)
        assertTrue(again.isEmpty(), "Should not create a second song")

        val rowsAfter = transaction(database) {
            SongTable.select(SongTable.title, SongTable.titleTags).toList()
        }
        assertEquals(1, rowsAfter.size)
        assertEquals("Song", rowsAfter.single()[SongTable.title])
        assertEquals(
            listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix"), TitleTag(TitleTagKind.FEAT, "feat. X")),
            decodeTitleTags(rowsAfter.single()[SongTable.titleTags])
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch marks songs dirty when tags change`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val path = "/path/to/live.flac"

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
                it[title] = "Song"
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
        val result = songService.createBatch(
            listOf(
                InsertableSong(
                    title = "Song (Live)",
                    artists = listOf("Artist"),
                    album = album,
                    duration = 100,
                    explicit = false,
                    path = path
                )
            )
        )
        assertTrue(result.isEmpty(), "Should not create new song")

        val fromDb = songService.byId(songId)
        assertEquals("Song", fromDb?.title)
        assertEquals(listOf(TitleTag(TitleTagKind.LIVE, "Live")), fromDb?.tags)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch keeps remix and original apart`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = InsertableAlbum("Split Album", listOf("Split Artist"))
        val songs = listOf(
            InsertableSong(
                title = "Song",
                artists = listOf("Split Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = "/path/original.flac",
                trackNumber = 1,
                discNumber = 1
            ),
            InsertableSong(
                title = "Song (Remix)",
                artists = listOf("Split Artist"),
                album = album,
                duration = 100,
                explicit = false,
                path = "/path/remix.flac",
                trackNumber = 1,
                discNumber = 1
            )
        )

        val created = songService.createBatch(songs)
        assertEquals(2, created.size)

        val rows = transaction(database) {
            SongTable.select(SongTable.filePath, SongTable.title, SongTable.titleTags).toList()
        }
        assertEquals(2, rows.size)
        assertEquals(setOf("Song"), rows.map { it[SongTable.title] }.toSet())

        val tagsByPath = rows.associate { it[SongTable.filePath] to decodeTitleTags(it[SongTable.titleTags]) }
        assertEquals(emptyList<TitleTag>(), tagsByPath["/path/original.flac"])
        assertEquals(listOf(TitleTag(TitleTagKind.REMIX, "Remix")), tagsByPath["/path/remix.flac"])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateSong persists tags and splits the title`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/x/song.mp3")
        val song = songService.byId(songId)!!

        val updated = songService.updateSong(
            song.copy(
                title = "Song (Radio Edit)",
                tags = listOf(TitleTag(TitleTagKind.FEAT, "feat. X"))
            ),
            user.id
        )

        assertNotNull(updated)
        assertEquals("Song", updated?.title)
        assertEquals(
            listOf(TitleTag(TitleTagKind.FEAT, "feat. X"), TitleTag(TitleTagKind.EDIT, "Radio Edit")),
            updated?.tags
        )

        val row = transaction(database) {
            SongTable.select(SongTable.title, SongTable.titleTags)
                .where { SongTable.id eq songId }
                .single()
        }
        assertEquals("Song", row[SongTable.title])
        assertEquals(
            listOf(TitleTag(TitleTagKind.FEAT, "feat. X"), TitleTag(TitleTagKind.EDIT, "Radio Edit")),
            decodeTitleTags(row[SongTable.titleTags])
        )
    }

    private fun titleTagKindsBySong(): Map<UUID, Set<TitleTagKind>> = transaction(database) {
        SongTitleTagTable.selectAll()
            .groupBy({ it[SongTitleTagTable.songId].value }, { it[SongTitleTagTable.kind] })
            .mapValues { it.value.toSet() }
    }

    private fun assertTitleTagTableMatchesColumn() {
        val expected = transaction(database) {
            SongTable.select(SongTable.id, SongTable.titleTags)
                .associate { it[SongTable.id].value to decodeTitleTags(it[SongTable.titleTags]).map { tag -> tag.kind }.toSet() }
                .filterValues { it.isNotEmpty() }
        }
        assertEquals(expected, titleTagKindsBySong())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch keeps the title tag table in sync for new and dirty songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = InsertableAlbum("Sync Album", listOf("Sync Artist"))
        val created = songService.createBatch(
            listOf(
                InsertableSong(
                    title = "Song (Skrillex Remix) (feat. X)",
                    artists = listOf("Sync Artist"),
                    album = album,
                    duration = 100,
                    explicit = false,
                    path = "/sync/remix.flac",
                    trackNumber = 1,
                ),
                InsertableSong(
                    title = "Plain",
                    artists = listOf("Sync Artist"),
                    album = album,
                    duration = 120,
                    explicit = false,
                    path = "/sync/plain.flac",
                    trackNumber = 2,
                ),
            )
        )
        assertEquals(2, created.size)
        val remixId = created.values.single { it.title == "Song" }.id
        assertEquals(mapOf(remixId to setOf(TitleTagKind.REMIX, TitleTagKind.FEAT)), titleTagKindsBySong())
        assertTitleTagTableMatchesColumn()

        val again = songService.createBatch(
            listOf(
                InsertableSong(
                    title = "Song (Live)",
                    artists = listOf("Sync Artist"),
                    album = album,
                    duration = 100,
                    explicit = false,
                    path = "/sync/remix.flac",
                    trackNumber = 1,
                ),
            )
        )
        assertTrue(again.isEmpty())
        assertEquals(mapOf(remixId to setOf(TitleTagKind.LIVE)), titleTagKindsBySong())
        assertTitleTagTableMatchesColumn()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateSong and upsertSong keep the title tag table in sync`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/sync/song.mp3")

        songService.updateSong(
            songService.byId(songId)!!.copy(
                title = "Song (Radio Edit)",
                tags = listOf(TitleTag(TitleTagKind.FEAT, "feat. X"), TitleTag(TitleTagKind.FEAT, "with Y"))
            ),
            user.id
        )
        assertEquals(mapOf(songId to setOf(TitleTagKind.FEAT, TitleTagKind.EDIT)), titleTagKindsBySong())
        assertTitleTagTableMatchesColumn()

        songService.updateSong(songService.byId(songId)!!.copy(title = "Song", tags = emptyList()), user.id)
        assertEquals(emptyMap<UUID, Set<TitleTagKind>>(), titleTagKindsBySong())
        assertTitleTagTableMatchesColumn()

        songService.upsertSong(songService.byId(songId)!!.copy(title = "Song (Live)", tags = emptyList()))
        assertEquals(mapOf(songId to setOf(TitleTagKind.LIVE)), titleTagKindsBySong())
        assertTitleTagTableMatchesColumn()

        songService.deleteSongs(listOf(songId))
        assertEquals(0L, transaction(database) { SongTitleTagTable.selectAll().count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `user songs carry only the requesting user's tags with an action and song edits leave them untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertSongWithPath("/x/tagged.mp3")
        val otherUserId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert {
                it[id] = otherUserId
                it[username] = "other"
                it[passwordHash] = "hash"
            }
        }
        val tagService = TimecodeTagService()
        tagService.createTag(user.id, songId, TimecodeTagType.NOTE, "passive", 500L, null)
        val skipTo = tagService.createTag(user.id, songId, TimecodeTagType.MARKER, "start", 9000L, null, TimecodeTagAction.SKIP_TO)
        val skip = tagService.createTag(user.id, songId, TimecodeTagType.CHAPTER, "intro", 1000L, 4000L, TimecodeTagAction.SKIP, true)
        tagService.createTag(otherUserId, songId, TimecodeTagType.CHAPTER, "foreign", 0L, 2000L, TimecodeTagAction.SKIP)

        val song = songService.byId(songId, user.id)!!
        assertEquals(listOf(skip, skipTo), song.playbackTags)
        assertEquals(listOf(skip, skipTo), songService.byIds(listOf(songId), user.id).single().playbackTags)
        assertEquals(listOf("foreign"), songService.byId(songId, otherUserId)!!.playbackTags.map { it.text })

        val updated = songService.updateSong(songService.byId(songId)!!.copy(title = "Renamed"), user.id)

        assertEquals("Renamed", updated?.title)
        assertEquals(listOf(skip, skipTo), updated?.playbackTags)
        assertEquals(4, transaction(database) { TimecodeTagTable.selectAll().count() })
        assertEquals(3, tagService.getTags(user.id, songId).size)
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

        val notHighQuality = songService.allSongIds(true, excludeTags = listOf(SongTag.Q_96)).toList()
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

        val resultExplicit = rpcService.allSongs(0, 10, explicit = true, tags = emptyList())
        assertEquals(1, resultExplicit.data.size)
        assertTrue(resultExplicit.data[0].explicit)

        val resultNonExplicit = rpcService.allSongs(0, 10, explicit = false, tags = emptyList())
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

        val firstPage = rpcService.allSongs(0, 2, true, emptyList())
        assertEquals(2, firstPage.data.size)
        assertTrue(firstPage.hasNextPage)
        assertEquals(5, firstPage.total)

        val secondPage = rpcService.allSongs(1, 2, true, emptyList())
        assertEquals(2, secondPage.data.size)
        assertTrue(secondPage.hasNextPage)

        val lastPage = rpcService.allSongs(2, 2, true, emptyList())
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

    private fun insertTaggedSong(
        albumId: UUID,
        songTitle: String,
        tags: List<TitleTag>,
        rate: Int = 44100,
        songLyrics: String = "",
    ): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            SongTable.insert {
                it[id] = songId
                it[title] = songTitle
                it[SongTable.albumId] = albumId
                it[titleTags] = encodeTitleTags(tags)
                it[sampleRate] = rate
                it[lyrics] = songLyrics
            }
            syncSongTitleTags(songId, tags)
        }
        return songId
    }

    private suspend fun assertFilteredSongs(
        expected: Set<UUID>,
        tags: List<SongTag> = emptyList(),
        excludeTags: List<SongTag> = emptyList(),
        titleTags: List<TitleTagKind> = emptyList(),
        excludeTitleTags: List<TitleTagKind> = emptyList(),
    ) {
        val ids = songService.allSongIds(true, tags, excludeTags, titleTags, excludeTitleTags).toList()
        assertEquals(expected, ids.toSet())
        assertEquals(expected.size, ids.size)

        val page = rpcService.allSongs(0, 50, true, tags, excludeTags, titleTags, excludeTitleTags)
        assertEquals(expected, page.data.map { it.id }.toSet())
        assertEquals(expected.size, page.total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `allSongs and allSongIds combine included and excluded song tags and title tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
        }
        val plain = insertTaggedSong(albumId, "Plain", emptyList(), rate = 96000, songLyrics = "La la")
        val remix = insertTaggedSong(albumId, "Remixed", listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix")))
        val liveFeat = insertTaggedSong(
            albumId,
            "Live Feat",
            listOf(TitleTag(TitleTagKind.LIVE, "Live"), TitleTag(TitleTagKind.FEAT, "feat. X")),
            rate = 96000,
        )
        val feat = insertTaggedSong(albumId, "Feat", listOf(TitleTag(TitleTagKind.FEAT, "feat. Y")), songLyrics = "La la")
        val mbId = UUID.randomUUID()
        transaction(database) {
            MBRecordingTable.insert {
                it[id] = mbId
                it[title] = "Remixed"
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = remix
                it[musicBrainzId] = mbId
            }
        }

        assertFilteredSongs(setOf(plain, remix, liveFeat, feat))
        assertFilteredSongs(setOf(remix), titleTags = listOf(TitleTagKind.REMIX))
        assertFilteredSongs(setOf(remix, liveFeat), titleTags = listOf(TitleTagKind.LIVE, TitleTagKind.REMIX))
        assertFilteredSongs(setOf(plain, remix), excludeTitleTags = listOf(TitleTagKind.FEAT))
        assertFilteredSongs(setOf(feat), titleTags = listOf(TitleTagKind.FEAT), excludeTitleTags = listOf(TitleTagKind.LIVE))
        assertFilteredSongs(setOf(remix, feat), excludeTags = listOf(SongTag.Q_96))
        assertFilteredSongs(setOf(remix), excludeTags = listOf(SongTag.Q_96, SongTag.HAS_LYRICS))
        assertFilteredSongs(setOf(plain, remix, liveFeat, feat), excludeTags = listOf(SongTag.Q_192))
        assertFilteredSongs(setOf(liveFeat), tags = listOf(SongTag.Q_96), excludeTags = listOf(SongTag.HAS_LYRICS))
        assertFilteredSongs(setOf(plain), tags = listOf(SongTag.Q_96), excludeTitleTags = listOf(TitleTagKind.FEAT))
        assertFilteredSongs(
            setOf(liveFeat),
            excludeTags = listOf(SongTag.HAS_LYRICS),
            excludeTitleTags = listOf(TitleTagKind.REMIX),
        )
        assertFilteredSongs(setOf(remix), tags = listOf(SongTag.HAS_MUSICBRAINZ_ID))
        assertFilteredSongs(setOf(plain, liveFeat, feat), excludeTags = listOf(SongTag.HAS_MUSICBRAINZ_ID))
        assertFilteredSongs(
            setOf(liveFeat, feat),
            excludeTags = listOf(SongTag.HAS_MUSICBRAINZ_ID),
            titleTags = listOf(TitleTagKind.REMIX, TitleTagKind.FEAT),
        )
        assertFilteredSongs(
            setOf(feat),
            tags = listOf(SongTag.HAS_LYRICS, SongTag.HAS_MUSICBRAINZ_ID),
            excludeTags = listOf(SongTag.Q_96),
            titleTags = listOf(TitleTagKind.FEAT, TitleTagKind.REMIX),
            excludeTitleTags = listOf(TitleTagKind.REMIX),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `exclude only filters keep songs without title tags`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        every { storageService.customAudioPath } returns "/custom/path"
        val albumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert { it[id] = albumId; it[name] = "Album" }
        }
        val plain = insertTaggedSong(albumId, "Plain", emptyList())
        val live = insertTaggedSong(albumId, "Live", listOf(TitleTag(TitleTagKind.LIVE, "Live")))

        assertFilteredSongs(setOf(plain), excludeTitleTags = TitleTagKind.entries)
        assertFilteredSongs(setOf(plain, live), excludeTitleTags = listOf(TitleTagKind.REMIX))
        assertFilteredSongs(setOf(plain), excludeTags = listOf(SongTag.HAS_LYRICS), excludeTitleTags = listOf(TitleTagKind.LIVE))
        assertFilteredSongs(setOf(plain, live), excludeTags = listOf(SongTag.HAS_LYRICS, SongTag.CUSTOM_UPLOAD))
        assertFilteredSongs(emptySet(), titleTags = listOf(TitleTagKind.REMIX))
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
    fun `byId should return song with animated cover fields`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
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
            SongTable.insert {
                it[id] = songId
                it[title] = "Song with Animated Cover"
                it[animatedCover] = animatedImageId
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
        assertEquals(animatedImageId, song?.animatedCoverId)
        assertEquals(frameImageId, song?.animatedCoverImageId)
        assertEquals("animated_blurhash", song?.animatedCoverBlurHash)
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
            title = "Fetched Title",
            isrcs = listOf("USAT20300184"),
            artistCredit = emptyList()
        )

        val updated = rpcService.setMusicBrainzId(songId, mbId)
        assertNotNull(updated)
        assertEquals("USAT20300184", updated?.isrc)

        val (dbTitle, dbIsrc) = transaction(database) {
            val row = MBRecordingTable.selectAll().where { MBRecordingTable.id eq mbId }.single()
            val songRow = SongTable.selectAll().where { SongTable.id eq songId }.single()
            row[MBRecordingTable.title] to songRow[SongTable.isrc]
        }
        assertEquals("Fetched Title", dbTitle)
        assertEquals("USAT20300184", dbIsrc)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId should match by ISRC`(dialect: DbDialect) {
        runBlocking {
            setup(dialect)
            val songId = UUID.randomUUID()
            val isrc = "USAT20300184"
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
                    it[SongTable.isrc] = isrc
                }
            }

            coEvery { musicBrainzService.searchMb(match { it.isrc == isrc }, any()) } returns MusicBrainzRecording(
                id = mbId,
                title = "Matched Song",
                isrcs = listOf(isrc),
                artistCredit = emptyList()
            )
            coEvery { musicBrainzService.fetchRecordingById(mbId, any()) } returns MusicBrainzRecording(
                id = mbId,
                title = "Matched Song",
                isrcs = listOf(isrc),
                artistCredit = emptyList()
            )

            val updated = songService.fetchMusicBrainzId(songId, user.id)
            assertNotNull(updated)
            assertEquals(mbId, updated?.musicBrainzId)

            coVerify { musicBrainzService.searchMb(match { it.isrc == isrc }, any()) }
        }
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
    fun `byOriginalUrls picks the exact url match first, then the oldest song, then the lowest id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val exactNewer = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val providerOlder = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val sameUrlNewer = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val sameUrlOlder = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        val tieHigh = UUID.fromString("00000000-0000-0000-0000-00000000000f")
        val tieLow = UUID.fromString("00000000-0000-0000-0000-00000000000c")

        val exactUrl = "https://example.com/track/exact"
        val sharedUrl = "https://example.com/track/shared"
        val providerUrl = "https://example.com/track/provider"

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            fun song(songId: UUID, songTitle: String, url: String, insertedAt: Long) = SongTable.insert {
                it[id] = songId
                it[title] = songTitle
                it[SongTable.albumId] = albumId
                it[originalUrl] = url
                it[inserted] = insertedAt
            }
            song(exactNewer, "Exact newer", exactUrl, 2000)
            song(providerOlder, "Provider older", "", 1000)
            song(sameUrlNewer, "Same url newer", sharedUrl, 2000)
            song(sameUrlOlder, "Same url older", sharedUrl, 1000)
            song(tieHigh, "Tie high", "", 1000)
            song(tieLow, "Tie low", "", 1000)
            SongProviderTable.insert {
                it[SongProviderTable.songId] = providerOlder
                it[provider] = "example"
                it[externalId] = "exact"
                it[type] = Type.SONG.value
                it[rawUrl] = exactUrl
            }
            listOf(tieHigh, tieLow).forEach { songId ->
                SongProviderTable.insert {
                    it[SongProviderTable.songId] = songId
                    it[provider] = "example"
                    it[externalId] = songId.toString()
                    it[type] = Type.SONG.value
                    it[rawUrl] = providerUrl
                }
            }
        }

        val result = rpcService.byOriginalUrls(listOf(exactUrl, sharedUrl, providerUrl))

        assertEquals(exactNewer, result[exactUrl]?.id)
        assertEquals(sameUrlOlder, result[sharedUrl]?.id)
        assertEquals(tieLow, result[providerUrl]?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `byOriginalUrls resolves more urls than one lookup chunk the same as a small list`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val albumId = UUID.randomUUID()
        val older = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val newer = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val providerSong = UUID.randomUUID()
        val lateSong = UUID.randomUUID()

        val sharedUrl = "https://example.com/track/shared"
        val providerUrl = "https://example.com/track/provider"
        val lateUrl = "https://example.com/track/late"
        val fillers = (0 until 6000).map { "https://example.com/missing/$it" }

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            fun song(songId: UUID, songTitle: String, url: String, insertedAt: Long) = SongTable.insert {
                it[id] = songId
                it[title] = songTitle
                it[SongTable.albumId] = albumId
                it[originalUrl] = url
                it[inserted] = insertedAt
            }
            song(older, "Older", sharedUrl, 1000)
            song(newer, "Newer", sharedUrl, 2000)
            song(providerSong, "Provider", "", 1000)
            song(lateSong, "Late", lateUrl, 1000)
            SongProviderTable.insert {
                it[SongProviderTable.songId] = providerSong
                it[provider] = "example"
                it[externalId] = "provider"
                it[type] = Type.SONG.value
                it[rawUrl] = providerUrl
            }
        }

        val smallUrls = listOf(sharedUrl, providerUrl, lateUrl)
        val small = rpcService.byOriginalUrls(smallUrls)
        val large = rpcService.byOriginalUrls(listOf(sharedUrl) + fillers.take(5500) + providerUrl + fillers.drop(5500) + lateUrl)

        assertEquals(6003, large.size)
        assertEquals(older, small[sharedUrl]?.id)
        assertEquals(providerSong, small[providerUrl]?.id)
        assertEquals(lateSong, small[lateUrl]?.id)
        assertEquals(small.mapValues { it.value?.id }, smallUrls.associateWith { large[it]?.id })
        assertTrue(fillers.all { it in large && large[it] == null })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch should populate SongProviderTable`(dialect: DbDialect) {
        runBlocking {
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

    private suspend fun useAcoustIdResponse(body: String) {
        mockkObject(ApiClient)
        val engine = MockEngine { request ->
            acoustIdRequests += request.url
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        every { ApiClient.instance } returns HttpClient(engine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        val queue = HttpClientQueueService()
        queue.startService()
        acoustIdQueue = queue
        every { ApiClient.queueInstance } returns queue
    }

    private fun insertUntaggedSong(): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Untagged"
                it[SongTable.albumId] = albumId
                it[filePath] = "/music/untagged.mp3"
                it[duration] = 200_000
            }
        }
        return songId
    }

    private fun acoustIdHit(recordingId: UUID) = """
        {"status": "ok", "results": [{"id": "${UUID.randomUUID()}", "score": 0.96,
          "recordings": [{"id": "$recordingId", "title": "Real Title", "duration": 200}]}]}
    """.trimIndent()

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId takes the AcoustID recording and never searches by name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertUntaggedSong()
        val mbId = UUID.randomUUID()
        coEvery { fingerprintService.fingerprint("/music/untagged.mp3") } returns Fingerprint(200, "AQADtEmU")
        useAcoustIdResponse(acoustIdHit(mbId))
        coEvery { musicBrainzService.fetchRecordingById(mbId, any()) } returns MusicBrainzRecording(
            id = mbId,
            title = "Real Title",
            artistCredit = emptyList()
        )

        val updated = songService.fetchMusicBrainzId(songId, user.id)

        assertEquals(mbId, updated?.musicBrainzId)
        coVerify(exactly = 0) { musicBrainzService.searchMb(any(), any()) }
        assertEquals("testKey", acoustIdRequests.single().parameters["client"])
        val row = transaction(database) {
            SongAcoustIdTable.selectAll().where { SongAcoustIdTable.songId eq songId }.single()
        }
        assertEquals("AQADtEmU", row[SongAcoustIdTable.fingerprint])
        assertEquals(200, row[SongAcoustIdTable.duration])
        assertNotNull(row[SongAcoustIdTable.acoustId])
        assertEquals(0.96, row[SongAcoustIdTable.score])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId falls back to the name search when AcoustID finds nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertUntaggedSong()
        val mbId = UUID.randomUUID()
        coEvery { fingerprintService.fingerprint(any()) } returns Fingerprint(200, "AQADtEmU")
        useAcoustIdResponse("""{"status": "ok", "results": []}""")
        val recording = MusicBrainzRecording(id = mbId, title = "Untagged", artistCredit = emptyList())
        coEvery { musicBrainzService.searchMb(any(), any()) } returns recording
        coEvery { musicBrainzService.fetchRecordingById(mbId, any()) } returns recording

        val updated = songService.fetchMusicBrainzId(songId, user.id)

        assertEquals(mbId, updated?.musicBrainzId)
        coVerify(exactly = 1) { musicBrainzService.searchMb(any(), any()) }
        val row = transaction(database) {
            SongAcoustIdTable.selectAll().where { SongAcoustIdTable.songId eq songId }.single()
        }
        assertNull(row[SongAcoustIdTable.acoustId])
        assertTrue(row[SongAcoustIdTable.lastCheck] > 0)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId never fingerprints a song that already has a MusicBrainz id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertUntaggedSong()
        val mbId = UUID.randomUUID()
        transaction(database) {
            MBRecordingTable.insert {
                it[id] = mbId
                it[title] = "Known"
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = songId
                it[musicBrainzId] = mbId
                it[lastCheck] = 0
            }
        }
        coEvery { musicBrainzService.fetchRecordingById(mbId, any()) } returns MusicBrainzRecording(
            id = mbId,
            title = "Known",
            artistCredit = emptyList()
        )

        songService.fetchMusicBrainzId(songId, user.id)

        coVerify(exactly = 0) { fingerprintService.fingerprint(any()) }
        coVerify(exactly = 0) { musicBrainzService.searchMb(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId skips fingerprint and lookup for a recent negative AcoustID result`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertUntaggedSong()
        transaction(database) {
            SongAcoustIdTable.insert {
                it[SongAcoustIdTable.songId] = songId
                it[fingerprint] = "AQADtEmU"
                it[duration] = 200
                it[acoustId] = null
                it[score] = null
                it[lastCheck] = System.currentTimeMillis() - 5L * 24 * 60 * 60 * 1000
            }
        }
        useAcoustIdResponse("""{"status": "ok", "results": []}""")
        coEvery { musicBrainzService.searchMb(any(), any()) } returns null

        songService.fetchMusicBrainzId(songId, user.id)

        coVerify(exactly = 0) { fingerprintService.fingerprint(any()) }
        assertTrue(acoustIdRequests.isEmpty())
        coVerify(exactly = 1) { musicBrainzService.searchMb(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchMusicBrainzId reuses the cached fingerprint once a negative result is stale`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = insertUntaggedSong()
        val mbId = UUID.randomUUID()
        transaction(database) {
            SongAcoustIdTable.insert {
                it[SongAcoustIdTable.songId] = songId
                it[fingerprint] = "AQADcached"
                it[duration] = 199
                it[acoustId] = null
                it[score] = null
                it[lastCheck] = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
            }
        }
        useAcoustIdResponse(acoustIdHit(mbId))
        coEvery { musicBrainzService.fetchRecordingById(mbId, any()) } returns MusicBrainzRecording(
            id = mbId,
            title = "Real Title",
            artistCredit = emptyList()
        )

        val updated = songService.fetchMusicBrainzId(songId, user.id)

        assertEquals(mbId, updated?.musicBrainzId)
        coVerify(exactly = 0) { fingerprintService.fingerprint(any()) }
        val request = acoustIdRequests.single()
        assertEquals("AQADcached", request.parameters["fingerprint"])
        assertEquals("199", request.parameters["duration"])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch links songs whose albums differ only in cover hash to one album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val base = InsertableAlbum("Cover Album", listOf("Cover Artist"), songCount = 2)
        val songs = listOf(
            InsertableSong(
                title = "Cover Song 1",
                artists = listOf("Cover Artist"),
                album = base.copy(coverHash = "cover-hash-1"),
                duration = 100,
                explicit = false,
                path = "/path/cover/1",
                trackNumber = 1,
            ),
            InsertableSong(
                title = "Cover Song 2",
                artists = listOf("Cover Artist"),
                album = base.copy(coverHash = "cover-hash-2"),
                duration = 200,
                explicit = false,
                path = "/path/cover/2",
                trackNumber = 2,
            ),
        )

        val created = songService.createBatch(songs)

        assertEquals(setOf("Cover Song 1", "Cover Song 2"), created.values.map { it.title }.toSet())
        val albumIds = transaction(database) {
            SongTable.selectAll().map { it[SongTable.albumId].value }.toSet()
        }
        assertEquals(1, albumIds.size)
        assertEquals(1L, transaction(database) { AlbumTable.selectAll().count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBatch links songs whose albums differ only in barcode to one album`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val base = InsertableAlbum("Barcode Album", listOf("Barcode Artist"), songCount = 2)
        val songs = listOf(
            InsertableSong(
                title = "Barcode Song 1",
                artists = listOf("Barcode Artist"),
                album = base,
                duration = 100,
                explicit = false,
                path = "/path/barcode/1",
                trackNumber = 1,
            ),
            InsertableSong(
                title = "Barcode Song 2",
                artists = listOf("Barcode Artist"),
                album = base.copy(barcode = "123456789012"),
                duration = 200,
                explicit = false,
                path = "/path/barcode/2",
                trackNumber = 2,
            ),
        )

        val created = songService.createBatch(songs)

        assertEquals(setOf("Barcode Song 1", "Barcode Song 2"), created.values.map { it.title }.toSet())
        val albumIds = transaction(database) {
            SongTable.selectAll().map { it[SongTable.albumId].value }.toSet()
        }
        assertEquals(1, albumIds.size)
        assertEquals(1L, transaction(database) { AlbumTable.selectAll().count() })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `mapUserSong maps every song and user field like the full row mapping`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Mapping Album"
            }
            ImageTable.insert {
                it[id] = imageId
                it[path] = "cover.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "song_blurhash"
            }
            MBRecordingTable.insert {
                it[id] = mbId
                it[title] = "MB Mapping Song"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Mapping Song 🅴"
                it[titleTags] = encodeTitleTags(listOf(TitleTag(TitleTagKind.REMIX, "Club Mix")))
                it[SongTable.albumId] = albumId
                it[duration] = 123456
                it[explicit] = true
                it[releaseDate] = "2024-03-01"
                it[lyrics] = "la la"
                it[filePath] = "/music/mapping.flac"
                it[originalUrl] = "https://tidal.com/track/42"
                it[trackNumber] = 3
                it[discNumber] = 2
                it[copyright] = "(c) Test"
                it[format] = "flac"
                it[sampleRate] = 96000
                it[bitsPerSample] = 24
                it[bitRate] = 2304000
                it[fileSize] = 55555
                it[channels] = 2
                it[isrc] = "USAT20300184"
                it[cover] = imageId
                it[audioStartMs] = 120
            }
            SongMusicBrainzTable.insert {
                it[SongMusicBrainzTable.songId] = songId
                it[musicBrainzId] = mbId
            }
            UserSongTable.insert {
                it[userId] = user.id
                it[UserSongTable.songId] = songId
                it[isFavourite] = true
                it[superLikedAt] = 3000L
                it[createdAt] = 1000L
                it[updatedAt] = 2000L
            }
        }

        val row = transaction(database) {
            SongTable
                .leftJoin(UserSongTable)
                .leftJoin(SongMusicBrainzTable)
                .leftJoin(ImageTable, onColumn = { SongTable.cover }, otherColumn = { ImageTable.id })
                .selectAll()
                .where { SongTable.id eq songId }
                .single()
        }
        val genres = listOf(Genre(UUID.randomUUID(), "pop"))

        val expected = UserSong(
            id = songId,
            title = row[SongTable.title].removeSuffix("🅴").trimEnd(),
            artists = listOf(),
            album = null,
            duration = row[SongTable.duration],
            explicit = row[SongTable.explicit],
            releaseDate = getDateFromISO(row[SongTable.releaseDate]),
            lyrics = row[SongTable.lyrics],
            path = row[SongTable.filePath],
            originalUrl = row[SongTable.originalUrl],
            trackNumber = row[SongTable.trackNumber],
            discNumber = row[SongTable.discNumber],
            copyright = row[SongTable.copyright],
            audio = AudioInfo(
                codec = row[SongTable.format],
                sampleRate = row[SongTable.sampleRate],
                bitsPerSample = row[SongTable.bitsPerSample],
                bitRate = row[SongTable.bitRate],
                fileSize = row[SongTable.fileSize],
                channels = row[SongTable.channels],
            ),
            isrc = row[SongTable.isrc],
            coverId = row[SongTable.cover]?.value,
            blurHash = row.getOrNull(ImageTable.blurHash),
            musicBrainzId = row.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value,
            genres = genres,
            animatedCoverId = row[SongTable.animatedCover]?.value,
            animatedCoverImageId = null,
            animatedCoverBlurHash = null,
            audioStartMs = row.getOrNull(SongTable.audioStartMs),
            tags = row.titleTags(),
            isFavourite = true,
            userSongCreatedAt = 1000L.date,
            userSongUpdatedAt = 2000L.date,
            likeLevel = LikeLevel.SUPER,
            superLikedAt = 3000L.date,
        )

        val mapped = SongService.mapUserSong(row, genres)

        assertEquals(expected, mapped)
        assertEquals("Mapping Song", mapped.title)
        assertEquals("song_blurhash", mapped.blurHash)
        assertEquals(mbId, mapped.musicBrainzId)
        assertEquals(listOf(TitleTag(TitleTagKind.REMIX, "Club Mix")), mapped.tags)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `batched id flows over rows tied across page breaks return every song once in id order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val expected = transaction(database) {
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
            val songIds = (1..2500).map { UUID.randomUUID() }
            SongTable.batchInsert(songIds) { songId ->
                this[SongTable.id] = songId
                this[SongTable.title] = "Song"
                this[SongTable.albumId] = albumId
                this[SongTable.inserted] = 1000L
            }
            UserSongTable.batchInsert(songIds) { songId ->
                this[UserSongTable.userId] = user.id
                this[UserSongTable.songId] = songId
                this[UserSongTable.isFavourite] = true
                this[UserSongTable.superLikedAt] = 3000L
                this[UserSongTable.updatedAt] = 2000L
            }
            SongTable.select(SongTable.id).orderBy(SongTable.id, SortOrder.ASC).map { it[SongTable.id].value }
        }

        assertEquals(2500, expected.distinct().size)
        assertEquals(expected, songService.songIdsByAlbum(albumId).toList())
        assertEquals(expected, songService.songIdsByArtist(artistId).toList())
        assertEquals(expected, songService.songIdsWithoutMusicBrainzId().toList())
        assertEquals(expected, songService.songIdsForProviderEnrichment().toList())
        assertEquals(expected, songService.likedSongIds(true, user.id).toList())
        assertEquals(expected, songService.superLikedSongIds(true, user.id).toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `songIdsByArtist through subqueries matches the id list query`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val otherArtistId = UUID.randomUUID()
        val unlinkedArtistId = UUID.randomUUID()
        val artistAlbumId = UUID.randomUUID()
        val otherAlbumId = UUID.randomUUID()

        transaction(database) {
            listOf(artistId, otherArtistId, unlinkedArtistId).forEach { id ->
                ArtistTable.insert {
                    it[ArtistTable.id] = id
                    it[name] = "Artist $id"
                }
            }
            listOf(artistAlbumId, otherAlbumId).forEach { id ->
                AlbumTable.insert {
                    it[AlbumTable.id] = id
                    it[name] = "Album $id"
                }
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = artistAlbumId
                it[AlbumArtistTable.artistId] = artistId
            }
            AlbumArtistTable.insert {
                it[AlbumArtistTable.albumId] = otherAlbumId
                it[AlbumArtistTable.artistId] = otherArtistId
            }
            val songs = listOf(
                Triple(artistAlbumId, "2020-01-01", 1),
                Triple(artistAlbumId, "2020-01-01", 2),
                Triple(artistAlbumId, "2020-01-01", 2),
                Triple(artistAlbumId, null, 3),
                Triple(otherAlbumId, "2021-05-05", 1),
                Triple(otherAlbumId, "2021-05-05", 1),
                Triple(otherAlbumId, "2019-03-03", 4),
            )
            songs.forEachIndexed { index, (songAlbumId, songReleaseDate, track) ->
                val songId = UUID.randomUUID()
                SongTable.insert {
                    it[id] = songId
                    it[title] = "Song $index"
                    it[albumId] = songAlbumId
                    it[releaseDate] = songReleaseDate
                    it[trackNumber] = track
                }
                if (index == 1 || index == 4 || index == 6) {
                    SongArtistTable.insert {
                        it[SongArtistTable.songId] = songId
                        it[SongArtistTable.artistId] = artistId
                    }
                }
            }
        }

        val expected = transaction(database) {
            val songIds = SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId eq artistId }
                .map { it[SongArtistTable.songId].value }
            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId eq artistId }
                .map { it[AlbumArtistTable.albumId].value }
            SongTable
                .select(SongTable.id)
                .where { (SongTable.id inList songIds) or (SongTable.albumId inList albumIds) }
                .orderBy(SongTable.releaseDate, SortOrder.DESC)
                .orderBy(SongTable.trackNumber, SortOrder.ASC)
                .orderBy(SongTable.id, SortOrder.ASC)
                .map { it[SongTable.id].value }
        }

        val result = songService.songIdsByArtist(artistId).toList()
        assertEquals(6, expected.size)
        assertEquals(expected, result)
        assertEquals(emptyList<UUID>(), songService.songIdsByArtist(unlinkedArtistId).toList())
    }
}
