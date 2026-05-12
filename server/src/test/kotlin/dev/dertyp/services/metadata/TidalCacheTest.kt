package dev.dertyp.services.metadata

import dev.dertyp.core.getTrackFromJedis
import dev.dertyp.core.writeToJedis
import dev.dertyp.plugins.RedisCacheObject
import dev.dertyp.plugins.RedisCacheProvider
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import redis.clients.jedis.RedisClusterClient
import redis.clients.jedis.params.SetParams
import kotlin.time.Duration.Companion.minutes

class TidalCacheTest : KoinTest {
    private val jedis = mockk<RedisClusterClient>(relaxed = true)
    private val redisConfig = mockk<RedisCacheProvider.Config>()
    private lateinit var tidalService: TidalService

    @BeforeEach
    fun setup() {
        every { redisConfig.host } returns "localhost"
        every { redisConfig.port } returns 6379

        startKoin {
            modules(module {
                single { redisConfig }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
            })
        }

        tidalService = spyk(TidalService(getKoin().get()))
        every { tidalService.jedis } returns jedis
        
        mockkObject(RedisCacheObject)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `writeToJedis should store track with TTL`() {
        val track = IMetadataService.Track(
            id = "123",
            title = "Test Track",
            duration = 3.minutes,
            images = emptyList()
        )
        
        val mockCacheObj = mockk<RedisCacheObject>(relaxed = true)
        every { RedisCacheObject.fromObject(track) } returns mockCacheObj
        every { mockCacheObj.toString() } returns "serialized-track"

        tidalService.writeToJedis(track)

        verify { 
            jedis.set(
                "tidal_track::123", 
                "serialized-track", 
                any<SetParams>()
            ) 
        }
    }

    @Test
    fun `getTrackFromJedis should return deserialized track if exists`() {
        val trackId = "123"
        val serialized = "serialized-track"
        val expectedTrack = IMetadataService.Track(
            id = trackId,
            title = "Test Track",
            duration = 3.minutes,
            images = emptyList()
        )

        every { jedis.exists("tidal_track::$trackId") } returns true
        every { jedis.get("tidal_track::$trackId") } returns serialized
        every { RedisCacheObject.fromCache<IMetadataService.Track>(serialized) } returns expectedTrack

        val result = tidalService.getTrackFromJedis(trackId)

        assertEquals(expectedTrack, result)
    }
}
