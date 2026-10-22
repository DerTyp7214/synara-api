package dev.dertyp.services.lyrics.parse

import dev.dertyp.core.normalizedKey
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object KrcParser {
    const val MAX_INFLATED_BYTES = 4 * 1024 * 1024

    private val magic = "krc1".toByteArray(Charsets.US_ASCII)
    private val key = intArrayOf(0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47, 0x51, 0x36, 0x31, 0x2d, 0xce, 0xd2, 0x6e, 0x69)
        .map { it.toByte() }.toByteArray()
    private val lineHeader = Regex("^\\[(\\d+),(\\d+)]")
    private val syllable = Regex("<(\\d+),(\\d+),-?\\d+>")
    private val metadataTag = Regex("^\\[([A-Za-z#]+):(.*)]$")
    private val titleArtistLine = Regex("^(.+?)\\s+-\\s+(.+)$")
    private val creditLine = Regex("^[^：:]{1,16}[：:]\\s*\\S.*$")
    private val lineBreak = Regex("\r\n|\r|\n")
    private const val INSTRUMENTAL_MARKER = "纯音乐"

    private data class KrcLine(val start: Duration, val end: Duration, val words: List<RawWord>, val text: String)

    fun parse(blob: ByteArray, requestIsCjk: Boolean): ParsedLyrics? {
        val text = decode(blob) ?: return null
        return parseText(text, requestIsCjk)
    }

    fun decode(blob: ByteArray): String? {
        if (blob.size <= magic.size || !blob.copyOfRange(0, magic.size).contentEquals(magic)) return null
        val payload = ByteArray(blob.size - magic.size) { i -> (blob[i + magic.size].toInt() xor key[i % key.size].toInt()).toByte() }
        val inflater = Inflater()
        return try {
            inflater.setInput(payload)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                output.write(buffer, 0, count)
                if (output.size() > MAX_INFLATED_BYTES) return null
            }
            output.toString(Charsets.UTF_8).removePrefix("﻿")
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    fun parseText(krc: String, requestIsCjk: Boolean): ParsedLyrics? {
        val tags = mutableMapOf<String, String>()
        val lines = mutableListOf<KrcLine>()
        for (rawLine in krc.removePrefix("﻿").split(lineBreak)) {
            val line = rawLine.trim()
            val header = lineHeader.find(line)
            if (header == null) {
                metadataTag.matchEntire(line)?.let { tags.putIfAbsent(it.groupValues[1].lowercase(), it.groupValues[2].trim()) }
                continue
            }
            val start = header.groupValues[1].toLong().milliseconds
            val end = start + header.groupValues[2].toLong().milliseconds
            lines += lineOf(start, end, line.substring(header.range.last + 1))
        }

        val title = tags["ti"]?.takeIf { it.isNotEmpty() }
        val artist = tags["ar"]?.takeIf { it.isNotEmpty() }
        var kept = lines.filter { it.text.isNotBlank() }
        if (kept.firstOrNull()?.let { isTitleArtistLine(it.text, title, artist) } == true) kept = kept.drop(1)
        kept = kept.dropWhile { isCreditLine(it.text) }.dropLastWhile { isCreditLine(it.text) }

        val instrumental = kept.size == 1 && kept.single().text.contains(INSTRUMENTAL_MARKER)
        if (instrumental) kept = emptyList()
        if (!requestIsCjk && isTranslationAlternation(kept)) kept = kept.filterIndexed { i, _ -> i % 2 == 0 }

        val offset = tags["offset"]?.removePrefix("+")?.toLongOrNull()?.milliseconds ?: Duration.ZERO
        val shift = { time: Duration -> (time - offset).coerceAtLeast(Duration.ZERO) }
        val raw = kept.mapIndexed { i, line ->
            RawLine(
                start = shift(line.start),
                end = shift(line.end),
                text = if (line.words.isEmpty()) line.text else null,
                words = line.words.map { word -> word.copy(start = word.start?.let(shift), end = word.end?.let(shift)) },
                group = i,
            )
        }
        return LyricsAssembler.assemble(
            raw,
            instrumental = instrumental,
            tags = TrackTags(
                title = title,
                artist = artist,
                album = tags["al"]?.takeIf { it.isNotEmpty() },
                durationSec = tags["total"]?.toLongOrNull()?.let { (it / 1000).toInt() },
            ),
        )
    }

    private fun lineOf(start: Duration, end: Duration, body: String): KrcLine {
        val matches = syllable.findAll(body).toList()
        if (matches.isEmpty()) return KrcLine(start, end, emptyList(), htmlUnescape(body))
        val words = mutableListOf<RawWord>()
        val leading = body.substring(0, matches.first().range.first)
        if (leading.isNotBlank()) words += RawWord(htmlUnescape(leading), null)
        matches.forEachIndexed { i, match ->
            val textEnd = matches.getOrNull(i + 1)?.range?.first ?: body.length
            val text = htmlUnescape(body.substring(match.range.last + 1, textEnd))
            val wordStart = start + match.groupValues[1].toLong().milliseconds
            words += RawWord(text, wordStart, wordStart + match.groupValues[2].toLong().milliseconds)
        }
        return KrcLine(start, end, words, words.joinToString("") { it.text })
    }

    private fun isTitleArtistLine(text: String, title: String?, artist: String?): Boolean {
        val match = titleArtistLine.matchEntire(text.trim()) ?: return false
        if (title == null && artist == null) return true
        val sides = listOf(match.groupValues[1], match.groupValues[2]).map { it.normalizedKey() }
        val known = listOfNotNull(title, artist).map { it.normalizedKey() }.filter { it.isNotEmpty() }
        return sides.any { side -> known.any { it == side || " $side ".contains(" $it ") || " $it ".contains(" $side ") } }
    }

    private fun isCreditLine(text: String): Boolean {
        val trimmed = text.trim()
        if (!creditLine.matches(trimmed)) return false
        val role = trimmed.substringBefore('：').substringBefore(':').trim()
        return role.isNotEmpty() && role.split(whitespaceRun).size <= 3
    }

    private fun isTranslationAlternation(lines: List<KrcLine>): Boolean {
        if (lines.size < 2 || lines.size % 2 != 0) return false
        return lines.withIndex().all { (i, line) -> if (i % 2 == 0) !isHanOnly(line.text) else isHanOnly(line.text) }
    }
}
