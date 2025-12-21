package dev.dertyp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun getDateFromISO(iso: String?): LocalDate? {
    return if (iso == null) null else LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
}

fun getDateTimeFromISO(iso: String?): LocalDateTime? {
    return if (iso == null) null else LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}

fun getISOFromDate(date: LocalDate?): String? {
    return if (date == null) null else DateTimeFormatter.ISO_LOCAL_DATE.format(date)
}

fun getISOFromDateTime(date: LocalDateTime): String {
    return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(date)
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    suspendTransaction { withContext(Dispatchers.IO) { block() } }