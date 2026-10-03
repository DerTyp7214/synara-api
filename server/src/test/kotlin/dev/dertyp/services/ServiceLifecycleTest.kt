package dev.dertyp.services

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Collections

class ServiceLifecycleTest {
    private class ScopedService(
        private val name: String,
        private val stopped: MutableList<String>,
        private val failOnStop: Boolean = false
    ) : Service() {
        fun launchInScope(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

        fun scopeJob(): Job = scope.coroutineContext.job

        override suspend fun stopService() {
            stopped += name
            super.stopService()
            if (failOnStop) throw IllegalStateException("stop failed")
        }
    }

    @Test
    fun `a failing coroutine does not cancel the service scope`() = runTest {
        val service = ScopedService("a", mutableListOf())

        service.launchInScope { throw IllegalStateException("boom") }.join()
        val result = CompletableDeferred<Int>()
        service.launchInScope { result.complete(42) }

        assertEquals(42, withContext(Dispatchers.Default) { withTimeout(5_000) { result.await() } })
        assertTrue(service.scopeJob().isActive)
        service.stopService()
    }

    @Test
    fun `stopService cancels the service scope`() = runTest {
        val service = ScopedService("a", mutableListOf())
        val job = service.launchInScope { awaitCancellation() }

        service.stopService()

        withContext(Dispatchers.Default) { withTimeout(5_000) { job.join() } }
        assertTrue(job.isCancelled)
        assertFalse(service.scopeJob().isActive)
    }

    @Test
    fun `stopAll stops services in reverse registration order and continues after a failure`() = runTest {
        val stopped = Collections.synchronizedList(mutableListOf<String>())
        val first = ScopedService("first", stopped)
        val second = ScopedService("second", stopped, failOnStop = true)
        val third = ScopedService("third", stopped)
        val registry = ServiceRegistry()
        registry.register(first)
        registry.register(second)
        registry.register(third)
        registry.register(first)
        val firstJob = first.scopeJob()
        val thirdJob = third.scopeJob()

        registry.stopAll()

        assertEquals(listOf("third", "second", "first"), stopped)
        assertFalse(firstJob.isActive)
        assertFalse(thirdJob.isActive)
    }
}
