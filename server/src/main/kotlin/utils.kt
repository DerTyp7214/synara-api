package dev.dertyp

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun getDateFromISO(iso: String): LocalDateTime {
    return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

fun getISOFromDate(date: LocalDateTime): String {
    return DateTimeFormatter.ISO_LOCAL_DATE.format(date)
}