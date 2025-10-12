package dev.dertyp.core

import java.util.*

fun String.toUUIDOrNull(): UUID? {
    return try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun String.capitalize(): String = replaceFirstChar { it.lowercase() }
fun String.oneLine(joiner: String = ""): String = split(Regex("[\n\r]")).joinToString(joiner)