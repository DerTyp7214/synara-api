package dev.dertyp

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun getDateFromISO(iso: String?): LocalDate? {
    return if (iso == null) null else LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
}

fun getISOFromDate(date: LocalDate?): String? {
    return if (date == null) null else DateTimeFormatter.ISO_LOCAL_DATE.format(date)
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }