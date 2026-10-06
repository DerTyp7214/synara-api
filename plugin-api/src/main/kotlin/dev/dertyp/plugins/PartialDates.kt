package dev.dertyp.plugins

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

fun parsePartialDate(value: String?): LocalDate? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val iso = when {
        raw.length >= 10 -> raw.substring(0, 10)
        raw.length == 7 -> "$raw-01"
        raw.length == 4 -> "$raw-01-01"
        else -> return null
    }
    return try {
        LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
}
