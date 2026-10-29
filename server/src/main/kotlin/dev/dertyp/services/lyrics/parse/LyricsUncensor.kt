package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class UncensorDonor(val lyrics: SyncedLyrics?, val plain: String)

object LyricsUncensor {
    private const val MAX_CELLS = 4_000_000L
    private const val MASK_CHARS = "*#_•"
    private const val EDGE_CHARS = "\"“”„‘’«»()[]{}!?.,"
    private const val UP: Byte = 0
    private const val LEFT: Byte = 1
    private const val DIAG: Byte = 2
    private val MAX_TIME_GAP = 5.seconds
    private val dashMask = Regex("(?<=\\p{L})-{2,}(?=\\p{L})")
    private val tokenPattern = Regex("\\S+")

    private class Line(val parts: MutableList<String>, val separator: String, val time: Duration?)

    private class Mask(val prefix: String, val suffix: String) {
        val full: Boolean get() = prefix.isEmpty() && suffix.isEmpty()
    }

    private class TargetToken(
        val line: Int,
        val segment: Int,
        val start: Int,
        val end: Int,
        val core: String,
        val norm: String,
        val mask: Mask?,
        val time: Duration?,
    )

    private class DonorToken(val core: String, val norm: String, val masked: Boolean, val time: Duration?)

    private class Replacement(val token: TargetToken, val text: String)

    fun hasMask(text: String): Boolean = tokenPattern.findAll(text).any { maskOf(coreOf(it.value)) != null }

    fun uncensor(target: SyncedLyrics, donors: List<UncensorDonor>): SyncedLyrics? {
        val lines = target.lines.map { line ->
            if (line.words.isNotEmpty()) {
                Line(line.words.map { it.text }.toMutableList(), if (line.text != null) "" else " ", line.startTime)
            } else {
                Line(mutableListOf(line.text.orEmpty()), "", line.startTime)
            }
        }
        if (!restore(lines, donors)) return null
        return target.copy(lines = target.lines.mapIndexed { index, line ->
            val parts = lines[index].parts
            when {
                line.words.isNotEmpty() -> {
                    val words = line.words.mapIndexed { k, word ->
                        if (parts[k] == word.text) word
                        else word.copy(text = parts[k], chars = word.chars?.let { spreadChars(parts[k], word.startTime, word.endTime) })
                    }
                    line.copy(words = words, text = line.text?.let { words.joinToString("") { it.text } })
                }
                line.text != null -> line.copy(text = parts[0])
                else -> line
            }
        })
    }

    fun uncensorPlain(text: String, donors: List<UncensorDonor>): String? {
        val lines = text.lines().map { Line(mutableListOf(it), "", null) }
        if (!restore(lines, donors)) return null
        return lines.joinToString("\n") { it.parts[0] }
    }

    private fun restore(lines: List<Line>, donors: List<UncensorDonor>): Boolean {
        var changed = false
        for (donor in donors) {
            val targets = targetTokens(lines)
            if (targets.none { it.mask != null }) break
            val donorTokens = donorTokens(donor)
            val match = align(targets, donorTokens) ?: continue
            val replacements = replacements(targets, donorTokens, match)
            for (replacement in replacements.asReversed()) {
                val token = replacement.token
                val parts = lines[token.line].parts
                val part = parts[token.segment]
                parts[token.segment] = part.substring(0, token.start) + replacement.text + part.substring(token.end)
            }
            if (replacements.isNotEmpty()) changed = true
        }
        return changed
    }

    private fun replacements(targets: List<TargetToken>, donors: List<DonorToken>, match: IntArray): List<Replacement> {
        val result = mutableListOf<Replacement>()
        var a = 0
        while (a < targets.size) {
            if (targets[a].mask == null) {
                a++
                continue
            }
            var b = a
            while (b + 1 < targets.size && targets[b + 1].mask != null && targets[b + 1].line == targets[a].line) b++
            if (restorable(targets, match, a, b)) {
                for (k in a..b) {
                    val token = targets[k]
                    val mask = token.mask!!
                    val donor = donors[match[k]].core
                    val capitalize = token.core.first().isUpperCase() || (mask.full && isFirstInLine(targets, k))
                    result += Replacement(token, if (capitalize) donor.replaceFirstChar { it.uppercaseChar() } else donor)
                }
            }
            a = b + 1
        }
        return result
    }

    private fun restorable(targets: List<TargetToken>, match: IntArray, a: Int, b: Int): Boolean {
        val first = match[a]
        if (first < 0) return false
        if ((a..b).any { match[it] != first + (it - a) || targets[it].segment < 0 }) return false
        val last = first + (b - a)
        val leftInLine = !isFirstInLine(targets, a)
        val rightInLine = b + 1 < targets.size && targets[b + 1].line == targets[b].line
        val leftAnchored = leftInLine && match[a - 1] == first - 1
        val rightAnchored = rightInLine && match[b + 1] == last + 1
        if (leftInLine && !leftAnchored) return false
        if (rightInLine && !rightAnchored) return false
        return leftAnchored || rightAnchored
    }

    private fun isFirstInLine(targets: List<TargetToken>, index: Int): Boolean =
        index == 0 || targets[index - 1].line != targets[index].line

    private fun align(targets: List<TargetToken>, donors: List<DonorToken>): IntArray? {
        val n = targets.size
        val m = donors.size
        if (n == 0 || m == 0 || n.toLong() * m > MAX_CELLS) return null
        val width = m + 1
        val directions = ByteArray((n + 1) * width)
        var previous = IntArray(width)
        var current = IntArray(width)
        for (i in 1..n) {
            current[0] = 0
            for (j in 1..m) {
                var best = previous[j]
                var direction = UP
                if (current[j - 1] > best) {
                    best = current[j - 1]
                    direction = LEFT
                }
                val score = score(targets[i - 1], donors[j - 1])
                if (score > 0 && previous[j - 1] + score >= best) {
                    best = previous[j - 1] + score
                    direction = DIAG
                }
                current[j] = best
                directions[i * width + j] = direction
            }
            val swap = previous
            previous = current
            current = swap
        }
        val match = IntArray(n) { -1 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            when (directions[i * width + j]) {
                DIAG -> {
                    match[i - 1] = j - 1
                    i--
                    j--
                }
                UP -> i--
                else -> j--
            }
        }
        return match
    }

    private fun score(target: TargetToken, donor: DonorToken): Int {
        if (target.time != null && donor.time != null && (target.time - donor.time).absoluteValue > MAX_TIME_GAP) return 0
        if (donor.masked) return 0
        val mask = target.mask ?: return if (target.norm == donor.norm) 2 else 0
        val fits = donor.norm.length > mask.prefix.length + mask.suffix.length &&
            donor.norm.startsWith(mask.prefix) && donor.norm.endsWith(mask.suffix)
        return if (fits) 1 else 0
    }

    private fun targetTokens(lines: List<Line>): List<TargetToken> {
        val tokens = mutableListOf<TargetToken>()
        lines.forEachIndexed { lineIndex, line ->
            val offsets = IntArray(line.parts.size)
            val full = buildString {
                line.parts.forEachIndexed { k, part ->
                    if (k > 0) append(line.separator)
                    offsets[k] = length
                    append(part)
                }
            }
            for (found in tokenPattern.findAll(full)) {
                val (from, to) = coreRange(found.value)
                if (from >= to) continue
                val core = found.value.substring(from, to)
                val mask = maskOf(core)
                val norm = normalize(core)
                if (mask == null && norm.isEmpty()) continue
                val start = found.range.first + from
                val end = found.range.first + to
                val segment = segmentAt(offsets, line.parts, start)
                val sameSegment = segment >= 0 && end <= offsets[segment] + line.parts[segment].length
                tokens += TargetToken(
                    line = lineIndex,
                    segment = if (sameSegment) segment else -1,
                    start = if (sameSegment) start - offsets[segment] else 0,
                    end = if (sameSegment) end - offsets[segment] else 0,
                    core = core,
                    norm = norm,
                    mask = mask,
                    time = line.time,
                )
            }
        }
        return tokens
    }

    private fun segmentAt(offsets: IntArray, parts: List<String>, position: Int): Int =
        offsets.indices.lastOrNull { offsets[it] <= position && position < offsets[it] + parts[it].length } ?: -1

    private fun donorTokens(donor: UncensorDonor): List<DonorToken> {
        val lines = donor.lyrics?.lines?.takeIf { it.isNotEmpty() }
            ?.map { line -> (line.text ?: line.words.joinToString(" ") { it.text }) to line.startTime }
            ?: donor.plain.lines().map { it to null }
        return lines.flatMap { (text, time) ->
            tokenPattern.findAll(text).mapNotNull { found ->
                val core = coreOf(found.value)
                val masked = maskOf(core) != null
                val norm = normalize(core)
                if (!masked && norm.isEmpty()) null else DonorToken(core, norm, masked, time)
            }.toList()
        }
    }

    private fun coreRange(token: String): Pair<Int, Int> {
        var from = 0
        var to = token.length
        while (from < to && token[from] in EDGE_CHARS) from++
        while (to > from && token[to - 1] in EDGE_CHARS) to--
        val bracketedMask = from in 1..<to && to < token.length && token[from - 1] == '[' && token[to] == ']' &&
            (from until to).all { token[it] in MASK_CHARS }
        return if (bracketedMask) from - 1 to to + 1 else from to to
    }

    private fun coreOf(token: String): String = coreRange(token).let { (from, to) -> token.substring(from, to) }

    private fun maskOf(core: String): Mask? {
        if (core.isEmpty()) return null
        val first = core.indexOfFirst { it in MASK_CHARS }
        val (from, to) = if (first >= 0) {
            first to core.indexOfLast { it in MASK_CHARS } + 1
        } else {
            val runs = dashMask.findAll(core).toList().ifEmpty { return null }
            runs.first().range.first to runs.last().range.last + 1
        }
        return Mask(normalize(core.substring(0, from)), normalize(core.substring(to)))
    }

    private fun normalize(text: String): String = buildString {
        for (char in text.lowercase()) {
            when {
                char.isLetterOrDigit() || char == '\'' -> append(char)
                char == '’' -> append('\'')
            }
        }
    }.trim('\'')

    private fun spreadChars(text: String, start: Duration, end: Duration): List<LyricChar> {
        val chars = text.codePoints().toArray().map { String(Character.toChars(it)) }
        if (chars.isEmpty()) return emptyList()
        val step = (end - start) / chars.size
        return chars.mapIndexed { index, char ->
            LyricChar(char, start + step * index, if (index == chars.lastIndex) end else start + step * (index + 1))
        }
    }
}
