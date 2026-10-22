package dev.dertyp.services.lyrics.parse

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object LyricsPlusParser {
    private val timedTypes = setOf("word", "line")

    private data class Syllable(val text: String, val start: Duration?, val end: Duration?, val background: Boolean)

    fun parse(json: String): ParsedLyrics? {
        val root = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val timed = root.string("type")?.lowercase() in timedTypes
        val entries = root["lyrics"] as? JsonArray ?: return null

        val lines = mutableListOf<RawLine>()
        entries.forEachIndexed { group, element ->
            val entry = element as? JsonObject ?: return@forEachIndexed
            val text = entry.string("text")
            val syllables = (entry["syllabus"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { syllable ->
                val start = syllable.millis("time")
                Syllable(
                    text = syllable.string("text").orEmpty(),
                    start = start,
                    end = syllable.millis("duration")?.let { duration -> start?.plus(duration) },
                    background = (syllable["isBackground"] as? JsonPrimitive)?.booleanOrNull == true,
                )
            }
            val lineText = text ?: syllables.filterNot { it.background }.joinToString("") { it.text }
            if (!timed) {
                lines += RawLine(start = null, text = lineText, group = group)
                return@forEachIndexed
            }
            val start = entry.millis("time") ?: syllables.firstOrNull()?.start
            val end = entry.millis("duration")?.let { duration -> start?.plus(duration) }
            lines += timedLines(start, end, text, lineText, syllables, group)
        }
        return LyricsAssembler.assemble(lines)
    }

    private fun timedLines(start: Duration?, end: Duration?, text: String?, lineText: String, syllables: List<Syllable>, group: Int): List<RawLine> {
        val aligned = alignedSyllables(syllables, text)
        val trusted = start != null && aligned != null && aligned.any { !it.background } &&
            aligned.all { it.start != null && it.end != null && it.start >= start }
        if (!trusted) return listOf(RawLine(start = start, end = end, text = lineText, group = group))

        val lead = aligned.filterNot { it.background }
        val backing = aligned.filter { it.background }
        return listOfNotNull(
            RawLine(start = start, end = end, words = lead.map { RawWord(it.text, it.start, it.end) }, group = group),
            backing.takeIf { it.isNotEmpty() }?.let { words ->
                RawLine(
                    start = words.first().start,
                    end = words.mapNotNull { it.end }.maxOrNull(),
                    words = words.map { RawWord(it.text, it.start, it.end) },
                    background = true,
                    group = group,
                )
            },
        )
    }

    private fun alignedSyllables(syllables: List<Syllable>, text: String?): List<Syllable>? {
        if (syllables.isEmpty()) return null
        if (text.isNullOrBlank()) return syllables
        val lead = syllables.filterNot { it.background }
        align(lead, text)?.let { alignedLead ->
            val backing = syllables.filter { it.background }
            return alignedLead + backing
        }
        return align(syllables, text)
    }

    private fun align(syllables: List<Syllable>, text: String): List<Syllable>? {
        var position = 0
        val result = syllables.map { syllable ->
            val core = syllable.text.filterNot { it.isWhitespace() }
            while (position < text.length && text[position].isWhitespace()) position++
            val begin = position
            var matched = 0
            while (matched < core.length) {
                if (position >= text.length) return null
                val char = text[position]
                if (char.isWhitespace()) {
                    position++
                    continue
                }
                if (char != core[matched]) return null
                matched++
                position++
            }
            while (position < text.length && text[position].isWhitespace()) position++
            syllable.copy(text = text.substring(begin, position))
        }
        if (text.substring(position).isNotBlank()) return null
        return result
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.millis(key: String): Duration? {
        val value = (this[key] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!value.isFinite() || value < 0) return null
        return value.roundToLong().milliseconds
    }
}
