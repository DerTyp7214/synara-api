package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.metadata.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import java.time.LocalDate
import java.util.UUID
import dev.dertyp.data.Artist as DataArtist

class ReleaseServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ReleaseService
    
    private lateinit var musicBrainzService: MusicBrainzService
    private lateinit var artistService: ArtistService
    private lateinit var imageService: ImageService
    private lateinit var environment: ApplicationEnvironment
    private lateinit var tidalService: TidalService
    private lateinit var appleMusicService: AppleMusicService
    private lateinit var spotifyService: SpotifyService

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single<StorageService> { mockk(relaxed = true) }
                single<RedisCacheProvider.Config> { mockk(relaxed = true) }
                
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { MusicBrainzCacheService() }
                single { mockk<ArtistService>(relaxed = true) }
                single { mockk<ImageService>(relaxed = true) }
                single { mockk<SpotifyService>(relaxed = true) }
                single { mockk<AppleMusicService>(relaxed = true) }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { mockk<TidalService>(relaxed = true) }
            })
        }

        musicBrainzService = get()
        artistService = get()
        imageService = get()
        environment = get()
        tidalService = get()
        spotifyService = get()
        appleMusicService = get()

        database = TestDatabase.connect(dialect, "release_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                AlbumTable,
                AlbumArtistTable,
                AlbumMusicBrainzTable,
                SongTable,
                SongArtistTable,
                SongMusicBrainzTable,
                FollowedArtistTable,
                RecentReleaseTable,
                *allMusicBrainzTables
            )
        }

        service = ReleaseService(environment)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `followArtist should link existing artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Artist"; it[sortName] = "Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
        }

        val result = service.followArtist(userId, mbId)
        assertTrue(result)

        val followed = service.getFollowedArtists(userId)
        assertEquals(1, followed.size)
        assertEquals(artistId, followed[0].artistId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `followArtist should create artist if not exists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val newArtistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
        }

        coEvery { musicBrainzService.fetchArtistById(mbId) } returns MusicBrainzArtist(id = mbId, name = "New Artist")
        coEvery { artistService.createArtist("New Artist", any(), any(), mbId) } answers {
            transaction(database) {
                ArtistTable.insert {
                    it[id] = newArtistId
                    it[name] = "New Artist"
                }
            }
            DataArtist(
                id = newArtistId,
                name = "New Artist",
                isGroup = false
            )
        }

        val result = service.followArtist(userId, mbId)
        assertTrue(result)

        val followed = service.getFollowedArtists(userId)
        assertEquals(1, followed.size)
        assertEquals(newArtistId, followed[0].artistId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases should download releases and resolve links`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.appleMusic, any()) } returns appleMusicService

        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(
                id = releaseId,
                title = "New Album",
                firstReleaseDate = "2023-10-27",
                relations = listOf(
                    MusicBrainzRelation(
                        type = "spotify",
                        url = MusicBrainzRelationUrl(
                            id = UUID.randomUUID(),
                            resource = "https://spotify.com/album/123"
                        )
                    )
                )
            )
        )

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.resolvePlatformLinks(any(), priority = HttpClientPriority.LOW) } returns listOf("https://tidal.com/album/456")
        
        val dummyImageId = UUID.randomUUID()
        transaction(database) {
            ImageTable.insert {
                it[id] = dummyImageId
                it[path] = "test"
                it[imageHash] = "hash"
                it[origin] = "test"
            }
        }
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns dummyImageId

        spiedService.fetchNewReleases()

        val releases = service.getRecentReleases(userId).data
        assertEquals(1, releases.size)
        assertEquals("New Album", releases[0].title)
        assertTrue(releases[0].links.contains("https://tidal.com/album/456"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases should include releases already in library but link them`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.appleMusic, any()) } returns appleMusicService

        val mbId = UUID.randomUUID()
        val releaseIdInAlbumDb = UUID.randomUUID()
        val releaseIdInSongDb = UUID.randomUUID()
        val someOtherGroupId = UUID.randomUUID()
        val releaseIdNew = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        var existingAlbumId: UUID? = null
        var existingSongId: UUID? = null

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }

            val albumId = AlbumTable.insert { it[name] = "Existing Album" }[AlbumTable.id]
            existingAlbumId = albumId.value
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[this.artistId] = artistId }
            MBReleaseTable.insert { it[id] = releaseIdInAlbumDb; it[title] = "Existing Album" }
            AlbumMusicBrainzTable.insert { it[this.albumId] = albumId; it[musicBrainzId] = releaseIdInAlbumDb }

            val songId = SongTable.insert {
                it[title] = "Existing Song"
                it[this.albumId] = albumId
            }[SongTable.id]
            existingSongId = songId.value
            SongArtistTable.insert { it[this.songId] = songId; it[this.artistId] = artistId }
            MBRecordingTable.insert { it[id] = releaseIdInSongDb; it[title] = "Existing Song" }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = releaseIdInSongDb }
        }

        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzRelease(
                id = releaseIdInAlbumDb,
                releaseGroup = MusicBrainzReleaseGroup(
                    id = releaseIdInAlbumDb,
                    title = "Existing Album"
                )
            ),
            MusicBrainzRelease(
                id = releaseIdInSongDb,
                releaseGroup = MusicBrainzReleaseGroup(id = someOtherGroupId, title = "Album with existing song")
            )
        )

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(
                id = releaseIdInAlbumDb,
                title = "Existing Album",
                firstReleaseDate = "2023-01-01"
            ),
            MusicBrainzReleaseGroup(
                id = someOtherGroupId,
                title = "Album with existing song",
                firstReleaseDate = "2023-01-01"
            ),
            MusicBrainzReleaseGroup(
                id = releaseIdNew,
                title = "Truly New Album",
                firstReleaseDate = "2023-01-01"
            )
        )

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.resolvePlatformLinks(any(), priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        val releases = service.getRecentReleases(userId).data
        assertEquals(1, releases.size)
        assertEquals("Truly New Album", releases[0].title)
        assertEquals(null, releases[0].albumId)
        assertEquals(null, releases[0].songId)
        
        transaction(database) {
            val allInDb = RecentReleaseTable.selectAll().map { it[RecentReleaseTable.title] to (it[RecentReleaseTable.albumId]?.value to it[RecentReleaseTable.songId]?.value) }
            assertEquals(3, allInDb.size)
            
            val albumWithExistingSong = allInDb.find { it.first == "Album with existing song" }
            assertEquals(existingSongId, albumWithExistingSong?.second?.second)

            val existingAlbum = allInDb.find { it.first == "Existing Album" }
            assertEquals(existingAlbumId, existingAlbum?.second?.first)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases should only return valid releases`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[FollowedArtistTable.userId] = userId; it[FollowedArtistTable.artistId] = artistId }

            val dummyAlbumId = AlbumTable.insert { 
                it[id] = UUID.randomUUID()
                it[name] = "Dummy Album" 
            }[AlbumTable.id]

            val dummySongId = SongTable.insert { 
                it[id] = UUID.randomUUID()
                it[title] = "Dummy Song"
                it[SongTable.albumId] = dummyAlbumId
            }[SongTable.id]

            val validRelId = UUID.randomUUID()
            MBReleaseGroupTable.insert { it[id] = validRelId; it[title] = "New Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = validRelId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "New Release"
                it[RecentReleaseTable.releaseDate] = 1000L
            }

            val noDateRelId = UUID.randomUUID()
            MBReleaseGroupTable.insert { it[id] = noDateRelId; it[title] = "No Date Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = noDateRelId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "No Date Release"
                it[RecentReleaseTable.releaseDate] = null
            }

            val albumRelId = UUID.randomUUID()
            MBReleaseGroupTable.insert { it[id] = albumRelId; it[title] = "Existing Album" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = albumRelId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Existing Album"
                it[RecentReleaseTable.albumId] = dummyAlbumId
                it[RecentReleaseTable.releaseDate] = 1000L
            }

            val songRelId = UUID.randomUUID()
            MBReleaseGroupTable.insert { it[id] = songRelId; it[title] = "Existing Song" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = songRelId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Existing Song"
                it[RecentReleaseTable.songId] = dummySongId
                it[RecentReleaseTable.releaseDate] = 1000L
            }
        }

        val releases = service.getRecentReleases(userId).data
        assertEquals(1, releases.size)
        assertEquals("New Release", releases[0].title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases should support pagination`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }

            for (i in 1..5) {
                val relId = UUID.randomUUID()
                MBReleaseGroupTable.insert { it[id] = relId; it[title] = "Release $i" }
                RecentReleaseTable.insert {
                    it[releaseId] = relId
                    it[this.artistId] = artistId
                    it[artistName] = "Artist"
                    it[title] = "Release $i"
                    it[releaseDate] = i.toLong()
                }
            }
        }

        val page0 = service.getRecentReleases(userId, page = 0, pageSize = 2)
        assertEquals(2, page0.data.size)
        assertEquals(5, page0.total)
        assertTrue(page0.hasNextPage)
        assertEquals("Release 5", page0.data[0].title)
        assertEquals("Release 4", page0.data[1].title)

        val page1 = service.getRecentReleases(userId, page = 1, pageSize = 2)
        assertEquals(2, page1.data.size)
        assertEquals("Release 3", page1.data[0].title)
        assertEquals("Release 2", page1.data[1].title)
        assertTrue(page1.hasNextPage)

        val page2 = service.getRecentReleases(userId, page = 2, pageSize = 2)
        assertEquals(1, page2.data.size)
        assertEquals("Release 1", page2.data[0].title)
        assertTrue(!page2.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unfollowArtist should remove record`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        val result = service.unfollowArtist(userId, artistId)
        assertTrue(result)

        val followed = service.getFollowedArtists(userId)
        assertTrue(followed.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases should handle MusicBrainz API failure`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mbId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.appleMusic, any()) } returns appleMusicService

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Artist"; it[sortName] = "Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } throws Exception("MB Failure")
        
        val result = service.fetchNewReleases()
        assertTrue(result.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases should handle title matching ambiguity`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(MetadataService.Companion.MetadataType.appleMusic, any()) } returns appleMusicService

        val mbId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val relGroupId = UUID.randomUUID()
        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = relGroupId, title = "The Title", firstReleaseDate = "2023-01-01", primaryType = "Single")
        )
        
        coEvery { appleMusicService.searchAlbums(any(), any(), any(), priority = HttpClientPriority.LOW) } returns listOf(
            IMetadataService.Album(
                id = "apple-1",
                title = "The Title - Single",
                artists = listOf("Test Artist"),
                additionalTitles = emptyList(),
                trackCount = 1,
                releaseDate = LocalDate.parse("2023-01-01")
            )
        )
        
        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.resolvePlatformLinks(any(), priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        val result = spiedService.fetchNewReleases()
        assertEquals(1, result["Test Artist"])
        
        transaction(database) {
            val release = RecentReleaseTable.selectAll().single()
            val links = ApplicationScope.json.decodeFromString<List<String>>(release[RecentReleaseTable.links])
            assertTrue(links.any { it.contains("apple-1") })
        }
    }
}
