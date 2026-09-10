package dev.dertyp.mcp

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val EPOCH_MILLIS = Regex("-?\\d+")

fun parseMcpTime(value: String, zone: ZoneId): Long {
    val trimmed = value.trim()
    if (trimmed.matches(EPOCH_MILLIS)) {
        trimmed.toLongOrNull()?.let { return it }
    }

    runCatching { LocalDate.parse(trimmed) }.getOrNull()?.let { return it.atStartOfDay(zone).toInstant().toEpochMilli() }
    runCatching { OffsetDateTime.parse(trimmed) }.getOrNull()?.let { return it.toInstant().toEpochMilli() }
    runCatching { Instant.parse(trimmed) }.getOrNull()?.let { return it.toEpochMilli() }
    runCatching { LocalDateTime.parse(trimmed) }.getOrNull()?.let { return it.atZone(zone).toInstant().toEpochMilli() }

    throw IllegalArgumentException("Invalid time '$value': expected ISO-8601 or epoch milliseconds")
}

fun mcpTime(epochMs: Long, zone: ZoneId): McpTime = McpTime(
    epochMs = epochMs,
    iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone)),
)

fun mcpZone(timezone: String?): ZoneId = timezone
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    ?: ZoneOffset.UTC
