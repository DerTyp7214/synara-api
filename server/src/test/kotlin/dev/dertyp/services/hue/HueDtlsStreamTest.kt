package dev.dertyp.services.hue

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.DatagramSocket

class HueDtlsStreamTest {
    @Test
    fun `a client key decodes to sixteen bytes`() {
        val bytes = HueDtlsStream.pskBytes("00112233445566778899aabbccddeeff")

        assertEquals(16, bytes.size)
        assertArrayEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte()),
            bytes,
        )
    }

    @Test
    fun `an upper case client key decodes the same way`() {
        assertArrayEquals(
            HueDtlsStream.pskBytes("00112233445566778899aabbccddeeff"),
            HueDtlsStream.pskBytes("00112233445566778899AABBCCDDEEFF"),
        )
    }

    @Test
    fun `client keys of another length are rejected`() {
        assertThrows<HueBridgeException> { HueDtlsStream.pskBytes("") }
        assertThrows<HueBridgeException> { HueDtlsStream.pskBytes("00112233445566778899aabbccddee") }
        assertThrows<HueBridgeException> { HueDtlsStream.pskBytes("00112233445566778899aabbccddeeff00") }
    }

    @Test
    fun `a client key that is not hexadecimal is rejected`() {
        assertThrows<HueBridgeException> { HueDtlsStream.pskBytes("00112233445566778899aabbccddeezz") }
    }

    @Test
    fun `a handshake against a closed port fails fast`() {
        val probe = DatagramSocket(0)
        val port = probe.localPort
        probe.close()
        val stream = HueDtlsStream("127.0.0.1", "application-key", "00112233445566778899aabbccddeeff", port, handshakeTimeoutMs = 500)

        val startedAt = System.currentTimeMillis()
        val error = assertThrows<HueBridgeException> { runBlocking { stream.start() } }
        val elapsed = System.currentTimeMillis() - startedAt
        stream.close()

        assertTrue(elapsed < 4_000, "the handshake should give up quickly but took $elapsed ms")
        assertTrue(error.message.orEmpty().startsWith("Entertainment handshake with 127.0.0.1 failed"), error.message)
    }
}
