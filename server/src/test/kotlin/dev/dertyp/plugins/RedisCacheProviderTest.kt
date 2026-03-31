package dev.dertyp.plugins

import com.google.gson.Gson
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClusterClient
import redis.clients.jedis.params.SetParams
import kotlin.time.Duration.Companion.minutes

class RedisCacheProviderTest {

    private lateinit var mockJedis: RedisClusterClient

    @BeforeEach
    fun setup() {
        mockJedis = mockk(relaxed = true)
        mockkStatic(RedisClusterClient::class)
        every { RedisClusterClient.create(any<HostAndPort>()) } returns mockJedis

        startKoin {
            modules(module {
                single { Gson() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `setCache should call jedis set with params`() = runBlocking {
        val config = RedisCacheProvider.Config().apply {
            host = "localhost"
            port = 6379
        }
        val provider = RedisCacheProvider(config)
        val content = "test-content"
        val key = "test-key"
        val duration = 5.minutes

        provider.setCache(key, content, duration)

        verify { 
            mockJedis.set(
                eq(key), 
                match { it.contains("test-content") }, 
                any<SetParams>()
            ) 
        }
    }

    @Test
    fun `getCache should return content from jedis`() = runBlocking {
        val config = RedisCacheProvider.Config().apply {
            host = "localhost"
            port = 6379
        }
        val provider = RedisCacheProvider(config)
        val key = "test-key"

        val type = "java.lang.String"
        val content = "\"cached-value\""
        val cacheString = "${type.length}:$type$content"

        every { mockJedis.exists(key) } returns true
        every { mockJedis.get(key) } returns cacheString

        val result = provider.getCache(key)
        assertEquals("cached-value", result)
    }
}
