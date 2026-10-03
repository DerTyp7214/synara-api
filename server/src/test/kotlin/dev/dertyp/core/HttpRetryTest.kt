package dev.dertyp.core

import dev.dertyp.services.metadata.AcoustIdService
import dev.dertyp.services.metadata.AppleMusicService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.services.metadata.TheAudioDBService
import dev.dertyp.services.metadata.TidalService
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HttpRetryTest {
    private val logger = LoggerFactory.getLogger(HttpRetryTest::class.java)

    private fun response(status: HttpStatusCode, headers: Headers = Headers.Empty): HttpResponse = mockk {
        every { this@mockk.status } returns status
        every { this@mockk.headers } returns headers
    }

    private class Script(private val steps: List<() -> HttpResponse>) {
        var calls = 0
            private set

        fun next(): HttpResponse = steps[minOf(calls++, steps.size - 1)]()
    }

    private fun script(vararg steps: () -> HttpResponse) = Script(steps.toList())

    private val backoffPolicy = RetryPolicy(
        maxAttempts = 3,
        retryOn = { it == HttpStatusCode.ServiceUnavailable || it == HttpStatusCode.TooManyRequests },
        backoff = { _, attempt -> (attempt + 1).seconds },
        onError = RetryOnError.RETRY,
    )

    private suspend fun fetch(
        policy: RetryPolicy,
        script: Script,
        gaveUp: MutableList<Pair<HttpStatusCode, Int>> = mutableListOf(),
    ): HttpStatusCode? = retryingGet(
        policy = policy,
        label = "test",
        logger = logger,
        request = { script.next() },
        onGiveUp = { response, retries -> gaveUp += response.status to retries },
    ) { it.status }

    @Test
    fun `a success is parsed on the first attempt`() = runTest {
        val script = script({ response(HttpStatusCode.OK) })

        assertEquals(HttpStatusCode.OK, fetch(backoffPolicy, script))
        assertEquals(1, script.calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a non retryable client error returns null without retrying`() = runTest {
        val script = script({ response(HttpStatusCode.NotFound) })
        val gaveUp = mutableListOf<Pair<HttpStatusCode, Int>>()

        assertNull(fetch(backoffPolicy, script, gaveUp))
        assertEquals(1, script.calls)
        assertEquals(listOf(HttpStatusCode.NotFound to 0), gaveUp)
    }

    @Test
    fun `a retryable status is retried with backoff until attempts run out`() = runTest {
        val script = script({ response(HttpStatusCode.ServiceUnavailable) })
        val gaveUp = mutableListOf<Pair<HttpStatusCode, Int>>()

        assertNull(fetch(backoffPolicy, script, gaveUp))
        assertEquals(3, script.calls)
        assertEquals(3.seconds.inWholeMilliseconds, currentTime)
        assertEquals(listOf(HttpStatusCode.ServiceUnavailable to 2), gaveUp)
    }

    @Test
    fun `a retryable status is retried until the request succeeds`() = runTest {
        val script = script(
            { response(HttpStatusCode.TooManyRequests) },
            { response(HttpStatusCode.OK) },
        )

        assertEquals(HttpStatusCode.OK, fetch(backoffPolicy, script))
        assertEquals(2, script.calls)
        assertEquals(1.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `retry after overrides the backoff when honored and is capped`() = runTest {
        val policy = RetryPolicy(
            maxAttempts = 3,
            retryOn = { it == HttpStatusCode.TooManyRequests },
            backoff = { _, _ -> 1.seconds },
            honorRetryAfter = true,
            maxDelay = 1.minutes,
        )
        val script = script(
            { response(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "7")) },
            { response(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600")) },
            { response(HttpStatusCode.OK) },
        )

        assertEquals(HttpStatusCode.OK, fetch(policy, script))
        assertEquals((7.seconds + 1.minutes).inWholeMilliseconds, currentTime)
    }

    @Test
    fun `an invalid or negative retry after falls back to the backoff`() = runTest {
        val policy = RetryPolicy(
            maxAttempts = 3,
            retryOn = { it == HttpStatusCode.TooManyRequests },
            backoff = { _, _ -> 2.seconds },
            honorRetryAfter = true,
        )
        val script = script(
            { response(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "-5")) },
            {
                response(
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.RetryAfter, "Wed, 21 Oct 2015 07:28:00 GMT")
                )
            },
            { response(HttpStatusCode.OK) },
        )

        assertEquals(HttpStatusCode.OK, fetch(policy, script))
        assertEquals(4.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `retry after is ignored unless the policy honors it`() = runTest {
        val script = script(
            { response(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30")) },
            { response(HttpStatusCode.OK) },
        )

        assertEquals(HttpStatusCode.OK, fetch(backoffPolicy, script))
        assertEquals(1.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `a custom success predicate rejects other 2xx statuses`() = runTest {
        val policy = RetryPolicy(maxAttempts = 3, isSuccess = { it == HttpStatusCode.OK })
        val script = script({ response(HttpStatusCode.NoContent) })

        assertNull(fetch(policy, script))
        assertEquals(1, script.calls)
    }

    @Test
    fun `exceptions are retried when the policy retries errors`() = runTest {
        val script = script(
            { throw IOException("reset") },
            { response(HttpStatusCode.OK) },
        )

        assertEquals(HttpStatusCode.OK, fetch(backoffPolicy, script))
        assertEquals(2, script.calls)
    }

    @Test
    fun `exceptions return null after the last attempt when retried`() = runTest {
        val script = script({ throw IOException("reset") })

        assertNull(fetch(backoffPolicy, script))
        assertEquals(3, script.calls)
        assertEquals(3.seconds.inWholeMilliseconds, currentTime)
    }

    @Test
    fun `parse failures count as errors`() = runTest {
        var parses = 0
        val result = retryingGet(
            policy = backoffPolicy,
            label = "test",
            logger = logger,
            request = { response(HttpStatusCode.OK) },
        ) {
            parses++
            if (parses == 1) throw IllegalStateException("bad body")
            "parsed"
        }

        assertEquals("parsed", result)
        assertEquals(2, parses)
    }

    @Test
    fun `exceptions give up immediately when the policy gives up on errors`() = runTest {
        val script = script({ throw IOException("reset") })

        assertNull(fetch(RetryPolicy(maxAttempts = 3, onError = RetryOnError.GIVE_UP), script))
        assertEquals(1, script.calls)
    }

    @Test
    fun `exceptions propagate when the policy throws on errors`() = runTest {
        val script = script({ throw IOException("reset") })

        val error =
            runCatching { fetch(RetryPolicy(maxAttempts = 3, onError = RetryOnError.THROW), script) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(1, script.calls)
    }

    @Test
    fun `cancellation from the request is rethrown without retrying`() = runTest {
        val script = script({ throw CancellationException("cancelled") })

        val error = runCatching { fetch(backoffPolicy, script) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, script.calls)
    }

    @Test
    fun `cancelling during a backoff stops further attempts`() = runTest {
        val script = script({ response(HttpStatusCode.ServiceUnavailable) })
        val call = async { fetch(backoffPolicy, script) }

        runCurrent()
        advanceTimeBy(500.milliseconds)
        call.cancel()
        runCurrent()

        assertTrue(call.isCancelled)
        assertEquals(1, script.calls)
    }

    @Test
    fun `the service policies keep their status behaviour`() {
        val mb = MusicBrainzService.RETRY_POLICY
        assertEquals(3, mb.maxAttempts)
        assertTrue(
            mb.retryOn(HttpStatusCode.ServiceUnavailable) && mb.retryOn(HttpStatusCode.TooManyRequests) && mb.retryOn(
                HttpStatusCode.BadGateway
            )
        )
        assertTrue(!mb.retryOn(HttpStatusCode.NotFound) && !mb.retryOn(HttpStatusCode.BadRequest))
        assertEquals(1.seconds, mb.delayFor(response(HttpStatusCode.ServiceUnavailable), 0))
        assertEquals(1.seconds, mb.delayFor(response(HttpStatusCode.TooManyRequests), 2))
        assertEquals(10.seconds, mb.delayFor(response(HttpStatusCode.InternalServerError), 0))
        assertEquals(10.seconds, mb.delayFor(null, 0))
        assertEquals(RetryOnError.RETRY, mb.onError)

        val audioDb = TheAudioDBService.RETRY_POLICY
        assertEquals(5, audioDb.maxAttempts)
        assertTrue(audioDb.retryOn(HttpStatusCode.TooManyRequests))
        assertTrue(!audioDb.retryOn(HttpStatusCode.InternalServerError))
        assertEquals(1.seconds, audioDb.delayFor(null, 3))
        assertEquals(RetryOnError.RETRY, audioDb.onError)

        val tidal = TidalService.RETRY_POLICY
        assertEquals(6, tidal.maxAttempts)
        assertTrue(!tidal.isSuccess(HttpStatusCode.NoContent) && tidal.isSuccess(HttpStatusCode.OK))
        assertTrue(
            tidal.retryOn(HttpStatusCode.TooManyRequests) && tidal.retryOn(HttpStatusCode.RequestTimeout) && tidal.retryOn(
                HttpStatusCode.BadGateway
            )
        )
        assertTrue(!tidal.retryOn(HttpStatusCode.NotFound))
        assertEquals(5.seconds, tidal.delayFor(response(HttpStatusCode.ServiceUnavailable), 0))
        assertEquals(40.seconds, tidal.delayFor(response(HttpStatusCode.ServiceUnavailable), 3))
        assertEquals(2.minutes, tidal.delayFor(response(HttpStatusCode.ServiceUnavailable), 9))
        assertEquals(
            3.seconds,
            tidal.delayFor(response(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, " 3 ")), 4)
        )
        assertEquals(
            2.minutes,
            tidal.delayFor(response(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "600")), 0)
        )
        assertEquals(RetryOnError.THROW, tidal.onError)

        val apple = AppleMusicService.CATALOG_RETRY_POLICY
        assertEquals(1, apple.maxAttempts)
        assertTrue(!apple.isSuccess(HttpStatusCode.Created))
        assertEquals(RetryOnError.GIVE_UP, apple.onError)

        val acoustId = AcoustIdService.RETRY_POLICY
        assertEquals(1, acoustId.maxAttempts)
        assertTrue(acoustId.isSuccess(HttpStatusCode.Created))
        assertEquals(RetryOnError.GIVE_UP, acoustId.onError)
    }
}
