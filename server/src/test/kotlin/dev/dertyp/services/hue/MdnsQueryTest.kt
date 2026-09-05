package dev.dertyp.services.hue

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MdnsQueryTest {
    @Test
    fun `query is a standard PTR question with the unicast-response bit`() {
        val packet = MdnsQuery.buildQuery("_hue._tcp.local.")
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0), packet.copyOf(12))
        val name = packet.copyOfRange(12, packet.size - 4)
        val expected = byteArrayOf(4) + "_hue".toByteArray() + byteArrayOf(4) + "_tcp".toByteArray() + byteArrayOf(5) + "local".toByteArray() + byteArrayOf(0)
        assertArrayEquals(expected, name)
        val tail = packet.copyOfRange(packet.size - 4, packet.size)
        assertEquals(12, (tail[0].toInt() shl 8) or (tail[1].toInt() and 0xFF))
        assertEquals(0x8001, ((tail[2].toInt() and 0xFF) shl 8) or (tail[3].toInt() and 0xFF))
    }

    @Test
    fun `response detection looks at the QR flag`() {
        assertFalse(MdnsQuery.isResponse(MdnsQuery.buildQuery("_hue._tcp.local.")))
        assertTrue(MdnsQuery.isResponse(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0)))
        assertFalse(MdnsQuery.isResponse(byteArrayOf(0, 0, 0x84.toByte())))
    }
}
