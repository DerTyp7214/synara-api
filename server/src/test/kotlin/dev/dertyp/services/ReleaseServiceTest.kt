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
import org.jetbrains.exposed.v1.core.dao.id.EntityID
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
import org.koin.test.KoinTest
import org.koin.test.get
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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
    private lateinit var linkResolverService: LinkResolverService

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
                single { mockk<LinkResolverService>(relaxed = true) }
            })
        }

        musicBrainzService = get()
        artistService = get()
        imageService = get()
        environment = get()
        tidalService = get()
        spotifyService = get()
        appleMusicService = get()
        linkResolverService = get()

        database = TestDatabase.connect(dialect, "release_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                ImageMetadataTable,
                ArtistTable,
                ArtistMusicBrainzTable,
                AlbumTable,
                AlbumArtistTable,
                AlbumMusicBrainzTable,
                SongTable, SongVariantTable,
                SongArtistTable,
                SongMusicBrainzTable,
                FollowedArtistTable,
                RecentReleaseTable,
                RecentReleaseProviderTable,
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
    fun `getRecentReleases should return release with cover blurHash`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val imageId = UUID.randomUUID()
        
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Artist"
            }
            FollowedArtistTable.insert {
                it[FollowedArtistTable.userId] = userId
                it[FollowedArtistTable.artistId] = artistId
            }
            ImageTable.insert {
                it[id] = imageId
                it[path] = "test.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
                it[blurHash] = "recent_blurhash"
            }
            MBReleaseGroupTable.insert {
                it[id] = releaseId
                it[title] = "Recent Release"
            }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.artistName] = "Artist"
                it[RecentReleaseTable.title] = "Recent Release"
                it[RecentReleaseTable.releaseDate] = 1672531200000L // 2023-01-01
                it[RecentReleaseTable.type] = ReleaseType.Album
                it[RecentReleaseTable.imageId] = EntityID(imageId, ImageTable)
                it[RecentReleaseTable.links] = "[]"
            }
        }

        val result = service.getRecentReleases(userId)
        assertEquals(1, result.data.size)
        assertEquals("recent_blurhash", result.data[0].blurHash)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases should download releases and resolve links`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

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

        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns listOf("https://tidal.com/album/456")
        
        val spiedService = spyk(service, recordPrivateCalls = true)
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

        transaction(database) {
            val providers = RecentReleaseProviderTable.selectAll()
                .where { RecentReleaseProviderTable.releaseId eq releaseId }
                .associate { it[RecentReleaseProviderTable.provider] to it[RecentReleaseProviderTable.externalId] }
            
            assertEquals(2, providers.size)
            assertEquals("123", providers["spotify"])
            assertEquals("456", providers["tidal"])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases should include releases already in library but link them`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

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
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()
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
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

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
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

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
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        val result = spiedService.fetchNewReleases()
        assertEquals(1, result["Test Artist"])
        
        transaction(database) {
            val release = RecentReleaseTable.selectAll().single()
            val links = ApplicationScope.json.decodeFromString<List<String>>(release[RecentReleaseTable.links])
            assertTrue(links.any { it.contains("apple-1") })
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfillMissingRecentReleaseImages should fetch and update missing images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val imageId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            ImageTable.insert { it[id] = imageId; it[path] = "test"; it[imageHash] = "hash"; it[origin] = "test" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
                it[RecentReleaseTable.imageId] = null
                it[RecentReleaseTable.lastImageFetch] = null
            }
        }

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(releaseId) } returns imageId

        spiedService.backfillMissingRecentReleaseImages()

        transaction(database) {
            val release = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertEquals(imageId, release[RecentReleaseTable.imageId]?.value)
            assertNotNull(release[RecentReleaseTable.lastImageFetch])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfillMissingRecentReleaseImages should respect cooldown`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val now = Clock.System.now().toEpochMilliseconds()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
                it[RecentReleaseTable.imageId] = null
                it[RecentReleaseTable.lastImageFetch] = now
            }
        }

        val spiedService = spyk(service, recordPrivateCalls = true)
        spiedService.backfillMissingRecentReleaseImages()

        coVerify(exactly = 0) { spiedService.fetchReleaseGroupImage(any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfillMissingRecentReleaseImages should respect progressive cooldown tiers`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val now = Clock.System.now().toEpochMilliseconds()

        val rel1 = UUID.randomUUID()
        val date1 = now - 2.days.inWholeMilliseconds
        val last1 = now - 12.hours.inWholeMilliseconds

        val rel2 = UUID.randomUUID()
        val date2 = now - 7.days.inWholeMilliseconds
        val last2 = now - 1.days.inWholeMilliseconds

        val rel3 = UUID.randomUUID()
        val date3 = now - 15.days.inWholeMilliseconds
        val last3 = now - 6.days.inWholeMilliseconds

        val rel4 = UUID.randomUUID()
        val date4 = now - 2.days.inWholeMilliseconds
        val last4 = now - 25.hours.inWholeMilliseconds

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            listOf(
                rel1 to Pair(date1, last1),
                rel2 to Pair(date2, last2),
                rel3 to Pair(date3, last3),
                rel4 to Pair(date4, last4)
            ).forEach { (id, data) ->
                MBReleaseGroupTable.insert { it[MBReleaseGroupTable.id] = EntityID(id, MBReleaseGroupTable); it[title] = "Rel $id" }
                RecentReleaseTable.insert {
                    it[RecentReleaseTable.releaseId] = EntityID(id, MBReleaseGroupTable)
                    it[RecentReleaseTable.artistId] = artistId
                    it[RecentReleaseTable.artistName] = "Artist"
                    it[RecentReleaseTable.title] = "Rel $id"
                    it[RecentReleaseTable.releaseDate] = data.first
                    it[RecentReleaseTable.lastImageFetch] = data.second
                }
            }
        }

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.backfillMissingRecentReleaseImages()

        coVerify(exactly = 1) { spiedService.fetchReleaseGroupImage(any()) }
        coVerify { spiedService.fetchReleaseGroupImage(rel4) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshRecentRelease re-fetches a cached release and prunes stale providers`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Album" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.artistName] = "Test Artist"
                it[RecentReleaseTable.title] = "Album"
                it[RecentReleaseTable.releaseDate] = 1672531200000L
                it[RecentReleaseTable.links] = "[]"
            }
            RecentReleaseProviderTable.insert {
                it[RecentReleaseProviderTable.releaseId] = releaseId
                it[RecentReleaseProviderTable.provider] = "deezer"
                it[RecentReleaseProviderTable.externalId] = "stale-999"
                it[RecentReleaseProviderTable.rawUrl] = "https://deezer.com/album/999"
            }
        }

        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.HIGH) } returns emptyList()
        coEvery { musicBrainzService.fetchReleaseGroupById(releaseId, priority = HttpClientPriority.HIGH) } returns MusicBrainzReleaseGroup(
            id = releaseId,
            title = "Album",
            firstReleaseDate = "2023-01-01",
            relations = listOf(
                MusicBrainzRelation(
                    type = "spotify",
                    url = MusicBrainzRelationUrl(id = UUID.randomUUID(), resource = "https://spotify.com/album/123")
                )
            )
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns listOf("https://tidal.com/album/456")

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        val result = spiedService.refreshRecentRelease(releaseId)

        assertNotNull(result)
        assertTrue(result!!.links.contains("https://tidal.com/album/456"))
        assertTrue(result.links.contains("https://spotify.com/album/123"))

        transaction(database) {
            val providers = RecentReleaseProviderTable.selectAll()
                .where { RecentReleaseProviderTable.releaseId eq releaseId }
                .associate { it[RecentReleaseProviderTable.provider] to it[RecentReleaseProviderTable.externalId] }
            assertEquals(2, providers.size)
            assertEquals("123", providers["spotify"])
            assertEquals("456", providers["tidal"])
            assertNull(providers["deezer"])

            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertNotNull(row[RecentReleaseTable.lastUpdate])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshRecentRelease returns null when release cannot be resolved`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseId = UUID.randomUUID()

        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(releaseId, priority = HttpClientPriority.HIGH) } returns emptyList()

        val result = service.refreshRecentRelease(releaseId)
        assertNull(result)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshRecentRelease inserts a not-yet-cached release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Fresh Album" }
        }

        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(releaseId, priority = HttpClientPriority.HIGH) } returns listOf(
            MusicBrainzRelease(
                id = releaseId,
                artistCredit = listOf(MusicBrainzArtistCredit(artist = MusicBrainzArtist(id = mbId, name = "Test Artist")))
            )
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.HIGH) } returns emptyList()
        coEvery { musicBrainzService.fetchReleaseGroupById(releaseId, priority = HttpClientPriority.HIGH) } returns MusicBrainzReleaseGroup(
            id = releaseId,
            title = "Fresh Album",
            firstReleaseDate = "2023-01-01"
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        val result = spiedService.refreshRecentRelease(releaseId)

        assertNotNull(result)
        assertEquals("Fresh Album", result!!.title)

        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.singleOrNull()
            assertNotNull(row)
            assertEquals(artistId, row!![RecentReleaseTable.artistId].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases refreshes a released entry inside the refresh window`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val now = Clock.System.now().toEpochMilliseconds()
        val originalLastUpdate = now - 25.hours.inWholeMilliseconds

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Just Released" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.artistName] = "Test Artist"
                it[RecentReleaseTable.title] = "Just Released"
                it[RecentReleaseTable.releaseDate] = now - 2.days.inWholeMilliseconds
                it[RecentReleaseTable.links] = "[]"
                it[RecentReleaseTable.lastUpdate] = originalLastUpdate
            }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = releaseId, title = "Just Released", firstReleaseDate = "2023-01-01")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns listOf("https://tidal.com/album/456")

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertTrue(row[RecentReleaseTable.lastUpdate]!! > originalLastUpdate)
            val links = ApplicationScope.json.decodeFromString<List<String>>(row[RecentReleaseTable.links])
            assertTrue(links.contains("https://tidal.com/album/456"))
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases skips an upcoming entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val now = Clock.System.now().toEpochMilliseconds()
        val originalLastUpdate = now - 25.hours.inWholeMilliseconds

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Upcoming" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.artistName] = "Test Artist"
                it[RecentReleaseTable.title] = "Upcoming"
                it[RecentReleaseTable.releaseDate] = now + 10.days.inWholeMilliseconds
                it[RecentReleaseTable.links] = "[]"
                it[RecentReleaseTable.lastUpdate] = originalLastUpdate
            }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = releaseId, title = "Upcoming", firstReleaseDate = "2099-01-01")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        coVerify(exactly = 0) { musicBrainzService.fetchReleasesByReleaseGroup(releaseId, priority = HttpClientPriority.LOW) }
        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertEquals(originalLastUpdate, row[RecentReleaseTable.lastUpdate])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases skips a released entry still within cooldown`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val now = Clock.System.now().toEpochMilliseconds()
        val originalLastUpdate = now - 1.hours.inWholeMilliseconds

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Recently Refreshed" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.artistName] = "Test Artist"
                it[RecentReleaseTable.title] = "Recently Refreshed"
                it[RecentReleaseTable.releaseDate] = now - 2.days.inWholeMilliseconds
                it[RecentReleaseTable.links] = "[]"
                it[RecentReleaseTable.lastUpdate] = originalLastUpdate
            }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = releaseId, title = "Recently Refreshed", firstReleaseDate = "2023-01-01")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        coVerify(exactly = 0) { musicBrainzService.fetchReleasesByReleaseGroup(releaseId, priority = HttpClientPriority.LOW) }
        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertEquals(originalLastUpdate, row[RecentReleaseTable.lastUpdate])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfillMissingRecentReleaseImages skips releases of unfollowed artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
                it[RecentReleaseTable.imageId] = null
                it[RecentReleaseTable.lastImageFetch] = null
            }
        }

        val spiedService = spyk(service, recordPrivateCalls = true)
        spiedService.backfillMissingRecentReleaseImages()

        coVerify(exactly = 0) { spiedService.fetchReleaseGroupImage(any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfillMissingRecentReleaseImages with artistId only backfills that artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistA = UUID.randomUUID()
        val artistB = UUID.randomUUID()
        val releaseA = UUID.randomUUID()
        val releaseB = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistA; it[name] = "Artist A" }
            ArtistTable.insert { it[id] = artistB; it[name] = "Artist B" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistA }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistB }
            listOf(releaseA to artistA, releaseB to artistB).forEach { (relId, artId) ->
                MBReleaseGroupTable.insert { it[id] = relId; it[title] = "Rel $relId" }
                RecentReleaseTable.insert {
                    it[RecentReleaseTable.releaseId] = relId
                    it[RecentReleaseTable.artistId] = artId
                    it[RecentReleaseTable.title] = "Rel $relId"
                    it[RecentReleaseTable.imageId] = null
                    it[RecentReleaseTable.lastImageFetch] = null
                }
            }
        }

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.backfillMissingRecentReleaseImages(artistA)

        coVerify(exactly = 1) { spiedService.fetchReleaseGroupImage(any()) }
        coVerify { spiedService.fetchReleaseGroupImage(releaseA) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `followArtist triggers image backfill for the followed artist`(dialect: DbDialect) = runBlocking {
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

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.backfillMissingRecentReleaseImages(artistId) } returns Unit

        assertTrue(spiedService.followArtist(userId, mbId))

        coVerify(timeout = 5000) { spiedService.backfillMissingRecentReleaseImages(artistId) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases does not fetch images for unfollowed artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val mbId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Unfollowed Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Unfollowed Artist"; it[sortName] = "Unfollowed Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = releaseId, title = "New Album", firstReleaseDate = "2023-10-27")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        spiedService.fetchNewReleases()

        coVerify(exactly = 0) { spiedService.fetchReleaseGroupImage(any()) }
        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertNull(row[RecentReleaseTable.imageId])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unlinkUnfollowedRecentReleaseImages unlinks only CAA images of unfollowed artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val followedArtist = UUID.randomUUID()
        val unfollowedArtist = UUID.randomUUID()
        val caaRelease = UUID.randomUUID()
        val nonCaaRelease = UUID.randomUUID()
        val followedRelease = UUID.randomUUID()
        val caaImage = UUID.randomUUID()
        val nonCaaImage = UUID.randomUUID()
        val followedImage = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = followedArtist; it[name] = "Followed" }
            ArtistTable.insert { it[id] = unfollowedArtist; it[name] = "Unfollowed" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = followedArtist }

            ImageTable.insert { it[id] = caaImage; it[path] = "caa"; it[imageHash] = "h1"; it[origin] = "https://coverartarchive.org/release-group/$caaRelease/front" }
            ImageTable.insert { it[id] = nonCaaImage; it[path] = "tidal"; it[imageHash] = "h2"; it[origin] = "https://resources.tidal.com/images/cover.jpg" }
            ImageTable.insert { it[id] = followedImage; it[path] = "caa2"; it[imageHash] = "h3"; it[origin] = "https://coverartarchive.org/release-group/$followedRelease/front" }

            listOf(
                Triple(caaRelease, unfollowedArtist, caaImage),
                Triple(nonCaaRelease, unfollowedArtist, nonCaaImage),
                Triple(followedRelease, followedArtist, followedImage)
            ).forEach { (relId, artId, imgId) ->
                MBReleaseGroupTable.insert { it[id] = relId; it[title] = "Rel $relId" }
                RecentReleaseTable.insert {
                    it[RecentReleaseTable.releaseId] = relId
                    it[RecentReleaseTable.artistId] = artId
                    it[RecentReleaseTable.title] = "Rel $relId"
                    it[RecentReleaseTable.imageId] = EntityID(imgId, ImageTable)
                    it[RecentReleaseTable.lastImageFetch] = 1000L
                }
            }
        }

        val unlinked = service.unlinkUnfollowedRecentReleaseImages()
        assertEquals(1, unlinked)

        transaction(database) {
            val rows = RecentReleaseTable.selectAll().associate {
                it[RecentReleaseTable.releaseId].value to (it[RecentReleaseTable.imageId]?.value to it[RecentReleaseTable.lastImageFetch])
            }
            assertNull(rows[caaRelease]!!.first)
            assertNull(rows[caaRelease]!!.second)
            assertEquals(nonCaaImage, rows[nonCaaRelease]!!.first)
            assertEquals(1000L, rows[nonCaaRelease]!!.second)
            assertEquals(followedImage, rows[followedRelease]!!.first)
            assertEquals(1000L, rows[followedRelease]!!.second)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage returns null for unknown release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assertNull(service.getReleaseImage(UUID.randomUUID(), 0))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage serves the stored image when persisted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val imageId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            ImageTable.insert { it[id] = imageId; it[path] = "test"; it[imageHash] = "hash"; it[origin] = "test" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
                it[RecentReleaseTable.imageId] = EntityID(imageId, ImageTable)
            }
        }

        val expected = byteArrayOf(1, 2, 3)
        coEvery { imageService.getImageData(imageId, 250) } returns expected

        assertArrayEquals(expected, service.getReleaseImage(releaseId, 250))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage proxies CAA without persisting for unfollowed artists`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
            }
        }

        every { imageService.getCachedBytes(any()) } returns null

        val expected = byteArrayOf(4, 5, 6)
        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchCoverArtBytes(releaseId, "front") } returns expected

        assertArrayEquals(expected, spiedService.getReleaseImage(releaseId, 0))

        verify { imageService.setCachedBytes("releaseImage:$releaseId:0", expected, null) }
        coVerify(exactly = 0) { spiedService.fetchReleaseGroupImage(any()) }
        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertNull(row[RecentReleaseTable.imageId])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage caches a missing CAA image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
            }
        }

        every { imageService.getCachedBytes(any()) } returns null

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchCoverArtBytes(releaseId, "front-250") } returns null

        assertNull(spiedService.getReleaseImage(releaseId, 250))

        verify { imageService.setCachedBytes("releaseImage:$releaseId:missing", any(), 1.hours) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage short-circuits on negative cache`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
            }
        }

        every { imageService.getCachedBytes("releaseImage:$releaseId:missing") } returns byteArrayOf(0)

        val spiedService = spyk(service, recordPrivateCalls = true)
        assertNull(spiedService.getReleaseImage(releaseId, 0))

        coVerify(exactly = 0) { spiedService.fetchCoverArtBytes(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage lazily persists the image when the artist is followed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val imageId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
            ImageTable.insert { it[id] = imageId; it[path] = "test"; it[imageHash] = "hash"; it[origin] = "test" }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
            }
        }

        every { imageService.getCachedBytes(any()) } returns null

        val expected = byteArrayOf(7, 8, 9)
        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchCoverArtBytes(releaseId, "front") } returns expected
        coEvery { spiedService.fetchReleaseGroupImage(releaseId) } returns imageId

        assertArrayEquals(expected, spiedService.getReleaseImage(releaseId, 0))

        coVerify(timeout = 5000) { spiedService.fetchReleaseGroupImage(releaseId) }

        var persistedImageId: UUID? = null
        repeat(100) {
            persistedImageId = transaction(database) {
                RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()[RecentReleaseTable.imageId]?.value
            }
            if (persistedImageId != null) return@repeat
            Thread.sleep(50)
        }
        assertEquals(imageId, persistedImageId)
    }
}
