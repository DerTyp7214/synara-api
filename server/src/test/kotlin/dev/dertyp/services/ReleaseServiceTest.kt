package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.sha256
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.metadata.*
import dev.dertyp.services.release.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
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
import kotlin.test.assertFailsWith
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
    private lateinit var appleMusicReleaseService: AppleMusicReleaseService

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
                single { mockk<AppleMusicReleaseService>(relaxed = true) }
                single { ProviderLinkService() }
                single { ReleaseArtistService() }
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
        appleMusicReleaseService = get()

        coEvery { appleMusicReleaseService.linkedReleaseUrls(any()) } returns emptyList()
        coEvery { appleMusicReleaseService.findUnlinkedAppleRelease(any(), any(), any()) } returns null

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
                ArtistProviderTable,
                ProviderReleaseTable,
                ProviderLinkTable,
                RecentReleaseLinkTable,
                ProviderReleaseLinkTable,
                HiddenReleaseTable,
                ArtistSourceRuleTable,
                ReleaseArtistTable,
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

        val providers = linkKeysOf(releaseId)
        assertEquals(2, providers.size)
        assertEquals("123", providers["spotify"])
        assertEquals("456", providers["tidal"])
    }

    private fun linkKeysOf(releaseId: UUID): Map<String, String> = transaction(database) {
        RecentReleaseLinkTable
            .innerJoin(ProviderLinkTable)
            .selectAll()
            .where { RecentReleaseLinkTable.releaseId eq releaseId }
            .associate { it[ProviderLinkTable.provider] to it[ProviderLinkTable.externalId] }
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
        }
        attachLink(releaseId, "deezer", "stale-999", "https://deezer.com/album/999")

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

        val providers = linkKeysOf(releaseId)
        assertEquals(2, providers.size)
        assertEquals("123", providers["spotify"])
        assertEquals("456", providers["tidal"])
        assertNull(providers["deezer"])

        transaction(database) {
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

    private fun insertProviderRelease(
        rowId: UUID,
        owner: UUID,
        rowTitle: String,
        rowDate: Long?,
        rowUrl: String = "https://music.apple.com/album/$rowId",
        rowImageId: UUID? = null,
        rowArtworkUrl: String? = null,
        rowReleaseGroupId: UUID? = null,
        rowAlbumId: UUID? = null,
        rowSongId: UUID? = null,
        rowType: ReleaseType = ReleaseType.Album,
        rowCopyrightHolder: String? = null,
        rowRecordLabel: String? = null,
        rowCopyright: String? = null,
        rowIsrcRegistrants: String? = null,
        rowSuspect: Boolean = false,
        rowSuspectReason: String? = null
    ) {
        transaction(database) {
            ProviderReleaseTable.insert {
                it[ProviderReleaseTable.id] = rowId
                it[ProviderReleaseTable.provider] = "apple"
                it[ProviderReleaseTable.externalId] = rowId.toString()
                it[ProviderReleaseTable.artistId] = owner
                it[ProviderReleaseTable.artistName] = "Artist"
                it[ProviderReleaseTable.title] = rowTitle
                it[ProviderReleaseTable.releaseDate] = rowDate
                it[ProviderReleaseTable.type] = rowType
                it[ProviderReleaseTable.url] = rowUrl
                it[ProviderReleaseTable.artworkUrl] = rowArtworkUrl
                it[ProviderReleaseTable.imageId] = rowImageId?.let { value -> EntityID(value, ImageTable) }
                it[ProviderReleaseTable.releaseGroupId] = rowReleaseGroupId?.let { value -> EntityID(value, MBReleaseGroupTable) }
                it[ProviderReleaseTable.albumId] = rowAlbumId?.let { value -> EntityID(value, AlbumTable) }
                it[ProviderReleaseTable.songId] = rowSongId?.let { value -> EntityID(value, SongTable) }
                it[ProviderReleaseTable.copyrightHolder] = rowCopyrightHolder
                it[ProviderReleaseTable.recordLabel] = rowRecordLabel
                it[ProviderReleaseTable.copyright] = rowCopyright
                it[ProviderReleaseTable.isrcRegistrants] = rowIsrcRegistrants
                it[ProviderReleaseTable.suspect] = rowSuspect
                it[ProviderReleaseTable.suspectReason] = rowSuspectReason
            }
        }
    }

    private fun insertRecentRelease(rowId: UUID, owner: UUID, rowTitle: String, rowDate: Long?) {
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = rowId; it[title] = rowTitle }
            RecentReleaseTable.insert {
                it[releaseId] = rowId
                it[artistId] = owner
                it[artistName] = "Artist"
                it[title] = rowTitle
                it[releaseDate] = rowDate
            }
        }
    }

    private fun linkReleaseGroup(groupId: UUID, artistIds: List<UUID>) {
        transaction(database) {
            artistIds.forEach { linked ->
                ReleaseArtistTable.insert {
                    it[ReleaseArtistTable.releaseGroupId] = EntityID(groupId, MBReleaseGroupTable)
                    it[ReleaseArtistTable.artistId] = linked
                }
            }
        }
    }

    private fun linkProviderRelease(rowId: UUID, artistIds: List<UUID>) {
        transaction(database) {
            artistIds.forEach { linked ->
                ReleaseArtistTable.insert {
                    it[ReleaseArtistTable.providerReleaseId] = rowId
                    it[ReleaseArtistTable.artistId] = linked
                }
            }
        }
    }

    private fun linkedArtistIds(): Set<Pair<UUID?, UUID>> = transaction(database) {
        ReleaseArtistTable.selectAll()
            .map {
                (it[ReleaseArtistTable.releaseGroupId]?.value ?: it[ReleaseArtistTable.providerReleaseId]?.value) to
                        it[ReleaseArtistTable.artistId].value
            }
            .toSet()
    }

    private fun hiddenReleaseIds(): Set<UUID> = transaction(database) {
        HiddenReleaseTable.selectAll()
            .flatMap {
                listOfNotNull(
                    it[HiddenReleaseTable.releaseGroupId]?.value,
                    it[HiddenReleaseTable.providerReleaseId]?.value
                )
            }
            .toSet()
    }

    private fun sourceRules(): List<SourceRule> = transaction(database) {
        ArtistSourceRuleTable.selectAll().map {
            SourceRule(
                artistId = it[ArtistSourceRuleTable.artistId].toString(),
                provider = it[ArtistSourceRuleTable.provider],
                kind = it[ArtistSourceRuleTable.kind],
                value = it[ArtistSourceRuleTable.value],
                rule = it[ArtistSourceRuleTable.rule]
            )
        }
    }

    private data class SourceRule(
        val artistId: String,
        val provider: String,
        val kind: ArtistSourceRuleKind,
        val value: String,
        val rule: ArtistSourceRulePolarity
    )

    private fun insertLink(linkProvider: String, linkExternalId: String, linkUrl: String): UUID =
        transaction(database) {
            ProviderLinkTable.insertAndGetId {
                it[ProviderLinkTable.provider] = linkProvider
                it[ProviderLinkTable.externalId] = linkExternalId
                it[ProviderLinkTable.rawUrl] = linkUrl
            }.value
        }

    private fun attachLink(releaseId: UUID, linkProvider: String, linkExternalId: String, linkUrl: String): UUID {
        val linkId = insertLink(linkProvider, linkExternalId, linkUrl)
        transaction(database) {
            RecentReleaseLinkTable.insert {
                it[RecentReleaseLinkTable.releaseId] = releaseId
                it[RecentReleaseLinkTable.linkId] = linkId
            }
        }
        return linkId
    }

    private fun attachProviderLink(rowId: UUID, linkProvider: String, linkExternalId: String, linkUrl: String): UUID {
        val linkId = insertLink(linkProvider, linkExternalId, linkUrl)
        transaction(database) {
            ProviderReleaseLinkTable.insert {
                it[ProviderReleaseLinkTable.providerReleaseId] = rowId
                it[ProviderReleaseLinkTable.linkId] = linkId
            }
        }
        return linkId
    }

    private fun seedFollowedArtist(userId: UUID, artistId: UUID) {
        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases merges both sources in date order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbRelease = UUID.randomUUID()
        val newestProviderRelease = UUID.randomUUID()
        val oldestProviderRelease = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = mbRelease; it[title] = "MB Release" }
            RecentReleaseTable.insert {
                it[releaseId] = mbRelease
                it[this.artistId] = artistId
                it[artistName] = "Artist"
                it[title] = "MB Release"
                it[releaseDate] = 2000L
            }
        }
        insertProviderRelease(newestProviderRelease, artistId, "Newest Apple Release", 3000L)
        insertProviderRelease(oldestProviderRelease, artistId, "Oldest Apple Release", 1000L)

        val result = service.getRecentReleases(userId)

        assertEquals(3, result.total)
        assertEquals(
            listOf("Newest Apple Release", "MB Release", "Oldest Apple Release"),
            result.data.map { it.title }
        )
        assertEquals(ReleaseSource.Apple, result.data[0].source)
        assertEquals(ReleaseSource.MusicBrainz, result.data[1].source)
        assertEquals(listOf("https://music.apple.com/album/$newestProviderRelease"), result.data[0].links)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases paginates across both sources`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            for (i in 1..5) {
                val relId = UUID.randomUUID()
                MBReleaseGroupTable.insert { it[id] = relId; it[title] = "MB $i" }
                RecentReleaseTable.insert {
                    it[releaseId] = relId
                    it[this.artistId] = artistId
                    it[artistName] = "Artist"
                    it[title] = "MB $i"
                    it[releaseDate] = (i * 2).toLong()
                }
            }
        }
        for (i in 1..5) {
            insertProviderRelease(UUID.randomUUID(), artistId, "Apple $i", (i * 2 - 1).toLong())
        }

        val page0 = service.getRecentReleases(userId, page = 0, pageSize = 3)
        assertEquals(10, page0.total)
        assertTrue(page0.hasNextPage)
        assertEquals(listOf("MB 5", "Apple 5", "MB 4"), page0.data.map { it.title })

        val page1 = service.getRecentReleases(userId, page = 1, pageSize = 3)
        assertEquals(listOf("Apple 4", "MB 3", "Apple 3"), page1.data.map { it.title })

        val page3 = service.getRecentReleases(userId, page = 3, pageSize = 3)
        assertEquals(listOf("Apple 1"), page3.data.map { it.title })
        assertFalse(page3.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases marks future releases as upcoming`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbRelease = UUID.randomUUID()
        val futureProviderRelease = UUID.randomUUID()
        val pastProviderRelease = UUID.randomUUID()
        val now = Clock.System.now().toEpochMilliseconds()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = mbRelease; it[title] = "MB Upcoming" }
            RecentReleaseTable.insert {
                it[releaseId] = mbRelease
                it[this.artistId] = artistId
                it[artistName] = "Artist"
                it[title] = "MB Upcoming"
                it[releaseDate] = now + 20.days.inWholeMilliseconds
            }
        }
        insertProviderRelease(futureProviderRelease, artistId, "Apple Upcoming", now + 10.days.inWholeMilliseconds)
        insertProviderRelease(pastProviderRelease, artistId, "Apple Released", now - 10.days.inWholeMilliseconds)

        val byTitle = service.getRecentReleases(userId).data.associateBy { it.title }

        assertTrue(byTitle.getValue("MB Upcoming").upcoming)
        assertEquals(ReleaseSource.MusicBrainz, byTitle.getValue("MB Upcoming").source)
        assertTrue(byTitle.getValue("Apple Upcoming").upcoming)
        assertFalse(byTitle.getValue("Apple Released").upcoming)
        assertEquals(ReleaseSource.Apple, byTitle.getValue("Apple Released").source)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases hides provider rows that are matched or in the library`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val groupId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        var libraryAlbumId: UUID? = null
        var librarySongId: UUID? = null
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = groupId; it[title] = "Matched Group" }
            val albumEntity = AlbumTable.insert { it[name] = "Owned Album" }[AlbumTable.id]
            libraryAlbumId = albumEntity.value
            librarySongId = SongTable.insert {
                it[title] = "Owned Song"
                it[albumId] = albumEntity
            }[SongTable.id].value
        }

        insertProviderRelease(UUID.randomUUID(), artistId, "Visible", 5000L)
        insertProviderRelease(UUID.randomUUID(), artistId, "Matched", 4000L, rowReleaseGroupId = groupId)
        insertProviderRelease(UUID.randomUUID(), artistId, "Owned Album", 3000L, rowAlbumId = libraryAlbumId)
        insertProviderRelease(UUID.randomUUID(), artistId, "Owned Song", 2000L, rowSongId = librarySongId)
        insertProviderRelease(UUID.randomUUID(), artistId, "Undated", null)

        val result = service.getRecentReleases(userId)

        assertEquals(1, result.total)
        assertEquals(listOf("Visible"), result.data.map { it.title })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getArtistRecentReleases returns provider rows for an artist without a MusicBrainz id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val groupId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            MBReleaseGroupTable.insert { it[id] = groupId; it[title] = "Matched Group" }
        }

        var libraryAlbumId: UUID? = null
        transaction(database) {
            libraryAlbumId = AlbumTable.insert { it[name] = "Owned Album" }[AlbumTable.id].value
        }

        insertProviderRelease(UUID.randomUUID(), artistId, "Apple Discography", 3000L)
        insertProviderRelease(UUID.randomUUID(), artistId, "Owned Album", 2000L, rowAlbumId = libraryAlbumId)
        insertProviderRelease(UUID.randomUUID(), artistId, "Matched", 1000L, rowReleaseGroupId = groupId)

        val result = service.getArtistRecentReleases(artistId)

        assertEquals(2, result.total)
        assertEquals(listOf("Apple Discography", "Owned Album"), result.data.map { it.title })
        assertEquals(libraryAlbumId, result.data[1].albumId)
        assertEquals(listOf(artistId), result.data[0].artistIds)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases returns releases linked to a followed collaborator`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val collaboratorId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val providerReleaseId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = ownerId; it[name] = "Owner" }
            ArtistTable.insert { it[id] = collaboratorId; it[name] = "Collaborator" }
            FollowedArtistTable.insert { it[this.userId] = userId; it[artistId] = collaboratorId }
        }
        insertRecentRelease(groupId, ownerId, "MB Collab", 2000L)
        insertProviderRelease(providerReleaseId, ownerId, "Apple Collab", 1000L)
        linkReleaseGroup(groupId, listOf(ownerId, collaboratorId))
        linkProviderRelease(providerReleaseId, listOf(ownerId, collaboratorId))

        val result = service.getRecentReleases(userId)

        assertEquals(listOf("MB Collab", "Apple Collab"), result.data.map { it.title })
        result.data.forEach { release ->
            assertEquals(ownerId, release.artistIds.first())
            assertEquals(setOf(ownerId, collaboratorId), release.artistIds.toSet())
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getArtistRecentReleases returns releases linked to the artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val ownerId = UUID.randomUUID()
        val collaboratorId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val providerReleaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = ownerId; it[name] = "Owner" }
            ArtistTable.insert { it[id] = collaboratorId; it[name] = "Collaborator" }
        }
        insertRecentRelease(groupId, ownerId, "MB Collab", 2000L)
        insertProviderRelease(providerReleaseId, ownerId, "Apple Collab", 1000L)
        linkReleaseGroup(groupId, listOf(ownerId, collaboratorId))
        linkProviderRelease(providerReleaseId, listOf(ownerId, collaboratorId))

        val result = service.getArtistRecentReleases(collaboratorId)

        assertEquals(2, result.total)
        assertEquals(listOf("MB Collab", "Apple Collab"), result.data.map { it.title })
        assertEquals(ownerId, result.data[0].artistId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases links the processing artist and every credited artist`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val collaboratorId = UUID.randomUUID()
        val ownerMbId = UUID.randomUUID()
        val collaboratorMbId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val mbReleaseId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = ownerId; it[name] = "Owner" }
            ArtistTable.insert { it[id] = collaboratorId; it[name] = "Collaborator" }
            MBArtistTable.insert { it[id] = ownerMbId; it[name] = "Owner"; it[sortName] = "Owner" }
            MBArtistTable.insert { it[id] = collaboratorMbId; it[name] = "Collaborator"; it[sortName] = "Collaborator" }
            ArtistMusicBrainzTable.insert { it[artistId] = ownerId; it[musicBrainzId] = ownerMbId }
            ArtistMusicBrainzTable.insert { it[artistId] = collaboratorId; it[musicBrainzId] = collaboratorMbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[artistId] = ownerId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(ownerMbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = groupId, title = "Collab Album", firstReleaseDate = "2023-10-27")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(ownerMbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzRelease(
                id = mbReleaseId,
                title = "Collab Album",
                releaseGroup = MusicBrainzReleaseGroup(id = groupId, title = "Collab Album"),
                artistCredit = listOf(
                    MusicBrainzArtistCredit(artist = MusicBrainzArtist(id = ownerMbId, name = "Owner")),
                    MusicBrainzArtistCredit(artist = MusicBrainzArtist(id = collaboratorMbId, name = "Collaborator"))
                )
            )
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        assertEquals(setOf(groupId to ownerId, groupId to collaboratorId), linkedArtistIds())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases links a stored group of another artist without processing it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val collaboratorId = UUID.randomUUID()
        val collaboratorMbId = UUID.randomUUID()
        val groupId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = ownerId; it[name] = "Owner" }
            ArtistTable.insert { it[id] = collaboratorId; it[name] = "Collaborator" }
            MBArtistTable.insert { it[id] = collaboratorMbId; it[name] = "Collaborator"; it[sortName] = "Collaborator" }
            ArtistMusicBrainzTable.insert { it[artistId] = collaboratorId; it[musicBrainzId] = collaboratorMbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[artistId] = collaboratorId }
        }
        insertRecentRelease(groupId, ownerId, "Stored Album", 1000L)

        coEvery { musicBrainzService.fetchReleaseGroups(collaboratorMbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = groupId, title = "Stored Album", firstReleaseDate = "1970-01-01")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(collaboratorMbId, priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        assertEquals(setOf(groupId to collaboratorId), linkedArtistIds())
        coVerify(exactly = 0) { musicBrainzService.fetchReleasesByReleaseGroup(groupId, any()) }

        val owner = transaction(database) {
            RecentReleaseTable.selectAll().single()[RecentReleaseTable.artistId].value
        }
        assertEquals(ownerId, owner)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage serves the stored provider release image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val imageId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
            ImageTable.insert { it[id] = imageId; it[path] = "test"; it[imageHash] = "hash"; it[origin] = "test" }
        }
        insertProviderRelease(releaseId, artistId, "Apple Release", 1000L, rowImageId = imageId)

        val expected = byteArrayOf(1, 2, 3)
        coEvery { imageService.getImageData(imageId, 250) } returns expected

        assertArrayEquals(expected, service.getReleaseImage(releaseId, 250))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage proxies and caches the provider artwork`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/cover/1200x1200bb.jpg"

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
        }
        insertProviderRelease(releaseId, artistId, "Apple Release", 1000L, rowArtworkUrl = artworkUrl)

        every { imageService.getCachedBytes(any()) } returns null

        val expected = byteArrayOf(4, 5, 6)
        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchProviderArtworkBytes(any()) } returns expected

        assertArrayEquals(expected, spiedService.getReleaseImage(releaseId, 0))

        verify { imageService.setCachedBytes("releaseImage:$releaseId:0", expected, null) }
        coVerify(exactly = 0) { spiedService.fetchCoverArtBytes(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getReleaseImage caches a missing provider artwork`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
        }
        insertProviderRelease(
            releaseId,
            artistId,
            "Apple Release",
            1000L,
            rowArtworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/cover/1200x1200bb.jpg"
        )

        every { imageService.getCachedBytes(any()) } returns null

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchProviderArtworkBytes(any()) } returns null

        assertNull(spiedService.getReleaseImage(releaseId, 250))

        verify { imageService.setCachedBytes("releaseImage:$releaseId:missing", any(), 1.hours) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshRecentRelease refreshes a provider release through the Apple Music service`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
        }
        insertProviderRelease(releaseId, artistId, "Apple Only", 1000L)

        coEvery { appleMusicReleaseService.fetchArtistReleases(artistId) } returns 1

        val result = service.refreshRecentRelease(releaseId)

        assertNotNull(result)
        assertEquals("Apple Only", result!!.title)
        assertEquals(ReleaseSource.Apple, result.source)
        coVerify { appleMusicReleaseService.fetchArtistReleases(artistId) }
        coVerify(exactly = 0) { musicBrainzService.fetchReleasesByReleaseGroup(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `followArtist triggers the Apple Music release fetch`(dialect: DbDialect) = runBlocking {
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

        assertTrue(service.followArtist(userId, mbId))

        verify(timeout = 5000) { appleMusicReleaseService.fetchArtistReleasesAsync(artistId) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases reads MusicBrainz links from the mapping tables`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "MB Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.artistName] = "Artist"
                it[RecentReleaseTable.title] = "MB Release"
                it[RecentReleaseTable.releaseDate] = 2000L
                it[RecentReleaseTable.links] = "[]"
            }
        }
        attachLink(releaseId, "tidal", "456", "https://tidal.com/album/456")
        attachLink(releaseId, "apple", "789", "https://music.apple.com/album/789")

        val result = service.getRecentReleases(userId)

        assertEquals(1, result.data.size)
        assertEquals(
            listOf("https://music.apple.com/album/789", "https://tidal.com/album/456"),
            result.data[0].links
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases exposes the Apple url first and the attached urls`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val rowId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertProviderRelease(rowId, artistId, "Apple Release", 1000L)
        attachProviderLink(rowId, "tidal", "456", "https://tidal.com/album/456")
        attachProviderLink(rowId, "apple", rowId.toString(), "https://music.apple.com/album/$rowId")

        val result = service.getRecentReleases(userId)

        assertEquals(1, result.data.size)
        assertEquals(
            listOf("https://music.apple.com/album/$rowId", "https://tidal.com/album/456"),
            result.data[0].links
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases merges a matched Apple release and skips the Apple search`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val rowId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }
        insertProviderRelease(rowId, artistId, "New Album", 1000L)
        attachProviderLink(rowId, "apple", "999", "https://music.apple.com/album/999")

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = groupId, title = "New Album", firstReleaseDate = "2023-10-27")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { appleMusicReleaseService.findUnlinkedAppleRelease(any(), any(), any()) } returns rowId

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        coVerify { appleMusicReleaseService.mergeIntoReleaseGroup(rowId, groupId) }
        coVerify(exactly = 0) { appleMusicService.searchAlbums(any(), any(), any(), priority = HttpClientPriority.LOW) }

        val providers = linkKeysOf(groupId)
        assertEquals("999", providers["apple"])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases hands barcodes and link keys to the Apple matcher`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val mbReleaseId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(
                id = groupId,
                title = "New Album",
                firstReleaseDate = "2023-10-27",
                relations = listOf(
                    MusicBrainzRelation(
                        type = "free streaming",
                        url = MusicBrainzRelationUrl(id = UUID.randomUUID(), resource = "https://tidal.com/album/456")
                    )
                )
            )
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(groupId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzRelease(id = mbReleaseId, barcode = "0123456789012")
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()

        val appleAlbumIds = slot<Set<String>>()
        val barcodes = slot<Set<String>>()
        val linkKeys = slot<Set<Pair<String, String>>>()
        coEvery {
            appleMusicReleaseService.findUnlinkedAppleRelease(
                capture(appleAlbumIds),
                capture(barcodes),
                capture(linkKeys)
            )
        } returns null

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        assertEquals(setOf("0123456789012"), barcodes.captured)
        assertTrue(linkKeys.captured.contains("tidal" to "456"))
        assertTrue(appleAlbumIds.captured.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshRecentRelease keeps the links of a merged Apple release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        transaction(database) {
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
        }
        attachLink(releaseId, "deezer", "stale-999", "https://deezer.com/album/999")

        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.HIGH) } returns emptyList()
        coEvery { musicBrainzService.fetchReleaseGroupById(releaseId, priority = HttpClientPriority.HIGH) } returns MusicBrainzReleaseGroup(
            id = releaseId,
            title = "Album",
            firstReleaseDate = "2023-01-01"
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { appleMusicReleaseService.linkedReleaseUrls(releaseId) } returns listOf("https://music.apple.com/album/999")

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        val result = spiedService.refreshRecentRelease(releaseId)

        assertNotNull(result)
        assertEquals(listOf("https://music.apple.com/album/999"), result!!.links)

        val providers = linkKeysOf(releaseId)
        assertEquals("999", providers["apple"])
        assertNull(providers["deezer"])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases merges the Apple release found by the title search`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val rowId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            MBArtistTable.insert { it[id] = mbId; it[name] = "Test Artist"; it[sortName] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = groupId, title = "The Title", firstReleaseDate = "2023-01-01", primaryType = "Single")
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW) } returns emptyList()
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()
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
        coEvery {
            appleMusicReleaseService.findUnlinkedAppleRelease(setOf("apple-1"), emptySet(), emptySet())
        } returns rowId

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        coVerify { appleMusicReleaseService.mergeIntoReleaseGroup(rowId, groupId) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unlinkUnfollowedRecentReleaseImages also unlinks Apple artwork`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val imageId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Unfollowed" }
            ImageTable.insert {
                it[id] = imageId
                it[path] = "apple"
                it[imageHash] = "h1"
                it[origin] = "https://is1-ssl.mzstatic.com/image/thumb/cover/1200x1200bb.jpg"
            }
            MBReleaseGroupTable.insert { it[id] = releaseId; it[title] = "Release" }
            RecentReleaseTable.insert {
                it[RecentReleaseTable.releaseId] = releaseId
                it[RecentReleaseTable.artistId] = artistId
                it[RecentReleaseTable.title] = "Release"
                it[RecentReleaseTable.imageId] = EntityID(imageId, ImageTable)
                it[RecentReleaseTable.lastImageFetch] = 1000L
            }
        }

        assertEquals(1, service.unlinkUnfollowedRecentReleaseImages())

        transaction(database) {
            val row = RecentReleaseTable.selectAll().where { RecentReleaseTable.releaseId eq releaseId }.single()
            assertNull(row[RecentReleaseTable.imageId])
            assertNull(row[RecentReleaseTable.lastImageFetch])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchReleaseGroupImage returns null and stores nothing for a Cover Art Archive 404 page`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseGroupId = UUID.randomUUID()
        val notFoundPage = "<!doctype html><html lang=en><title>404 Not Found</title><h1>Not Found</h1><p>No cover art found for release group $releaseGroupId</p>"

        val mockHttpClient = HttpClient(MockEngine { _ ->
            respond(
                content = notFoundPage,
                status = HttpStatusCode.NotFound,
                headers = headersOf("Content-Type", ContentType.Text.Html.toString())
            )
        }) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        val result = service.fetchReleaseGroupImage(releaseGroupId)

        assertNull(result)
        coVerify(exactly = 0) { imageService.createBatch(any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchReleaseGroupImage persists a real image`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseGroupId = UUID.randomUUID()
        val expectedId = UUID.randomUUID()
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00
        )

        val mockHttpClient = HttpClient(MockEngine { _ ->
            respond(
                content = jpeg,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Image.JPEG.toString())
            )
        }) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        coEvery { imageService.createBatch(any()) } returns mapOf(jpeg.sha256() to expectedId)

        val result = service.fetchReleaseGroupImage(releaseGroupId)

        assertEquals(expectedId, result)
        coVerify {
            imageService.createBatch(match {
                it.size == 1 &&
                    it.single().data.contentEquals(jpeg) &&
                    it.single().origin == "https://coverartarchive.org/release-group/$releaseGroupId/front"
            })
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchReleaseGroupImage returns null and stores nothing for a non-image 200 response`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val releaseGroupId = UUID.randomUUID()
        val notFoundPage = "<!doctype html><html lang=en><title>404 Not Found</title><h1>Not Found</h1><p>No cover art found for release group $releaseGroupId</p>"

        val mockHttpClient = HttpClient(MockEngine { _ ->
            respond(
                content = notFoundPage,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Text.Html.toString())
            )
        }) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        val result = service.fetchReleaseGroupImage(releaseGroupId)

        assertNull(result)
        coVerify(exactly = 0) { imageService.createBatch(any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden hides a MusicBrainz entry from the feed and the artist feed shows it only with includeHidden`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertRecentRelease(releaseId, artistId, "MB Release", 2000L)

        assertEquals(1, service.setReleaseHidden(userId, releaseId, true))

        val feed = service.getRecentReleases(userId)
        assertEquals(0, feed.total)
        assertTrue(feed.data.isEmpty())

        val artistFeed = service.getArtistRecentReleases(artistId)
        assertEquals(0, artistFeed.total)
        assertTrue(artistFeed.data.isEmpty())

        val withHidden = service.getArtistRecentReleases(artistId, includeHidden = true)
        assertEquals(1, withHidden.total)
        assertEquals("MB Release", withHidden.data.single().title)
        assertTrue(withHidden.data.single().hidden)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden returns the number of changed entries and is idempotent`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertProviderRelease(releaseId, artistId, "Apple Release", 1000L)

        assertEquals(1, service.setReleaseHidden(userId, releaseId, true))
        assertEquals(0, service.setReleaseHidden(userId, releaseId, true))
        assertEquals(1, service.setReleaseHidden(userId, releaseId, false))
        assertEquals(0, service.setReleaseHidden(userId, releaseId, false))
        assertTrue(hiddenReleaseIds().isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden with includeRelated hides the siblings sharing the copyright holder and records a block`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val releaseA = UUID.randomUUID()
        val releaseB = UUID.randomUUID()
        val releaseC = UUID.randomUUID()
        val releaseD = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            MBReleaseGroupTable.insert { it[id] = groupId; it[title] = "Matched Group" }
        }
        insertProviderRelease(releaseA, artistId, "A", 4000L, rowCopyrightHolder = "X")
        insertProviderRelease(releaseB, artistId, "B", 3000L, rowCopyrightHolder = "X")
        insertProviderRelease(releaseC, artistId, "C", 2000L, rowCopyrightHolder = "Y")
        insertProviderRelease(releaseD, artistId, "D", 1000L, rowReleaseGroupId = groupId, rowCopyrightHolder = "X")

        assertEquals(2, service.setReleaseHidden(userId, releaseA, true, includeRelated = true))

        val feed = service.getRecentReleases(userId)
        assertEquals(listOf("C"), feed.data.map { it.title })
        assertEquals(setOf(releaseA, releaseB), hiddenReleaseIds())

        val rule = sourceRules().single()
        assertEquals(artistId.toString(), rule.artistId)
        assertEquals("apple", rule.provider)
        assertEquals(ArtistSourceRuleKind.COPYRIGHT_HOLDER, rule.kind)
        assertEquals("X", rule.value)
        assertEquals(ArtistSourceRulePolarity.BLOCK, rule.rule)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden with includeRelated falls back to the record label when no holder is known`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseA = UUID.randomUUID()
        val releaseB = UUID.randomUUID()
        val releaseC = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertProviderRelease(releaseA, artistId, "A", 3000L, rowRecordLabel = "Label One")
        insertProviderRelease(releaseB, artistId, "B", 2000L, rowRecordLabel = "Label One")
        insertProviderRelease(releaseC, artistId, "C", 1000L, rowRecordLabel = "Label Two")

        assertEquals(2, service.setReleaseHidden(userId, releaseA, true, includeRelated = true))

        assertEquals(setOf(releaseA, releaseB), hiddenReleaseIds())
        assertEquals(listOf("C"), service.getRecentReleases(userId).data.map { it.title })

        val rule = sourceRules().single()
        assertEquals(ArtistSourceRuleKind.LABEL, rule.kind)
        assertEquals("Label One", rule.value)
        assertEquals(ArtistSourceRulePolarity.BLOCK, rule.rule)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden with includeRelated on a MusicBrainz entry hides only that entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val otherReleaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertRecentRelease(releaseId, artistId, "MB One", 2000L)
        insertRecentRelease(otherReleaseId, artistId, "MB Two", 1000L)

        assertEquals(1, service.setReleaseHidden(userId, releaseId, true, includeRelated = true))

        assertEquals(setOf(releaseId), hiddenReleaseIds())
        assertTrue(sourceRules().isEmpty())
        assertEquals(listOf("MB Two"), service.getRecentReleases(userId).data.map { it.title })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden unhide with includeRelated removes the block and shows the siblings again`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseA = UUID.randomUUID()
        val releaseB = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertProviderRelease(releaseA, artistId, "A", 2000L, rowCopyrightHolder = "X")
        insertProviderRelease(releaseB, artistId, "B", 1000L, rowCopyrightHolder = "X")

        assertEquals(2, service.setReleaseHidden(userId, releaseA, true, includeRelated = true))
        assertEquals(2, service.setReleaseHidden(userId, releaseA, false, includeRelated = true))

        assertTrue(hiddenReleaseIds().isEmpty())
        assertTrue(sourceRules().isEmpty())
        assertEquals(listOf("A", "B"), service.getRecentReleases(userId).data.map { it.title })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setReleaseHidden throws for an unknown release id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)

        assertFailsWith<IllegalArgumentException> { service.setReleaseHidden(userId, UUID.randomUUID(), true) }
        Unit
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `confirmRelease clears the suspect flag and records trust rules`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertProviderRelease(
            releaseId,
            artistId,
            "Suspect Release",
            1000L,
            rowCopyrightHolder = "h",
            rowRecordLabel = "L",
            rowIsrcRegistrants = "ABC12,XYZ99",
            rowSuspect = true,
            rowSuspectReason = "unknown label"
        )

        val confirmed = service.confirmRelease(userId, releaseId)

        assertFalse(confirmed.suspect)
        assertNull(confirmed.suspectReason)
        assertEquals("L", confirmed.recordLabel)

        val rules = sourceRules()
        assertEquals(4, rules.size)
        assertTrue(rules.all { it.rule == ArtistSourceRulePolarity.TRUST })
        assertTrue(rules.all { it.artistId == artistId.toString() && it.provider == "apple" })
        assertEquals(
            setOf(
                ArtistSourceRuleKind.COPYRIGHT_HOLDER to "h",
                ArtistSourceRuleKind.LABEL to "L",
                ArtistSourceRuleKind.ISRC_REGISTRANT to "ABC12",
                ArtistSourceRuleKind.ISRC_REGISTRANT to "XYZ99"
            ),
            rules.map { it.kind to it.value }.toSet()
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `confirmRelease turns a block for the same value into a trust rule`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val blockedId = UUID.randomUUID()
        val confirmedId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertProviderRelease(blockedId, artistId, "Blocked", 2000L, rowCopyrightHolder = "X")
        insertProviderRelease(confirmedId, artistId, "Confirmed", 1000L, rowCopyrightHolder = "X")

        assertEquals(2, service.setReleaseHidden(userId, blockedId, true, includeRelated = true))
        assertEquals(ArtistSourceRulePolarity.BLOCK, sourceRules().single().rule)

        service.confirmRelease(userId, confirmedId)

        val rule = sourceRules().single()
        assertEquals(ArtistSourceRuleKind.COPYRIGHT_HOLDER, rule.kind)
        assertEquals("X", rule.value)
        assertEquals(ArtistSourceRulePolarity.TRUST, rule.rule)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `confirmRelease throws for a MusicBrainz or unknown id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        insertRecentRelease(releaseId, artistId, "MB Release", 1000L)

        assertFailsWith<IllegalArgumentException> { service.confirmRelease(userId, releaseId) }
        assertFailsWith<IllegalArgumentException> { service.confirmRelease(userId, UUID.randomUUID()) }
        Unit
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getArtistRecentReleases exposes suspect, suspectReason, recordLabel and copyright of provider rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        val providerId = UUID.randomUUID()
        val mbId = UUID.randomUUID()

        transaction(database) {
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
        }
        insertProviderRelease(
            providerId,
            artistId,
            "Apple Release",
            2000L,
            rowCopyrightHolder = "h",
            rowRecordLabel = "Label One",
            rowCopyright = "2024 Label One",
            rowSuspect = true,
            rowSuspectReason = "unknown label"
        )
        insertRecentRelease(mbId, artistId, "MB Release", 1000L)

        val byTitle = service.getArtistRecentReleases(artistId).data.associateBy { it.title }

        val apple = byTitle.getValue("Apple Release")
        assertTrue(apple.suspect)
        assertEquals("unknown label", apple.suspectReason)
        assertEquals("Label One", apple.recordLabel)
        assertEquals("2024 Label One", apple.copyright)
        assertFalse(apple.hidden)

        val musicBrainz = byTitle.getValue("MB Release")
        assertFalse(musicBrainz.suspect)
        assertNull(musicBrainz.suspectReason)
        assertNull(musicBrainz.recordLabel)
        assertNull(musicBrainz.copyright)
        assertFalse(musicBrainz.hidden)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getRecentReleases pagination excludes hidden entries from the total`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        val releaseIds = (1..5).map { UUID.randomUUID() }
        releaseIds.forEachIndexed { index, releaseId ->
            insertRecentRelease(releaseId, artistId, "MB ${index + 1}", (index + 1) * 1000L)
        }

        assertEquals(1, service.setReleaseHidden(userId, releaseIds[0], true))
        assertEquals(1, service.setReleaseHidden(userId, releaseIds[1], true))

        val page0 = service.getRecentReleases(userId, page = 0, pageSize = 2)
        assertEquals(3, page0.total)
        assertTrue(page0.hasNextPage)
        assertEquals(listOf("MB 5", "MB 4"), page0.data.map { it.title })

        val page1 = service.getRecentReleases(userId, page = 1, pageSize = 2)
        assertEquals(listOf("MB 3"), page1.data.map { it.title })
        assertFalse(page1.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `trackReleaseGroup stores a release group that is not on the radar`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val groupId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            MBArtistTable.insert { it[id] = mbId; it[name] = "Artist"; it[sortName] = "Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
        }

        coEvery { musicBrainzService.fetchReleasesByArtist(mbId, any()) } returns emptyList()
        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(groupId, any()) } returns emptyList()
        coEvery { musicBrainzService.fetchReleaseGroupById(groupId, any()) } returns MusicBrainzReleaseGroup(
            id = groupId,
            title = "Deep Catalog Album",
            primaryType = "Album",
            firstReleaseDate = "2023-01-01"
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        assertTrue(spiedService.trackReleaseGroup(groupId, artistId))

        transaction(database) {
            val row = RecentReleaseTable.selectAll()
                .where { RecentReleaseTable.releaseId eq groupId }
                .singleOrNull()
            assertNotNull(row)
            assertEquals("Deep Catalog Album", row!![RecentReleaseTable.title])
            assertEquals(artistId, row[RecentReleaseTable.artistId].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `trackReleaseGroup returns false for a known group and an artist without a MusicBrainz id`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            mockkObject(MetadataService.Companion)
            every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
            every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

            val userId = UUID.randomUUID()
            val artistId = UUID.randomUUID()
            val mbId = UUID.randomUUID()
            val groupId = UUID.randomUUID()

            seedFollowedArtist(userId, artistId)
            assertFalse(service.trackReleaseGroup(groupId, artistId))

            transaction(database) {
                MBArtistTable.insert { it[id] = mbId; it[name] = "Artist"; it[sortName] = "Artist" }
                ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            }
            insertRecentRelease(groupId, artistId, "Known Album", null)

            coEvery { musicBrainzService.fetchReleasesByArtist(mbId, any()) } returns emptyList()
            coEvery { musicBrainzService.fetchReleaseGroupById(groupId, any()) } returns MusicBrainzReleaseGroup(
                id = groupId,
                title = "Known Album",
                primaryType = "Album",
                firstReleaseDate = "2023-01-01"
            )

            assertFalse(service.trackReleaseGroup(groupId, artistId))
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fetchNewReleases caches the releases of the processed group`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        mockkObject(MetadataService.Companion)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any()) } returns appleMusicService

        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        val releaseId = UUID.randomUUID()
        val groupReleaseId = UUID.randomUUID()

        seedFollowedArtist(userId, artistId)
        transaction(database) {
            MBArtistTable.insert { it[id] = mbId; it[name] = "Artist"; it[sortName] = "Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzReleaseGroup(id = releaseId, title = "Cached Album", firstReleaseDate = "2023-10-27")
        )
        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(releaseId, priority = HttpClientPriority.LOW) } returns listOf(
            MusicBrainzRelease(
                id = groupReleaseId,
                title = "Cached Album",
                barcode = "602445790000",
                releaseGroup = MusicBrainzReleaseGroup(id = releaseId, title = "Cached Album")
            )
        )
        coEvery { linkResolverService.batchResolve(any(), priority = HttpClientPriority.LOW) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null

        spiedService.fetchNewReleases()

        transaction(database) {
            val row = MBReleaseTable.selectAll()
                .where { MBReleaseTable.id eq groupReleaseId }
                .singleOrNull()
            assertNotNull(row)
            assertEquals("602445790000", row!![MBReleaseTable.barcode])
            assertEquals(releaseId, row[MBReleaseTable.releaseGroupId]?.value)
        }
    }
}
