package dev.dertyp.core

import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpClientFactoryTest {

    @Test
    fun `shared clients are created once per name`() = runBlocking {
        val factory = HttpClientFactory()
        try {
            assertSame(factory.api, factory.api)
            assertSame(factory.shared("a", CIO), factory.shared("a", CIO))
            assertNotSame(factory.shared("a", CIO), factory.shared("b", CIO))
        } finally {
            factory.stopService()
        }
    }

    @Test
    fun `stopping the factory closes every client it created`() = runBlocking {
        val factory = HttpClientFactory()
        val api = factory.api
        val shared = factory.shared("mirror", CIO)
        val defaultEngine = factory.sharedWithDefaultEngine("proxy")
        val created = factory.create(OkHttp)
        val clients = listOf(api, shared, defaultEngine, created)
        assertTrue(clients.all { it.coroutineContext.job.isActive })

        factory.stopService()

        assertTrue(clients.none { it.coroutineContext.job.isActive })
    }

    @Test
    fun `clients closed by their owner do not break stopping the factory`() = runBlocking {
        val factory = HttpClientFactory()
        val bridge = factory.create(OkHttp)
        val api = factory.api
        bridge.close()
        assertFalse(bridge.coroutineContext.job.isActive)

        factory.stopService()

        assertFalse(api.coroutineContext.job.isActive)
    }
}
