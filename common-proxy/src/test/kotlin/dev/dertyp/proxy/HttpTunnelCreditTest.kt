package dev.dertyp.proxy

import io.ktor.http.Headers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpTunnelCreditTest {

    @Test
    fun `constants have the agreed values`() {
        assertEquals(65536, HttpTunnel.BODY_CHUNK_SIZE)
        assertEquals(262144, HttpTunnel.INITIAL_WINDOW)
        assertEquals(65536, HttpTunnel.WINDOW_GRANT_THRESHOLD)
        assertEquals(65536, HttpTunnel.MAX_HEAD_SIZE)
        assertEquals(128, HttpTunnel.MAX_CONCURRENT_STREAMS)
        assertEquals("http", HttpTunnel.CAPABILITY_PARAMETER)
        assertEquals("1", HttpTunnel.CAPABILITY_VALUE)
    }

    @Test
    fun `acquire returns at most the requested amount`() = runBlocking {
        val window = HttpSendWindow()

        assertEquals(1000, withTimeout(5.seconds) { window.acquire(1000) })
        assertEquals(65536, withTimeout(5.seconds) { window.acquire(65536) })
    }

    @Test
    fun `acquire is partial when less credit is left`() = runBlocking {
        val window = HttpSendWindow()

        assertEquals(262144 - 100, withTimeout(5.seconds) { window.acquire(262144 - 100) })
        assertEquals(100, withTimeout(5.seconds) { window.acquire(65536) })
    }

    @Test
    fun `sender blocks at zero credit and resumes on a grant`() = runBlocking {
        val window = HttpSendWindow()
        assertEquals(262144, withTimeout(5.seconds) { window.acquire(Int.MAX_VALUE) })

        val waiting = async(Dispatchers.Default) { window.acquire(65536) }
        assertNull(withTimeoutOrNull(300.milliseconds) { waiting.await() })
        assertFalse(waiting.isCompleted)

        assertTrue(window.grant(500))
        assertEquals(500, withTimeout(5.seconds) { waiting.await() })

        val again = async(Dispatchers.Default) { window.acquire(1) }
        assertNull(withTimeoutOrNull(300.milliseconds) { again.await() })
        assertTrue(window.grant(65536))
        assertEquals(1, withTimeout(5.seconds) { again.await() })
        assertEquals(65535, withTimeout(5.seconds) { window.acquire(Int.MAX_VALUE) })
    }

    @Test
    fun `concurrent senders never take more than was granted`() = runBlocking {
        val window = HttpSendWindow()
        assertEquals(262144, withTimeout(5.seconds) { window.acquire(Int.MAX_VALUE) })

        val senders = List(8) { async(Dispatchers.Default) { runCatching { window.acquire(1000) } } }
        assertTrue(window.grant(3500))

        assertNull(withTimeoutOrNull(500.milliseconds) { senders.awaitAll() })
        val served = senders.filter { it.isCompleted }.map { it.await().getOrThrow() }
        assertEquals(listOf(500, 1000, 1000, 1000), served.sorted())

        window.close()
        val released = withTimeout(5.seconds) { senders.awaitAll() }
        assertEquals(4, released.count { it.exceptionOrNull() is HttpWindowClosedException })
        assertEquals(3500, released.mapNotNull { it.getOrNull() }.sum())
    }

    @Test
    fun `close releases a waiting sender`() = runBlocking {
        val window = HttpSendWindow()
        assertEquals(262144, withTimeout(5.seconds) { window.acquire(Int.MAX_VALUE) })

        val waiting = async(Dispatchers.Default) { runCatching { window.acquire(10) } }
        assertNull(withTimeoutOrNull(300.milliseconds) { waiting.await() })

        window.close()
        val result = withTimeout(5.seconds) { waiting.await() }
        assertTrue(result.exceptionOrNull() is HttpWindowClosedException)
    }

    @Test
    fun `closed window fails acquire even with credit and ignores grants`() = runBlocking {
        val window = HttpSendWindow()
        window.close()

        assertFailsWith<HttpWindowClosedException> { withTimeout(5.seconds) { window.acquire(10) } }
        assertTrue(window.grant(100))
        assertFailsWith<HttpWindowClosedException> { withTimeout(5.seconds) { window.acquire(10) } }
    }

    @Test
    fun `grant reports overflow and non positive increments`() = runBlocking {
        val window = HttpSendWindow()

        assertFalse(window.grant(0))
        assertFalse(window.grant(-1))
        assertFalse(window.grant(Int.MAX_VALUE.toLong() - 262144 + 1))
        assertFalse(window.grant(0xFFFFFFFFL))
        assertFalse(window.grant(Long.MAX_VALUE))
        assertTrue(window.grant(Int.MAX_VALUE.toLong() - 262144))
        assertFalse(window.grant(1))
        assertEquals(Int.MAX_VALUE, withTimeout(5.seconds) { window.acquire(Int.MAX_VALUE) })
    }

    @Test
    fun `acquire rejects a non positive request`() = runBlocking<Unit> {
        val window = HttpSendWindow()

        assertFailsWith<IllegalArgumentException> { window.acquire(0) }
        assertFailsWith<IllegalArgumentException> { window.acquire(-5) }
    }

    @Test
    fun `receive window grants at the threshold and not before`() {
        val window = HttpReceiveWindow()

        assertTrue(window.received(65536))
        assertEquals(0, window.delivered(65535))
        assertEquals(65536, window.delivered(1))
        assertEquals(0, window.delivered(0))

        assertTrue(window.received(40000))
        assertEquals(0, window.delivered(40000))
        assertTrue(window.received(40000))
        assertEquals(80000, window.delivered(40000))
    }

    @Test
    fun `peer overrunning its window is detected`() {
        val window = HttpReceiveWindow()

        assertTrue(window.received(262144))
        assertFalse(window.received(1))
    }

    @Test
    fun `undelivered bytes do not return credit to the peer`() {
        val window = HttpReceiveWindow()

        assertTrue(window.received(200000))
        assertEquals(65536, window.delivered(65536))
        assertTrue(window.received(262144 - 200000 + 65536))
        assertFalse(window.received(1))
    }

    @Test
    fun `granted credit lets the peer send again`() {
        val window = HttpReceiveWindow()

        assertTrue(window.received(262144))
        assertEquals(0, window.delivered(65535))
        assertEquals(262144, window.delivered(262144 - 65535))
        assertTrue(window.received(262144))
        assertFalse(window.received(1))
    }

    @Test
    fun `request filter drops hop by hop headers`() {
        val filtered = Headers.build {
            append("connection", "X-Foo, close")
            append("X-Foo", "1")
            append("x-bar", "2")
            append("Keep-Alive", "timeout=5")
            append("Proxy-Connection", "keep-alive")
            append("Proxy-Authenticate", "Basic")
            append("Proxy-Authorization", "Basic abc")
            append("te", "trailers")
            append("Trailer", "X-Sum")
            append("TRANSFER-ENCODING", "chunked")
            append("Upgrade", "websocket")
            append("Expect", "100-continue")
            append("HTTP2-Settings", "AAMAAABkAAQAAP__")
            append("Host", "example.org")
            append("Content-Length", "12")
            append("Authorization", "Bearer t")
            append("Cookie", "a=1")
            append("Cookie", "b=2")
            append("Range", "bytes=0-99")
            append("Content-Encoding", "gzip")
            append("Access-Control-Request-Method", "PUT")
        }.withoutRequestHopByHop()

        assertEquals(
            setOf(
                "x-bar", "Content-Length", "Authorization", "Cookie", "Range", "Content-Encoding",
                "Access-Control-Request-Method"
            ),
            filtered.names()
        )
        assertEquals(listOf("a=1", "b=2"), filtered.getAll("Cookie"))
        assertEquals("2", filtered["X-Bar"])
    }

    @Test
    fun `response filter drops hop by hop headers but keeps request only names`() {
        val filtered = Headers.build {
            append("Connection", "close")
            append("Connection", "x-internal")
            append("X-Internal", "1")
            append("Keep-Alive", "timeout=5")
            append("Proxy-Connection", "keep-alive")
            append("Proxy-Authenticate", "Basic")
            append("Proxy-Authorization", "Basic abc")
            append("TE", "trailers")
            append("Trailer", "X-Sum")
            append("Transfer-Encoding", "chunked")
            append("upgrade", "h2c")
            append("Expect", "kept")
            append("Host", "kept")
            append("HTTP2-Settings", "kept")
            append("Content-Length", "12")
            append("Content-Encoding", "gzip")
            append("Content-Range", "bytes 0-99/200")
            append("Set-Cookie", "a=1")
            append("Set-Cookie", "b=2")
            append("Access-Control-Allow-Origin", "*")
        }.withoutResponseHopByHop()

        assertEquals(
            setOf(
                "Expect", "Host", "HTTP2-Settings", "Content-Length", "Content-Encoding", "Content-Range",
                "Set-Cookie", "Access-Control-Allow-Origin"
            ),
            filtered.names()
        )
        assertEquals(listOf("a=1", "b=2"), filtered.getAll("Set-Cookie"))
    }

    @Test
    fun `filters keep everything without hop by hop headers`() {
        val headers = Headers.build {
            append("Accept", "application/json")
            append("Authorization", "Bearer t")
        }

        assertEquals(headers, headers.withoutRequestHopByHop())
        assertEquals(headers, headers.withoutResponseHopByHop())
    }
}
