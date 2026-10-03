package dev.dertyp.core.date

import org.jetbrains.exposed.v1.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun getDateFromISO(iso: String?): LocalDate? {
    if (iso.isNullOrBlank()) return null
    return try {
        if (iso.length == 4 && iso.all { it.isDigit() }) {
            LocalDate.parse("$iso-01-01", DateTimeFormatter.ISO_LOCAL_DATE)
        } else {
            LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE)
        }
    } catch (_: Exception) {
        null
    }
}

private val asciiDigits = ('0'..'9').map { it.toString() }

private fun twoDigits(values: IntRange) = values.map { it.toString().padStart(2, '0') }

private val twoDigitMultiplesOfFour = twoDigits(0..99).filter { it.toInt() % 4 == 0 }

private val longMonths = listOf("01", "03", "05", "07", "08", "10", "12")

fun isoDateKey(iso: Expression<String?>): Expression<String> {
    val length = CustomFunction("length", IntegerColumnType(), iso)
    fun part(start: Int, count: Int) = Substring(iso, intLiteral(start), intLiteral(count))
    fun digits(positions: IntRange): Op<Boolean> =
        positions.map<Int, Op<Boolean>> { part(it, 1) inList asciiDigits }.reduce { acc, op -> acc and op }

    val month = part(6, 2)
    val day = part(9, 2)
    val leapYear = (part(3, 2) inList twoDigitMultiplesOfFour) and
            ((part(3, 2) neq "00") or (part(1, 2) inList twoDigitMultiplesOfFour))
    val validDay = (day inList twoDigits(1..28)) or
            ((day eq "29") and ((month neq "02") or leapYear)) or
            ((day eq "30") and (month neq "02")) or
            ((day eq "31") and (month inList longMonths))
    val yearOnly = (length eq 4) and digits(1..4)
    val fullDate = (length eq 10) and digits(1..4) and (part(5, 1) eq "-") and digits(6..7) and
            (part(8, 1) eq "-") and digits(9..10) and (month inList twoDigits(1..12)) and validDay
    return case()
        .When(yearOnly, concat(iso, stringLiteral("-01-01")))
        .When(fullDate, concat(iso, stringLiteral("")))
        .Else(Op.nullOp())
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
