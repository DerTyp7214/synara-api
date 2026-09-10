package dev.dertyp.mcp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpTimeTest {
    @Test
    fun `parseMcpTime returns epoch millis directly for digit string regardless of zone`() {
        assertEquals(1700000000000L, parseMcpTime("1700000000000", ZoneOffset.UTC))
        assertEquals(1700000000000L, parseMcpTime("1700000000000", ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun `parseMcpTime parses negative epoch millis string`() {
        assertEquals(-5L, parseMcpTime("-5", ZoneOffset.UTC))
    }

    @Test
    fun `parseMcpTime parses date-only string as local midnight in given zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val expected = LocalDate.of(2024, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, parseMcpTime("2024-03-01", zone))
    }

    @Test
    fun `parseMcpTime parses offset date-time string with Z suffix`() {
        val expected = Instant.parse("2024-03-01T12:00:00Z").toEpochMilli()
        assertEquals(expected, parseMcpTime("2024-03-01T12:00:00Z", ZoneOffset.UTC))
    }

    @Test
    fun `parseMcpTime parses local date-time without offset using given zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val expected = LocalDateTime.of(2024, 3, 1, 12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, parseMcpTime("2024-03-01T12:00", zone))
    }

    @Test
    fun `parseMcpTime parses date-time string with explicit offset`() {
        val expected = OffsetDateTime.parse("2024-03-01T12:00:00+02:00").toInstant().toEpochMilli()
        assertEquals(expected, parseMcpTime("2024-03-01T12:00:00+02:00", ZoneOffset.UTC))
    }

    @Test
    fun `parseMcpTime throws IllegalArgumentException for invalid string`() {
        assertFailsWith<IllegalArgumentException> { parseMcpTime("not-a-real-timestamp", ZoneOffset.UTC) }
    }

    @Test
    fun `mcpZone returns UTC for null timezone`() {
        assertEquals(ZoneOffset.UTC, mcpZone(null))
    }

    @Test
    fun `mcpZone returns UTC for blank timezone`() {
        assertEquals(ZoneOffset.UTC, mcpZone(""))
        assertEquals(ZoneOffset.UTC, mcpZone("   "))
    }

    @Test
    fun `mcpZone falls back to UTC for invalid zone id`() {
        assertEquals(ZoneOffset.UTC, mcpZone("Not/AZone"))
    }

    @Test
    fun `mcpZone returns zone for valid IANA id`() {
        assertEquals(ZoneId.of("Europe/Berlin"), mcpZone("Europe/Berlin"))
    }

    @Test
    fun `mcpTime computes epochMs and iso string for given zone`() {
        val zone = ZoneId.of("Europe/Berlin")
        val epochMs = Instant.parse("2024-06-15T10:00:00Z").toEpochMilli()
        val expectedIso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))
        val result = mcpTime(epochMs, zone)
        assertEquals(epochMs, result.epochMs)
        assertEquals(expectedIso, result.iso)
    }

    @Test
    fun `mcpTime iso string reflects Berlin winter offset`() {
        val zone = ZoneId.of("Europe/Berlin")
        val epochMs = Instant.parse("2024-01-15T12:00:00Z").toEpochMilli()
        val result = mcpTime(epochMs, zone)
        assertTrue(result.iso.endsWith("+01:00"))
    }
}
