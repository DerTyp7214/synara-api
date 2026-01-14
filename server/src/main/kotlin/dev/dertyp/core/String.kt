package dev.dertyp.core

import io.ktor.http.*
import java.text.Normalizer
import java.util.*

fun String.toUUIDOrNull(): UUID? {
    return try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun String.isURL(): Boolean {
    return try {
        Url(this)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
}

fun String.capitalize(): String = replaceFirstChar { it.lowercase() }
fun String.oneLine(joiner: String = ""): String = split(Regex("[\n\r]")).joinToString(joiner)
fun String.tidalId(): String = Url(this).tidalId()

fun String.stripAccents(): String {
    val normalizer = Normalizer.normalize(this, Normalizer.Form.NFD)
    val accentRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    return accentRegex.replace(normalizer, "")
}