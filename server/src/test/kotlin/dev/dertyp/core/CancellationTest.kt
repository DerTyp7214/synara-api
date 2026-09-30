package dev.dertyp.core

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

class CancellationTest {

    @Test
    fun `runCatchingCancellable wraps the value on success`() = runBlocking {
        val result = runCatchingCancellable { 42 }

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `runCatchingCancellable wraps ordinary exceptions as failure`() = runBlocking {
        val error = IllegalStateException("boom")

        val result = runCatchingCancellable { throw error }

        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `runCatchingCancellable rethrows cancellation`() {
        assertThrows<CancellationException> {
            runBlocking {
                runCatchingCancellable { throw CancellationException("cancelled") }
            }
        }
    }

    @Test
    fun `runCatchingCancellable lets a cancelled coroutine stop`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var continuedAfterCancel = false

        val job = launch {
            runCatchingCancellable {
                started.complete(Unit)
                awaitCancellation()
            }
            continuedAfterCancel = true
        }

        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(false, continuedAfterCancel)
    }

    @Test
    fun `runCatchingCancellable inside withTimeoutOrNull still yields null on timeout`() = runBlocking {
        val result = withTimeoutOrNull(20.milliseconds) {
            runCatchingCancellable { delay(1_000.milliseconds) }
        }

        assertNull(result)
    }
}
