package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.TestRedis
import dev.dertyp.db.*
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class RedisRankedSearchIntegrationTest : KoinTest {
    private lateinit var database: Database
    private lateinit var redisProvider: RedisCacheProvider
    private lateinit var redisSearchService: RedisSearchService
    private lateinit var songService: SongService
    
    private val artistService = mockk<ArtistService>(relaxed = true)
    private val albumService = mockk<AlbumService>(relaxed = true)
    private val imageService = mockk<ImageService>(relaxed = true)
    private val genreService = mockk<GenreService>(relaxed = true)

    private val allTables = arrayOf(
        ArtistTable, AlbumTable, SongTable, SongArtistTable,
        SongMusicBrainzTable, SongAudioDataTable, ImageTable, GenreTable,
        UserTable, AlbumMusicBrainzTable, ArtistMusicBrainzTable,
        ArtistAliasTable, ArtistMemberTable, AlbumArtistTable,
        PlaylistTable, UserSongTable, UserPlaylistTable,
        SongGenreTable, ArtistGenreTable, AlbumGenreTable,
        PlaylistSongTable, UserPlaylistSongTable,
        SyncedLyricsTable, ImageMetadataTable, RecentReleaseTable,
        FollowedArtistTable, TranscodedSongTable, CustomMigrationTable,
        ScheduledTaskLogTable, ArtistSplitAliasTable, SyncServiceTable,
        SongProviderTable, AlbumProviderTable,
        *allMusicBrainzTables
    )

    @BeforeEach
    fun setup() {
        if (TestRedis.redisContainer == null) return

        database = TestDatabase.connect(DbDialect.POSTGRES, "redis_ranked_search")
        transaction(database) {
            SchemaUtils.create(*allTables)
        }

        val config = RedisCacheProvider.Config().apply {
            host = TestRedis.host
            port = TestRedis.port
            useRedisSearch = true
            indexPrefix = "ranked-test-${UUID.randomUUID()}"
        }

        startKoin {
            modules(module {
                single { mockk<ApplicationEnvironment>(relaxed = true) }
                single { config }
                single { RedisCacheProvider(config) }
                single { RedisSearchService() }
                single { mockk<MusicBrainzService>(relaxed = true) }
                single { mockk<CachedMusicBrainzService>(relaxed = true) }
                single { mockk<MusicBrainzCacheService>(relaxed = true) }
                single { artistService }
                single { albumService }
                single { genreService }
                single { imageService }
                single { LibraryMergeService() }
                single { SongService() }
            })
        }

        redisProvider = GlobalContext.get().get()
        redisSearchService = GlobalContext.get().get()
        songService = GlobalContext.get().get()
        
        redisSearchService.initIndex()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        if (::redisProvider.isInitialized) {
            redisProvider.jedis.close()
        }
        TestDatabase.cleanUp()
    }

    @Test
    fun `rankedSearchQuery should use Redis and return results from DB in Redis order`() = runBlocking {
        if (TestRedis.redisContainer == null) return@runBlocking

        val userId = UUID.randomUUID()
        val songId1 = UUID.randomUUID()
        val songId2 = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }
            
            val album1 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Other"
            }[AlbumTable.id]
            
            val album2 = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Target"
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = songId1
                it[title] = "Target"
                it[albumId] = album1
            }
            SongTable.insert {
                it[id] = songId2
                it[title] = "Other"
                it[albumId] = album2
            }
        }

        redisSearchService.indexSong(songId1, "Target", "Other", "Other", "")
        redisSearchService.indexSong(songId2, "Other", "Other", "Target", "")

        Thread.sleep(200)

        val result = songService.rankedSearch(0, 10, "Target", true, userId)

        assertEquals(2, result.data.size)
        assertEquals(songId1, result.data[0].id, "Song matched by title should be first")
        assertEquals(songId2, result.data[1].id, "Song matched by album should be second")
    }

    @Test
    fun `rankedSearchQuery should fall back to DB if Redis returns no results`() = runBlocking {
        if (TestRedis.redisContainer == null) return@runBlocking

        val userId = UUID.randomUUID()
        val songId = UUID.randomUUID()

        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = ""
            }
            val albumId = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Some Album"
            }[AlbumTable.id]

            SongTable.insert {
                it[id] = songId
                it[title] = "DatabaseOnly"
                it[this.albumId] = albumId
            }
        }
        
        val result = songService.rankedSearch(0, 10, "DatabaseOnly", true, userId)

        assertEquals(1, result.data.size)
        assertEquals(songId, result.data.first().id)
    }
}
