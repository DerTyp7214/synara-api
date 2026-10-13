package dev.dertyp.proxy

import io.ktor.http.Headers
import io.ktor.http.headersOf
import io.ktor.websocket.Frame
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProtocolTest {

    private val id = UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L)
    private val idBytes = ByteArray(16) { (it + 1).toByte() }
    private val zeroIdBytes = ByteArray(16)

    private val headers = Headers.build {
        append("Set-Cookie", "a=1")
        append("Set-Cookie", "b=2")
        append("Content-Type", "text/plain")
        append("Set-Cookie", "c=3")
        append("X-Title", "Übermäßig 日本語 🎵")
    }

    private fun bytes(vararg parts: Any): ByteArray = parts.fold(ByteArray(0)) { acc, part ->
        acc + when (part) {
            is Int -> byteArrayOf(part.toByte())
            is ByteArray -> part
            is String -> part.toByteArray(Charsets.UTF_8)
            else -> error("Unsupported part $part")
        }
    }

    private fun roundTrip(message: ProxyMessage): ProxyMessage? = ProxyMessage.fromFrame(message.toFrame())

    private fun decode(data: ByteArray): ProxyMessage? = ProxyMessage.fromFrame(Frame.Binary(true, data))

    private fun assertEveryTruncationIsNull(data: ByteArray, upTo: Int = data.size) {
        for (length in 0 until upTo) {
            assertNull(decode(data.copyOf(length)), "length $length of ${data.size}")
        }
    }

    @Test
    fun `all messages survive a round trip`() {
        val messages = listOf(
            ProxyMessage.NewClient(id, "/rpc/services?x=1", mapOf("Authorization" to "Bearer t", "X-A" to "ä")),
            ProxyMessage.ClientFrame(id, byteArrayOf(1, 2, 3), true),
            ProxyMessage.ClientFrame(id, "text".toByteArray(), false),
            ProxyMessage.ClientDisconnected(id),
            ProxyMessage.AssignedId("abcd1234"),
            ProxyMessage.Ping,
            ProxyMessage.Pong,
            ProxyMessage.HttpRequest(id, "GET", "/api/songs?q=ä", headers, true),
            ProxyMessage.HttpResponse(id, 206, headers, false),
            ProxyMessage.HttpBody(id, byteArrayOf(9, 8, 7), true),
            ProxyMessage.HttpReset(id, HttpResetReason.REFUSED),
            ProxyMessage.HttpWindow(id, 65536)
        )

        assertEquals(11, messages.map { it::class }.toSet().size)
        messages.forEach { assertEquals(it, roundTrip(it)) }
        assertSame(ProxyMessage.Ping, roundTrip(ProxyMessage.Ping))
        assertSame(ProxyMessage.Pong, roundTrip(ProxyMessage.Pong))
    }

    @Test
    fun `multi valued headers keep order and count`() {
        val decoded = roundTrip(ProxyMessage.HttpRequest(id, "POST", "/x", headers, false)) as ProxyMessage.HttpRequest

        assertEquals(listOf("a=1", "b=2", "c=3"), decoded.headers.getAll("Set-Cookie"))
        assertEquals(listOf("text/plain"), decoded.headers.getAll("Content-Type"))
        assertEquals(listOf("Set-Cookie", "Content-Type", "X-Title"), decoded.headers.entries().map { it.key })
        assertEquals(5, decoded.headers.entries().sumOf { it.value.size })
    }

    @Test
    fun `non ASCII header values and URIs are preserved`() {
        val uri = "/api/search?q=Bjørk/Ünïcödé/日本語/🎵"
        val request = roundTrip(ProxyMessage.HttpRequest(id, "GET", uri, headers, true)) as ProxyMessage.HttpRequest
        val response = roundTrip(ProxyMessage.HttpResponse(id, 200, headers, true)) as ProxyMessage.HttpResponse

        assertEquals(uri, request.uri)
        assertEquals("Übermäßig 日本語 🎵", request.headers["X-Title"])
        assertEquals("Übermäßig 日本語 🎵", response.headers["X-Title"])
    }

    @Test
    fun `request and response without headers`() {
        val request = ProxyMessage.HttpRequest(id, "DELETE", "", Headers.build {}, true)
        val response = ProxyMessage.HttpResponse(id, 204, Headers.build {}, true)
        val fromEmpty = roundTrip(ProxyMessage.HttpRequest(id, "DELETE", "", Headers.Empty, true))

        assertEquals(request, roundTrip(request))
        assertEquals(response, roundTrip(response))
        assertEquals(request, fromEmpty)
        assertTrue((fromEmpty as ProxyMessage.HttpRequest).headers.isEmpty())
    }

    @Test
    fun `empty and full sized bodies`() {
        val empty = roundTrip(ProxyMessage.HttpBody(id, ByteArray(0), true)) as ProxyMessage.HttpBody
        val content = ByteArray(65536) { (it * 31).toByte() }
        val full = roundTrip(ProxyMessage.HttpBody(id, content, false)) as ProxyMessage.HttpBody

        assertEquals(0, empty.data.size)
        assertTrue(empty.last)
        assertContentEquals(content, full.data)
        assertEquals(18 + 65536, ProxyMessage.HttpBody(id, content, false).toFrame().data.size)
    }

    @Test
    fun `flags in both states`() {
        for (flag in listOf(true, false)) {
            val request = ProxyMessage.HttpRequest(id, "PUT", "/x", headers, flag)
            val response = ProxyMessage.HttpResponse(id, 200, headers, flag)
            val body = ProxyMessage.HttpBody(id, byteArrayOf(1), flag)
            val expected = if (flag) 1 else 0

            assertEquals(flag, (roundTrip(request) as ProxyMessage.HttpRequest).noBody)
            assertEquals(flag, (roundTrip(response) as ProxyMessage.HttpResponse).noBody)
            assertEquals(flag, (roundTrip(body) as ProxyMessage.HttpBody).last)
            assertEquals(expected, request.toFrame().data[17].toInt())
            assertEquals(expected, response.toFrame().data[17].toInt())
            assertEquals(expected, body.toFrame().data[17].toInt())
        }
    }

    @Test
    fun `only bit 0 of the sub type is a flag`() {
        val set = decode(bytes(8, idBytes, 0xFF, "x")) as ProxyMessage.HttpBody
        val clear = decode(bytes(8, idBytes, 0xFE, "x")) as ProxyMessage.HttpBody

        assertTrue(set.last)
        assertEquals(false, clear.last)
    }

    @Test
    fun `every reset reason has its stable code`() {
        val codes = mapOf(
            HttpResetReason.CANCEL to 0,
            HttpResetReason.REFUSED to 1,
            HttpResetReason.INTERNAL to 2,
            HttpResetReason.PROTOCOL to 3,
            HttpResetReason.HEAD_TOO_LARGE to 4
        )

        assertEquals(HttpResetReason.entries.toSet(), codes.keys)
        codes.forEach { (reason, code) ->
            val frame = ProxyMessage.HttpReset(id, reason).toFrame()
            assertContentEquals(bytes(9, idBytes, code), frame.data)
            assertEquals(ProxyMessage.HttpReset(id, reason), decode(bytes(9, idBytes, code)))
        }
    }

    @Test
    fun `unknown reset reason codes decode to INTERNAL`() {
        for (code in listOf(5, 6, 127, 128, 255)) {
            assertEquals(ProxyMessage.HttpReset(id, HttpResetReason.INTERNAL), decode(bytes(9, idBytes, code)))
        }
    }

    @Test
    fun `http messages have the documented layout`() {
        val two = Headers.build {
            append("A", "1")
            append("A", "é")
        }

        assertContentEquals(
            bytes(
                6, idBytes, 1,
                3, "GET",
                0, 0, 0, 4, "/a?b",
                0, 2,
                0, 1, "A", 0, 0, 0, 1, "1",
                0, 1, "A", 0, 0, 0, 2, 0xC3, 0xA9
            ),
            ProxyMessage.HttpRequest(id, "GET", "/a?b", two, true).toFrame().data
        )
        assertContentEquals(
            bytes(7, idBytes, 0, 0x01, 0x94, 0, 1, 0, 2, "Ab", 0, 0, 0, 0),
            ProxyMessage.HttpResponse(id, 404, headersOf("Ab", ""), false).toFrame().data
        )
        assertContentEquals(
            bytes(8, idBytes, 1, 5, 6),
            ProxyMessage.HttpBody(id, byteArrayOf(5, 6), true).toFrame().data
        )
        assertContentEquals(
            bytes(10, idBytes, 0, 0, 1, 0, 0),
            ProxyMessage.HttpWindow(id, 65536).toFrame().data
        )
    }

    @Test
    fun `window increment covers the unsigned 32 bit range`() {
        assertEquals(ProxyMessage.HttpWindow(id, 0), roundTrip(ProxyMessage.HttpWindow(id, 0)))
        assertEquals(
            ProxyMessage.HttpWindow(id, 0xFFFFFFFFL),
            decode(bytes(10, idBytes, 0, 0xFF, 0xFF, 0xFF, 0xFF))
        )
        assertEquals(ProxyMessage.HttpWindow(id, 0xFFFFFFFFL), roundTrip(ProxyMessage.HttpWindow(id, 0xFFFFFFFFL)))
        assertFailsWith<IllegalArgumentException> { ProxyMessage.HttpWindow(id, 0x100000000L).toFrame() }
        assertFailsWith<IllegalArgumentException> { ProxyMessage.HttpWindow(id, -1).toFrame() }
    }

    @Test
    fun `legacy messages keep their exact bytes`() {
        val json = """{"uri":"/rpc","headers":{"A":"b"}}"""

        assertContentEquals(
            bytes(0, idBytes, 0, json),
            ProxyMessage.NewClient(id, "/rpc", mapOf("A" to "b")).toFrame().data
        )
        assertContentEquals(
            bytes(1, idBytes, 1, 0x00, 0x7F, 0x80, 0xFF),
            ProxyMessage.ClientFrame(id, byteArrayOf(0, 127, -128, -1), true).toFrame().data
        )
        assertContentEquals(
            bytes(1, idBytes, 0, "hi"),
            ProxyMessage.ClientFrame(id, "hi".toByteArray(), false).toFrame().data
        )
        assertContentEquals(bytes(2, idBytes, 0), ProxyMessage.ClientDisconnected(id).toFrame().data)
        assertContentEquals(bytes(3, zeroIdBytes, 0, "abcd1234"), ProxyMessage.AssignedId("abcd1234").toFrame().data)
        assertContentEquals(bytes(4, zeroIdBytes, 0), ProxyMessage.Ping.toFrame().data)
        assertContentEquals(bytes(5, zeroIdBytes, 0), ProxyMessage.Pong.toFrame().data)
    }

    @Test
    fun `legacy bytes decode to their messages`() {
        val json = """{"uri":"/rpc","headers":{"A":"b"}}"""

        assertEquals(ProxyMessage.NewClient(id, "/rpc", mapOf("A" to "b")), decode(bytes(0, idBytes, 0, json)))
        assertEquals(ProxyMessage.ClientFrame(id, byteArrayOf(7), true), decode(bytes(1, idBytes, 1, 7)))
        assertEquals(ProxyMessage.ClientFrame(id, byteArrayOf(7), false), decode(bytes(1, idBytes, 0, 7)))
        assertEquals(ProxyMessage.ClientDisconnected(id), decode(bytes(2, idBytes, 0)))
        assertEquals(ProxyMessage.AssignedId("abcd1234"), decode(bytes(3, zeroIdBytes, 0, "abcd1234")))
        assertSame(ProxyMessage.Ping, decode(bytes(4, zeroIdBytes, 0)))
        assertSame(ProxyMessage.Pong, decode(bytes(5, zeroIdBytes, 0)))
    }

    @Test
    fun `non binary and empty frames return null`() {
        assertNull(ProxyMessage.fromFrame(Frame.Text("hello")))
        assertNull(ProxyMessage.fromFrame(Frame.Close()))
        assertNull(decode(ByteArray(0)))
    }

    @Test
    fun `frames shorter than the prefix return null for every type`() {
        for (type in 0..10) {
            assertEveryTruncationIsNull(bytes(type, idBytes, 0))
        }
    }

    @Test
    fun `truncated payloads return null`() {
        val json = """{"uri":"/rpc","headers":{"A":"b"}}"""

        assertEveryTruncationIsNull(bytes(0, idBytes, 0, json))
        assertEveryTruncationIsNull(ProxyMessage.HttpRequest(id, "GET", "/api/ä", headers, true).toFrame().data)
        assertEveryTruncationIsNull(ProxyMessage.HttpRequest(id, "GET", "/", Headers.Empty, true).toFrame().data)
        assertEveryTruncationIsNull(ProxyMessage.HttpResponse(id, 200, headers, false).toFrame().data)
        assertEveryTruncationIsNull(ProxyMessage.HttpResponse(id, 200, Headers.Empty, false).toFrame().data)
        assertEveryTruncationIsNull(ProxyMessage.HttpWindow(id, 65536).toFrame().data)
        assertEveryTruncationIsNull(ProxyMessage.HttpBody(id, byteArrayOf(1, 2, 3), true).toFrame().data, 18)
        assertEveryTruncationIsNull(ProxyMessage.HttpReset(id, HttpResetReason.CANCEL).toFrame().data)
    }

    @Test
    fun `lengths beyond the remaining bytes return null`() {
        assertNull(decode(bytes(6, idBytes, 0, 200, "GET", 0, 0, 0, 1, "/", 0, 0)))
        assertNull(decode(bytes(6, idBytes, 0, 3, "GET", 0xFF, 0xFF, 0xFF, 0xFF, "/", 0, 0)))
        assertNull(decode(bytes(6, idBytes, 0, 3, "GET", 0x7F, 0xFF, 0xFF, 0xFF, "/", 0, 0)))
        assertNull(decode(bytes(6, idBytes, 0, 3, "GET", 0, 0, 0, 1, "/", 0xFF, 0xFF)))
        assertNull(decode(bytes(6, idBytes, 0, 3, "GET", 0, 0, 0, 1, "/", 0, 1, 0xFF, 0xFF, "A")))
        assertNull(decode(bytes(7, idBytes, 0, 0, 200, 0, 1, 0, 1, "A", 0xFF, 0xFF, 0xFF, 0xFF, "v")))
        assertNull(decode(bytes(7, idBytes, 0, 0, 200, 0, 2, 0, 1, "A", 0, 0, 0, 1, "v")))
    }

    @Test
    fun `trailing bytes and invalid content return null`() {
        assertNotNull(decode(bytes(6, idBytes, 0, 3, "GET", 0, 0, 0, 1, "/", 0, 0)))
        assertNull(decode(bytes(6, idBytes, 0, 3, "GET", 0, 0, 0, 1, "/", 0, 0, 1)))
        assertNull(decode(bytes(6, idBytes, 0, 0, 0, 0, 0, 1, "/", 0, 0)))
        assertNotNull(decode(bytes(7, idBytes, 0, 0, 200, 0, 0)))
        assertNull(decode(bytes(7, idBytes, 0, 0, 200, 0, 0, 1)))
        assertNull(decode(bytes(7, idBytes, 0, 0, 200, 0, 1, 0, 3, "a b", 0, 0, 0, 1, "v")))
        assertNull(decode(bytes(7, idBytes, 0, 0, 200, 0, 1, 0, 1, "A", 0, 0, 0, 3, "a\nb")))
        assertNull(decode(bytes(9, idBytes, 0, 1)))
        assertNull(decode(bytes(10, idBytes, 0, 0, 0, 0, 1, 0)))
        assertNull(decode(bytes(8, idBytes, 0, ByteArray(65537))))
    }

    @Test
    fun `unknown types return null`() {
        for (type in listOf(11, 12, 100, 127, 128, 255)) {
            assertNull(decode(bytes(type, idBytes, 0)))
            assertNull(decode(bytes(type, idBytes, 0, "payload")))
        }
    }

    @Test
    fun `malformed NewClient JSON returns null`() {
        for (json in listOf("", "{", "not json", "[]", "null", """{"uri":"/rpc"}""", """{"uri":1,"headers":[]}""")) {
            assertNull(decode(bytes(0, idBytes, 0, json)), json)
        }
        assertNull(decode(bytes(0, idBytes, 0, 0xFF, 0xFE, 0x00)))
    }

    @Test
    fun `encoding rejects values over its limits`() {
        val longName = Headers.build { append("X".repeat(65536), "v") }
        val maxName = Headers.build { append("X".repeat(65535), "v") }

        assertFailsWith<IllegalArgumentException> {
            ProxyMessage.HttpRequest(id, "M".repeat(256), "/", Headers.Empty, true).toFrame()
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyMessage.HttpRequest(id, "", "/", Headers.Empty, true).toFrame()
        }
        assertFailsWith<IllegalArgumentException> {
            ProxyMessage.HttpRequest(id, "GÉT", "/", Headers.Empty, true).toFrame()
        }
        assertFailsWith<IllegalArgumentException> { ProxyMessage.HttpRequest(id, "GET", "/", longName, true).toFrame() }
        assertFailsWith<IllegalArgumentException> { ProxyMessage.HttpResponse(id, 200, longName, true).toFrame() }
        assertFailsWith<IllegalArgumentException> { ProxyMessage.HttpResponse(id, 65536, Headers.Empty, true).toFrame() }
        assertFailsWith<IllegalArgumentException> { ProxyMessage.HttpBody(id, ByteArray(65537), true).toFrame() }

        val longMethod = ProxyMessage.HttpRequest(id, "M".repeat(255), "/", maxName, true)
        assertEquals(longMethod, roundTrip(longMethod))
    }

    @Test
    fun `head size is the encoded payload size`() {
        val request = ProxyMessage.HttpRequest(id, "GET", "/api/search?q=日本語", headers, true)
        val response = ProxyMessage.HttpResponse(id, 200, headers, false)
        val oversized = ProxyMessage.HttpResponse(id, 200, Headers.build { append("X".repeat(70000), "v") }, false)

        assertEquals(request.toFrame().data.size - 18, request.headSize())
        assertEquals(response.toFrame().data.size - 18, response.headSize())
        assertEquals(2 + 2, ProxyMessage.HttpResponse(id, 200, Headers.Empty, true).headSize())
        assertEquals(1 + 3 + 4 + 1 + 2, ProxyMessage.HttpRequest(id, "GET", "/", Headers.Empty, true).headSize())
        assertEquals(2 + 2 + 2 + 70000 + 4 + 1, oversized.headSize())
        assertTrue(oversized.headSize() > HttpTunnel.MAX_HEAD_SIZE)
    }

    @Test
    fun `stream id uses msb then lsb big endian`() {
        val frame = ProxyMessage.HttpWindow(id, 1).toFrame().data
        val buffer = ByteBuffer.wrap(frame, 1, 16)

        assertEquals(id.mostSignificantBits, buffer.getLong())
        assertEquals(id.leastSignificantBits, buffer.getLong())
    }
}
