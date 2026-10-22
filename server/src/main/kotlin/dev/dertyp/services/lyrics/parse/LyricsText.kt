package dev.dertyp.services.lyrics.parse

import java.lang.Character.UnicodeScript

private val completeTag = Regex("<[A-Za-z/!?][^<>]*>")
private val tagOpener = Regex("<(?=[A-Za-z/!?])")
private val htmlEntity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
private val namedEntities = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
)
private val cjkScripts = setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HANGUL)

internal val whitespaceRun = Regex("\\s+")

internal fun Char.opensTag(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this == '/' || this == '!' || this == '?'

fun stripHtmlTags(text: String): String {
    var current = text
    while (true) {
        val next = current.replace(completeTag, "").replace(tagOpener, "")
        if (next == current) return current
        current = next
    }
}

fun containsHtmlTagOpener(text: String): Boolean = tagOpener.containsMatchIn(text)

fun htmlUnescape(text: String): String = text.replace(htmlEntity) { match ->
    val entity = match.groupValues[1]
    when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.codePointString()
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.codePointString()
        else -> namedEntities[entity.lowercase()]
    } ?: match.value
}

private fun Int.codePointString(): String? =
    if (Character.isValidCodePoint(this) && this != 0) String(Character.toChars(this)) else null

fun containsCjk(text: String): Boolean = text.codePoints().anyMatch { UnicodeScript.of(it) in cjkScripts }

fun isHanOnly(text: String): Boolean {
    var han = false
    for (codePoint in text.codePoints().toArray()) {
        val script = UnicodeScript.of(codePoint)
        if (script == UnicodeScript.HAN) han = true
        else if (Character.isLetterOrDigit(codePoint)) return false
    }
    return han
}
