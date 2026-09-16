package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.ServerStats
import dev.dertyp.db.*
import dev.dertyp.services.metadata.MusicBrainzCacheService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import java.util.UUID

class ServerStatsServiceTest {
    private lateinit var database: Database
    private lateinit var service: ServerStatsService
    private lateinit var storageService: StorageService
    private lateinit var reverseProxyService: ReverseProxyService
    private lateinit var musicBrainzCacheService: MusicBrainzCacheService

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "stats_test")
        transaction(database) {
            SchemaUtils.create(
                ArtistTable, AlbumTable, ImageTable, AnimatedImageTable, SongTable, SongVariantTable, PlaylistTable, UserTable, UserPlaylistTable, TranscodedSongTable,
                PodcastShowTable, PodcastEpisodeTable, PodcastTranscriptTable, PodcastSubscriptionTable, PodcastEpisodeProgressTable,
            )
        }
        storageService = mockk()
        reverseProxyService = mockk()
        musicBrainzCacheService = mockk()
        service = ServerStatsService(storageService, reverseProxyService, musicBrainzCacheService)
    }

    private fun insertFeedShow(feedUrl: String, title: String = "Show"): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().toEpochMilli()
        PodcastShowTable.insert {
            it[PodcastShowTable.id] = id
            it[showSource] = PodcastSource.FEED
            it[sourceKey] = "FEED:$feedUrl"
            it[PodcastShowTable.feedUrl] = feedUrl
            it[PodcastShowTable.title] = title
            it[createdAt] = now
            it[updatedAt] = now
        }
        return id
    }

    private fun insertEpisode(showId: UUID, guid: String, importState: PodcastImportState = PodcastImportState.NONE): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().toEpochMilli()
        PodcastEpisodeTable.insert {
            it[PodcastEpisodeTable.id] = id
            it[PodcastEpisodeTable.showId] = showId
            it[PodcastEpisodeTable.guid] = guid
            it[guidKey] = guid
            it[title] = "Episode"
            it[publishedAt] = now
            it[PodcastEpisodeTable.importState] = importState
            it[createdAt] = now
            it[updatedAt] = now
        }
        return id
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getStats should return correct counts and sums`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        coEvery { storageService.getTotalStorage() } returns 1000L
        coEvery { storageService.getImagesStorage() } returns 500L
        coEvery { storageService.getAnimatedImagesStorage() } returns 250L
        coEvery { storageService.getPodcastStorage() } returns 0L
        coEvery { musicBrainzCacheService.getStats() } returns ServerStats.MusicBrainzCacheStats(0, 0, 0, 0, 0, 0, 0, 0)

        transaction(database) {
            ArtistTable.insert { it[name] = "Artist" }[ArtistTable.id]
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]
            
            SongTable.insert {
                it[title] = "Song 1"
                it[this.albumId] = albumId
                it[fileSize] = 100L
                it[duration] = 60L
            }
            SongTable.insert {
                it[title] = "Song 2"
                it[this.albumId] = albumId
                it[fileSize] = 200L
                it[duration] = 120L
            }
            
            PlaylistTable.insert { it[name] = "Playlist" }
        }

        val stats = service.getStats()
        
        assertEquals(2, stats.songCount)
        assertEquals(1, stats.albumCount)
        assertEquals(1, stats.artistCount)
        assertEquals(1, stats.playlistCount)
        assertEquals(0, stats.animatedImagesCount)
        assertEquals(300L, stats.indexedFileSize)
        assertEquals(180L, stats.totalDuration)
        assertEquals(1000L, stats.totalFileSize)
        assertEquals(500L, stats.imagesFileSize)
        assertEquals(250L, stats.animatedImagesFileSize)
        assertEquals(150L, stats.averageSizePerSong)
        assertNotNull(stats.version.version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getProxyInfo should return null if proxy not configured`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        every { reverseProxyService.proxyHost } returns null
        assertNull(service.getProxyInfo())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getProxyInfo should return info if proxy configured`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        every { reverseProxyService.proxyHost } returns "proxy.com"
        every { reverseProxyService.controlPort } returns 8080
        every { reverseProxyService.proxySsl } returns true
        every { reverseProxyService.proxyId } returns "my-id"

        val info = service.getProxyInfo()
        assertNotNull(info)
        assertEquals("proxy.com", info?.host)
        assertEquals(8080, info?.controlPort)
        assertTrue(info?.ssl == true)
        assertEquals("my-id", info?.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getStats includes podcast counts and file size`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        coEvery { storageService.getTotalStorage() } returns 0L
        coEvery { storageService.getImagesStorage() } returns 0L
        coEvery { storageService.getAnimatedImagesStorage() } returns 0L
        coEvery { storageService.getPodcastStorage() } returns 54321L
        coEvery { musicBrainzCacheService.getStats() } returns ServerStats.MusicBrainzCacheStats(0, 0, 0, 0, 0, 0, 0, 0)

        transaction(database) {
            val showId = insertFeedShow("https://feed.example/stats")
            insertEpisode(showId, "ep-1", PodcastImportState.IMPORTED)
            insertEpisode(showId, "ep-2", PodcastImportState.NONE)
        }

        val stats = service.getStats()

        assertEquals(1, stats.podcastShowCount)
        assertEquals(2, stats.podcastEpisodeCount)
        assertEquals(1, stats.podcastImportedEpisodeCount)
        assertEquals(54321L, stats.podcastFileSize)
    }
}
