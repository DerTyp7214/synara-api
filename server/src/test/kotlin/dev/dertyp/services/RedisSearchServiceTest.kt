package dev.dertyp.services

import dev.dertyp.TestRedis
import dev.dertyp.plugins.RedisCacheProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

class RedisSearchServiceTest {

    private lateinit var redisProvider: RedisCacheProvider
    private lateinit var config: RedisCacheProvider.Config
    private lateinit var service: RedisSearchService

    @BeforeEach
    fun setup() {
        if (TestRedis.redisContainer == null) {
            return
        }

        config = RedisCacheProvider.Config().apply {
            host = TestRedis.host
            port = TestRedis.port
            useRedisSearch = true
            indexPrefix = "test-${UUID.randomUUID()}"
        }

        startKoin {
            modules(module {
                single { config }
                single { RedisCacheProvider(config) }
                single { RedisSearchService() }
            })
        }

        redisProvider = GlobalContext.get().get()
        service = GlobalContext.get().get()
        service.initIndex()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        if (::redisProvider.isInitialized) {
            redisProvider.jedis.close()
        }
    }

    @Test
    fun `full search integration test with real redis`() {
        if (TestRedis.redisContainer == null) {
            println("Skipping Redis integration test because Docker is not available.")
            return
        }

        val songId = UUID.randomUUID()

        service.indexSong(
            id = songId,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            metadata = "rock opera classic"
        )

        val results = service.search("song", "bohemian")
        
        assertTrue(results.contains(songId), "Should find the song by title")
        assertEquals(1, results.size)

        val artistResults = service.search("song", "queen")
        assertTrue(artistResults.contains(songId), "Should find the song by artist")

        val metaResults = service.search("song", "opera")
        assertTrue(metaResults.contains(songId), "Should find the song by metadata")

        val negativeResults = service.search("song", "bohemian -opera")
        assertTrue(negativeResults.isEmpty(), "Should not find the song if excluded by negative term")
    }

    @Test
    fun `search should handle multiple tokens and prefix matching`() {
        if (TestRedis.redisContainer == null) return

        val id = UUID.randomUUID()
        service.indexSong(id, "Deeply Disturbed", "Infected Mushroom", "Converting Vegetarians", "")

        val results = service.search("song", "deeply distu")
        assertTrue(results.contains(id), "Should find song with partial tokens")

        val multiResults = service.search("song", "infected deeply")
        assertTrue(multiResults.contains(id), "Should find song with tokens from different fields")
    }

    @Test
    fun `artist and album indexing integration`() {
        if (TestRedis.redisContainer == null) return

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        service.indexArtist(artistId, "Infected Mushroom", "IM", "Infected", "psytrance")
        service.indexAlbum(albumId, "Converting Vegetarians", "Infected Mushroom", "Psy")

        val artistResults = service.search("artist", "mushroom")
        assertTrue(artistResults.contains(artistId))

        val albumResults = service.search("album", "vegetarians")
        assertTrue(albumResults.contains(albumId))
    }

    @Test
    fun `ranking integration test with real redis`() {
        if (TestRedis.redisContainer == null) return

        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()

        service.indexSong(id1, "Target Song", "Other Artist", "Other Album", "")
        service.indexSong(id2, "Other Song", "Other Artist", "Target Album", "")
        service.indexSong(id3, "Other Song", "Target Artist", "Other Album", "")

        Thread.sleep(200)

        val results = service.search("song", "Target")
        
        assertEquals(3, results.size)
        assertEquals(id1, results[0], "Title match (weight 5) should be first")
        assertEquals(id3, results[1], "Artist match (weight 2) should be second")
        assertEquals(id2, results[2], "Album match (weight 1) should be third")
    }

    @Test
    fun `artist ranking integration test`() {
        if (TestRedis.redisContainer == null) return

        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()

        service.indexArtist(id1, "Target Artist", "", "", "")
        service.indexArtist(id2, "Other Artist", "Target Alias", "", "")

        Thread.sleep(200)

        val results = service.search("artist", "Target")
        
        assertEquals(2, results.size)
        assertEquals(id1, results[0], "Name match should be first")
        assertEquals(id2, results[1], "Alias match should be second")
    }
}
