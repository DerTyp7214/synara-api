package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.*
import kotlin.time.Duration

data class ParsedLyrics(
    val lyrics: SyncedLyrics?,
    val plain: String,
    val syncLevel: LyricsSyncLevel,
    val instrumental: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationSec: Int? = null,
)

internal data class RawWord(val text: String, val start: Duration?, val end: Duration? = null)

internal data class RawLine(
    val start: Duration?,
    val end: Duration? = null,
    val text: String? = null,
    val words: List<RawWord> = emptyList(),
    val voice: String? = null,
    val background: Boolean = false,
    val group: Int = 0,
)

internal data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationSec: Int? = null,
)

object LyricsAssembler {
    const val MAX_LINES = 2000
    const val MAX_WORDS = 16000

    internal fun assemble(
        raw: List<RawLine>,
        voices: Map<String, LyricVoice> = emptyMap(),
        instrumental: Boolean = false,
        tags: TrackTags = TrackTags(),
    ): ParsedLyrics? {
        if (raw.size > MAX_LINES * 4) return null
        val timed = raw.mapNotNull { line -> line.start?.let { it to line } }.sortedBy { it.first }
        if (timed.isEmpty()) return plain(raw, instrumental, tags)

        val nextStarts = arrayOfNulls<Duration>(timed.size)
        for (i in timed.indices.reversed()) {
            val next = timed.getOrNull(i + 1)?.first ?: continue
            nextStarts[i] = if (next > timed[i].first) next else nextStarts[i + 1]
        }
        val lines = timed.mapIndexedNotNull { i, (start, line) -> buildLine(start, line, nextStarts[i]) }

        if (lines.isEmpty()) return if (instrumental) instrumentalResult(tags) else null
        if (lines.size > MAX_LINES || lines.sumOf { it.words.size } > MAX_WORDS) return null
        if (lines.all { line -> line.startTime == Duration.ZERO && line.words.all { it.startTime == Duration.ZERO } }) return null

        val usedVoices = voices.filterKeys { id -> lines.any { it.voice == id } }
        val withVoices = lines.map { if (it.voice != null && it.voice !in usedVoices) it.copy(voice = null) else it }
        val syncLevel = if (withVoices.any { it.words.isNotEmpty() }) LyricsSyncLevel.WORD else LyricsSyncLevel.LINE
        val lyrics = SyncedLyrics(withVoices, usedVoices)
        return ParsedLyrics(
            lyrics = lyrics,
            plain = plainText(lyrics),
            syncLevel = syncLevel,
            instrumental = instrumental,
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            durationSec = tags.durationSec,
        )
    }

    fun validate(lyrics: SyncedLyrics): String? {
        if (lyrics.lines.size > MAX_LINES) return "More than $MAX_LINES lines"
        if (lyrics.lines.sumOf { it.words.size } > MAX_WORDS) return "More than $MAX_WORDS words"
        var previousStart = Duration.ZERO
        lyrics.lines.forEachIndexed { index, line ->
            val position = "Line ${index + 1}"
            if (line.startTime.isNegative()) return "$position starts before zero"
            if (line.startTime < previousStart) return "$position starts before the previous line"
            if (line.endTime < line.startTime) return "$position ends before it starts"
            previousStart = line.startTime
            if (line.voice != null && line.voice !in lyrics.voices) return "$position has an unknown voice"
            val text = line.text ?: line.words.joinToString(" ") { it.text }
            if (containsHtmlTagOpener(text)) return "$position contains markup"
            if (line.text != null && line.words.isNotEmpty() && line.words.joinToString("") { it.text } != line.text) {
                return "$position words do not spell the line text"
            }
            var previousWord = line.startTime
            for (word in line.words) {
                if (word.startTime < previousWord) return "$position has a word before the line or the previous word"
                if (word.endTime < word.startTime) return "$position has a word that ends before it starts"
                previousWord = word.startTime
            }
        }
        if (lyrics.lines.isNotEmpty() && lyrics.lines.all { line -> line.startTime == Duration.ZERO && line.words.all { it.startTime == Duration.ZERO } }) {
            return "Lyrics have no timing"
        }
        return null
    }

    fun syncLevel(lyrics: SyncedLyrics?): LyricsSyncLevel = when {
        lyrics == null || lyrics.lines.isEmpty() -> LyricsSyncLevel.PLAIN
        lyrics.lines.any { it.words.isNotEmpty() } -> LyricsSyncLevel.WORD
        else -> LyricsSyncLevel.LINE
    }

    fun plainText(lyrics: SyncedLyrics): String =
        lyrics.lines.filterNot { it.background }.joinToString("\n") { line ->
            line.text ?: line.words.joinToString(" ") { it.text }
        }

    private fun buildLine(start: Duration, line: RawLine, nextStart: Duration?): LyricLine? {
        val cleaned = cleanWords(line.words)
        val claimedText = line.text?.let(::cleanText)
        val timedWords = timeWords(cleaned, start, line.end, nextStart)
        val words = when {
            timedWords == null -> emptyList()
            claimedText != null && timedWords.joinToString("") { it.text } != claimedText -> emptyList()
            else -> timedWords
        }
        val text = claimedText ?: cleaned.joinToString("") { it.text }
        if (text.isBlank()) return null
        val lastWordEnd = words.lastOrNull()?.endTime
        val end = maxOf(line.end ?: lastWordEnd ?: nextStart ?: start, lastWordEnd ?: start, start)
        return LyricLine(
            startTime = start,
            endTime = end,
            words = words,
            text = text,
            voice = line.voice,
            background = line.background,
            group = line.group,
        )
    }

    private fun timeWords(words: List<RawWord>, lineStart: Duration, lineEnd: Duration?, nextStart: Duration?): List<LyricWord>? {
        var previous = lineStart
        return words.mapIndexed { i, word ->
            val start = word.start ?: return null
            if (start < previous) return null
            val end = word.end ?: words.getOrNull(i + 1)?.start ?: lineEnd ?: nextStart ?: start
            if (end < start) return null
            previous = start
            LyricWord(word.text, start, end)
        }
    }

    private fun cleanWords(words: List<RawWord>): List<RawWord> {
        val result = mutableListOf<RawWord>()
        words.forEachIndexed { i, word ->
            var text = stripHtmlTags(word.text.replace(whitespaceRun, " "))
            if (text.endsWith('<') && words.getOrNull(i + 1)?.text?.firstOrNull()?.opensTag() == true) text = text.dropLast(1)
            val previous = result.lastOrNull()
            if (previous == null || previous.text.endsWith(' ')) text = text.trimStart()
            when {
                text.isEmpty() -> Unit
                text.isBlank() && previous != null -> result[result.lastIndex] = previous.copy(text = previous.text + text)
                else -> result += word.copy(text = text)
            }
        }
        while (result.isNotEmpty()) {
            val last = result.last()
            val trimmed = last.text.trimEnd()
            if (trimmed.isEmpty()) result.removeAt(result.lastIndex)
            else {
                result[result.lastIndex] = last.copy(text = trimmed)
                break
            }
        }
        return result
    }

    private fun cleanText(text: String): String = stripHtmlTags(text.replace(whitespaceRun, " ")).trim()

    private fun plain(raw: List<RawLine>, instrumental: Boolean, tags: TrackTags): ParsedLyrics? {
        val texts = raw.filterNot { it.background }.map { cleanText(it.text ?: it.words.joinToString("") { word -> word.text }) }
        val stanzas = mutableListOf<String>()
        for (text in texts) {
            if (text.isEmpty() && (stanzas.isEmpty() || stanzas.last().isEmpty())) continue
            stanzas += text
        }
        while (stanzas.lastOrNull()?.isEmpty() == true) stanzas.removeAt(stanzas.lastIndex)
        if (stanzas.size > MAX_LINES) return null
        if (stanzas.isEmpty()) return if (instrumental) instrumentalResult(tags) else null
        return ParsedLyrics(
            lyrics = null,
            plain = stanzas.joinToString("\n"),
            syncLevel = LyricsSyncLevel.PLAIN,
            instrumental = instrumental,
            title = tags.title,
            artist = tags.artist,
            album = tags.album,
            durationSec = tags.durationSec,
        )
    }

    private fun instrumentalResult(tags: TrackTags) = ParsedLyrics(
        lyrics = null,
        plain = "",
        syncLevel = LyricsSyncLevel.PLAIN,
        instrumental = true,
        title = tags.title,
        artist = tags.artist,
        album = tags.album,
        durationSec = tags.durationSec,
    )
}
