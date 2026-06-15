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

        Thread.sleep(200)

        val results = service.search("song", "bohemian")
        
        assertTrue(results.ids.contains(songId), "Should find the song by title")
        assertEquals(1, results.ids.size)

        val artistResults = service.search("song", "queen")
        assertTrue(artistResults.ids.contains(songId), "Should find the song by artist")

        val metaResults = service.search("song", "opera")
        assertTrue(metaResults.ids.contains(songId), "Should find the song by metadata")

        val negativeResults = service.search("song", "bohemian -opera")
        assertTrue(negativeResults.ids.isEmpty(), "Should not find the song if excluded by negative term")
    }

    @Test
    fun `search should handle multiple tokens and prefix matching`() {
        if (TestRedis.redisContainer == null) return

        val id = UUID.randomUUID()
        service.indexSong(id, "Deeply Disturbed", "Infected Mushroom", "Converting Vegetarians", "")

        Thread.sleep(200)

        val results = service.search("song", "deeply distu")
        assertTrue(results.ids.contains(id), "Should find song with partial tokens")

        val multiResults = service.search("song", "infected deeply")
        assertTrue(multiResults.ids.contains(id), "Should find song with tokens from different fields")
    }

    @Test
    fun `artist and album indexing integration`() {
        if (TestRedis.redisContainer == null) return

        val artistId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        service.indexArtist(artistId, "Infected Mushroom", "IM", "Infected", "psytrance")
        service.indexAlbum(albumId, "Converting Vegetarians", "Infected Mushroom", "Psy")

        Thread.sleep(200)

        val artistResults = service.search("artist", "mushroom")
        assertTrue(artistResults.ids.contains(artistId))

        val albumResults = service.search("album", "vegetarians")
        assertTrue(albumResults.ids.contains(albumId))
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
        
        assertEquals(3, results.ids.size)
        assertEquals(id1, results.ids[0], "Title match (weight 5) should be first")
        assertEquals(id3, results.ids[1], "Artist match (weight 2) should be second")
        assertEquals(id2, results.ids[2], "Album match (weight 1) should be third")
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
        
        assertEquals(2, results.ids.size)
        assertEquals(id1, results.ids[0], "Name match should be first")
        assertEquals(id2, results.ids[1], "Alias match should be second")
    }

    @Test
    fun `search should respect offset and limit`() {
        if (TestRedis.redisContainer == null) return

        (1..10).forEach { 
            val id = UUID.randomUUID()
            service.indexSong(id, "Test Song $it", "", "", "")
        }

        Thread.sleep(200)

        val firstPage = service.search("song", "Test", offset = 0, limit = 5)
        assertEquals(5, firstPage.ids.size)
        assertEquals(10, firstPage.total)

        val secondPage = service.search("song", "Test", offset = 5, limit = 5)
        assertEquals(5, secondPage.ids.size)

        assertTrue(firstPage.ids.none { it in secondPage.ids })
    }

    @Test
    fun `test getMemoryUsage reporting`() {
        if (TestRedis.redisContainer == null) return

        service.indexSong(UUID.randomUUID(), "Song 1", "Artist 1", "Album 1", "meta")
        service.indexArtist(UUID.randomUUID(), "Artist 2", "Alias", "Group", "meta")
        service.indexAlbum(UUID.randomUUID(), "Album 2", "Artist", "Group")

        Thread.sleep(200)

        val usage = service.getMemoryUsage()
        println("MEMORY USAGE REPORTED: $usage")
    }
}
