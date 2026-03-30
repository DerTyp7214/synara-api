package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.MusicBrainzArtist
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.metadata.*
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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
    private lateinit var spotifyService: SpotifyService

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single<StorageService> { mockk(relaxed = true) }
                single<RedisCacheProvider.Config> { mockk(relaxed = true) }
                
                single { mockk<MusicBrainzService>() }
                single { mockk<ArtistService>() }
                single { mockk<ImageService>() }
                single { mockk<SpotifyService>() }
                single { mockk<ApplicationEnvironment>() }
                single { mockk<TidalService>() }
            })
        }

        musicBrainzService = get()
        artistService = get()
        imageService = get()
        environment = get()
        tidalService = get()
        spotifyService = get()

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
                RecentReleaseTable
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
        val mbId = "mb-id-123"

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Artist" }
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
        val mbId = "mb-id-new"
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
        val tidalType = MetadataService.Companion.MetadataType.tidal
        every { MetadataService.getMetadataService(tidalType, any()) } returns tidalService
        val spotifyType = MetadataService.Companion.MetadataType.spotify
        every { MetadataService.getMetadataService(spotifyType, any()) } returns spotifyService

        val mbId = "mb-artist-1"
        val releaseId = "mb-release-1"
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }
        }

        coEvery { musicBrainzService.fetchReleaseGroups(mbId) } returns listOf(
            MusicBrainzReleaseGroup(
                id = releaseId,
                title = "New Album",
                firstReleaseDate = "2023-10-27",
                relations = listOf(
                    MusicBrainzRelation(
                        type = "spotify",
                        url = MusicBrainzRelationUrl(id = "1", resource = "https://spotify.com/album/123")
                    )
                )
            )
        )
        coEvery { musicBrainzService.fetchReleasesByArtist(mbId) } returns emptyList()
        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(any()) } returns emptyList()
        coEvery { musicBrainzService.fetchRecordingsByReleaseGroup(any()) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService["resolvePlatformLinks"](any<String>()) } returns listOf("https://tidal.com/album/456")
        coEvery { tidalService.searchAlbums(any(), any(), any()) } returns emptyList()
        coEvery { spotifyService.searchAlbums(any(), any(), any()) } returns emptyList()
        
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
    fun `fetchNewReleases should skip releases already in library`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        mockkObject(MetadataService.Companion)
        val tidalType = MetadataService.Companion.MetadataType.tidal
        every { MetadataService.getMetadataService(tidalType, any()) } returns tidalService
        val spotifyType = MetadataService.Companion.MetadataType.spotify
        every { MetadataService.getMetadataService(spotifyType, any()) } returns spotifyService

        val mbId = "mb-artist-1"
        val releaseIdInAlbumDb = "mb-release-in-album-db"
        val releaseIdInSongDb = "mb-release-in-song-db"
        val releaseIdNew = "mb-release-new"
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert { it[id] = userId; it[username] = "user"; it[passwordHash] = "hash" }
            ArtistTable.insert { it[id] = artistId; it[name] = "Test Artist" }
            ArtistMusicBrainzTable.insert { it[this.artistId] = artistId; it[musicBrainzId] = mbId }
            FollowedArtistTable.insert { it[this.userId] = userId; it[this.artistId] = artistId }

            val albumId = AlbumTable.insert { it[name] = "Existing Album" }[AlbumTable.id]
            AlbumArtistTable.insert { it[this.albumId] = albumId; it[this.artistId] = artistId }
            AlbumMusicBrainzTable.insert { it[this.albumId] = albumId; it[musicBrainzId] = releaseIdInAlbumDb }

            val songId = SongTable.insert {
                it[title] = "Existing Song"
                it[this.albumId] = albumId
            }[SongTable.id]
            SongArtistTable.insert { it[this.songId] = songId; it[this.artistId] = artistId }
            SongMusicBrainzTable.insert { it[this.songId] = songId; it[musicBrainzId] = releaseIdInSongDb }
        }

        coEvery { musicBrainzService.fetchReleasesByArtist(mbId) } returns listOf(
            MusicBrainzRelease(
                id = releaseIdInAlbumDb,
                releaseGroup = MusicBrainzReleaseGroup(id = releaseIdInAlbumDb, title = "Existing Album")
            ),
            MusicBrainzRelease(
                id = releaseIdInSongDb,
                releaseGroup = MusicBrainzReleaseGroup(id = "some-other-group-id", title = "Album with existing song")
            )
        )

        coEvery { musicBrainzService.fetchReleaseGroups(mbId) } returns listOf(
            MusicBrainzReleaseGroup(
                id = releaseIdInAlbumDb,
                title = "Existing Album"
            ),
            MusicBrainzReleaseGroup(
                id = "some-other-group-id",
                title = "Album with existing song"
            ),
            MusicBrainzReleaseGroup(
                id = releaseIdNew,
                title = "Truly New Album"
            )
        )
        coEvery { musicBrainzService.fetchReleasesByReleaseGroup(any()) } returns emptyList()
        coEvery { musicBrainzService.fetchRecordingsByReleaseGroup(any()) } returns emptyList()

        val spiedService = spyk(service, recordPrivateCalls = true)
        coEvery { spiedService["resolvePlatformLinks"](any<String>()) } returns emptyList<String>()
        coEvery { spiedService.fetchReleaseGroupImage(any()) } returns null
        coEvery { tidalService.searchAlbums(any(), any(), any()) } returns emptyList()
        coEvery { spotifyService.searchAlbums(any(), any(), any()) } returns emptyList()

        spiedService.fetchNewReleases()

        val releases = service.getRecentReleases(userId).data
        assertEquals(1, releases.size)
        assertEquals("Truly New Album", releases[0].title)
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
                RecentReleaseTable.insert {
                    it[releaseId] = "release-$i"
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
}
