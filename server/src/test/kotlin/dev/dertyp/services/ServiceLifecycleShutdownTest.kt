package dev.dertyp.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections

class ServiceLifecycleShutdownTest {
    private class RecordingService(
        private val name: String,
        private val stopped: MutableList<String>,
        private val hang: Boolean = false
    ) : Service() {
        override suspend fun stopService() {
            if (hang) awaitCancellation()
            stopped += name
            super.stopService()
        }
    }

    private class RecordingResource(private val name: String, private val stopped: MutableList<String>) :
        AutoCloseable {
        override fun close() {
            stopped += name
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a service that does not stop in time is skipped and the rest still stop`() = runTest {
        val stopped = Collections.synchronizedList(mutableListOf<String>())
        val first = RecordingService("first", stopped)
        val hanging = RecordingService("hanging", stopped, hang = true)
        val last = RecordingService("last", stopped)
        val registry = ServiceRegistry()
        registry.register(first)
        registry.register(hanging)
        registry.register(last)

        registry.stopAll()

        assertEquals(listOf("last", "first"), stopped)
        assertEquals(ServiceRegistry.STOP_TIMEOUT.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun `resources registered first are closed after every service`() = runTest {
        val stopped = Collections.synchronizedList(mutableListOf<String>())
        val database = RecordingResource("database", stopped)
        val http = RecordingService("http", stopped)
        val cache = RecordingResource("cache", stopped)
        val schedule = RecordingService("schedule", stopped)
        val registry = ServiceRegistry()
        registry.register(database)
        registry.register(http)
        registry.register(cache)
        registry.register(schedule)
        registry.register(database)

        registry.stopAll()

        assertEquals(listOf("schedule", "cache", "http", "database"), stopped)
    }
}
