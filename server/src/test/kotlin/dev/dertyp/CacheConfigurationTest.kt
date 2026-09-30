package dev.dertyp

import com.google.gson.Gson
import dev.dertyp.config.ServerConfig
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.plugins.toRedisCacheProviderConfig
import dev.dertyp.services.RedisSearchService
import io.ktor.server.application.install
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.RedisClusterClient
import redis.clients.jedis.search.IndexOptions
import redis.clients.jedis.search.Schema

class CacheConfigurationTest {

    private lateinit var jedis: RedisClusterClient

    @BeforeEach
    fun setup() {
        jedis = mockk(relaxed = true)
        every { jedis.ftInfo(any()) } throws IllegalStateException("Unknown index name")
        mockkStatic(RedisClusterClient::class)
        every { RedisClusterClient.create(any<HostAndPort>()) } returns jedis
    }

    @AfterEach
    fun tearDown() {
        runCatching { stopKoin() }
        unmockkAll()
    }

    private fun startWith(vararg properties: Pair<String, String>, check: () -> Unit) = testApplication {
        application {
            install(Koin) {
                modules(module {
                    single<ApplicationConfig> { MapApplicationConfig(*properties) }
                    single { ServerConfig(get()) }
                    single { get<ServerConfig>().toRedisCacheProviderConfig() }
                    single { Gson() }
                    single { RedisSearchService() }
                })
            }
            configureCache()
        }
        startApplication()
        check()
    }

    @Test
    fun `with redis search enabled the cache provider exists before the search indexes are created`() {
        startWith("redis.host" to "redis", "redis.useSearch" to "true", "redis.indexPrefix" to "test") {
            verify(exactly = 1) { RedisClusterClient.create(any<HostAndPort>()) }
            listOf("test:song-index", "test:artist-index", "test:album-index").forEach { index ->
                verify(exactly = 1) { jedis.ftCreate(index, any<IndexOptions>(), any<Schema>()) }
            }
            assertSame(jedis, GlobalContext.get().get<RedisCacheProvider>().jedis)
        }
    }

    @Test
    fun `with redis disabled no provider and no search index is created`() {
        startWith {
            verify(exactly = 0) { RedisClusterClient.create(any<HostAndPort>()) }
            verify(exactly = 0) { jedis.ftCreate(any<String>(), any<IndexOptions>(), any<Schema>()) }
            assertNull(GlobalContext.get().getOrNull<RedisCacheProvider>())
        }
    }
}
