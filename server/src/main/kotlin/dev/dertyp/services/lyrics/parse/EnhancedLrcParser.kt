package dev.dertyp.services.lyrics.parse

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object EnhancedLrcParser {
    private val lineStamp = Regex("^\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val metadataTag = Regex("^\\[([A-Za-z#]+):(.*)]$")
    private val wordStampCandidate = Regex("<(\\d{1,3}:[^<>]{0,12})>")
    private val wordStamp = Regex("^(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?$")
    private val lengthValue = Regex("^(?:(\\d+):)?(\\d+)(?:\\.\\d+)?$")
    private val lineBreak = Regex("\r\n|\r|\n")

    fun parse(lrc: String): ParsedLyrics? {
        val tags = mutableMapOf<String, String>()
        val entries = mutableListOf<Pair<List<Duration>, String>>()
        for (rawLine in lrc.removePrefix("﻿").split(lineBreak)) {
            val line = rawLine.trim()
            val stamps = mutableListOf<Duration>()
            var rest = line
            while (true) {
                val match = lineStamp.find(rest) ?: break
                stamps += stampOf(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                rest = rest.substring(match.range.last + 1)
            }
            if (stamps.isEmpty()) {
                val tag = metadataTag.matchEntire(line)
                if (tag != null) {
                    tags.putIfAbsent(tag.groupValues[1].lowercase(), tag.groupValues[2].trim())
                    continue
                }
            }
            entries += stamps to rest
        }

        val offset = tags["offset"]?.removePrefix("+")?.toLongOrNull()?.milliseconds ?: Duration.ZERO
        val shift = { time: Duration -> (time - offset).coerceAtLeast(Duration.ZERO) }
        val lines = mutableListOf<RawLine>()
        entries.forEach { (stamps, text) ->
            if (stamps.isEmpty()) {
                lines += RawLine(start = null, text = stripWordStamps(text), group = lines.size)
                return@forEach
            }
            val words = wordsOf(text)
            val first = stamps.first()
            for (stamp in stamps) {
                val delta = stamp - first
                lines += if (words == null) {
                    RawLine(start = shift(stamp), text = stripWordStamps(text), group = lines.size)
                } else {
                    RawLine(
                        start = shift(stamp),
                        words = words.map { word ->
                            word.copy(start = word.start?.let { shift(it + delta) }, end = word.end?.let { shift(it + delta) })
                        },
                        group = lines.size,
                    )
                }
            }
        }
        return LyricsAssembler.assemble(
            lines,
            tags = TrackTags(
                title = tags["ti"]?.takeIf { it.isNotEmpty() },
                artist = tags["ar"]?.takeIf { it.isNotEmpty() },
                album = tags["al"]?.takeIf { it.isNotEmpty() },
                durationSec = tags["length"]?.let(::lengthSeconds),
            ),
        )
    }

    private fun wordsOf(text: String): List<RawWord>? {
        val matches = wordStampCandidate.findAll(text).toList()
        if (matches.isEmpty()) return null
        val stamps = matches.map { match ->
            wordStamp.matchEntire(match.groupValues[1].trim())?.let { stampOf(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }
        if (stamps.any { it == null }) return null
        val times = stamps.filterNotNull()
        if (times.zipWithNext().any { (a, b) -> b < a }) return null

        val words = mutableListOf<RawWord>()
        val leading = text.substring(0, matches.first().range.first)
        if (leading.isNotBlank()) words += RawWord(leading, null)
        matches.forEachIndexed { i, match ->
            val segmentEnd = matches.getOrNull(i + 1)?.range?.first ?: text.length
            val segment = text.substring(match.range.last + 1, segmentEnd)
            when {
                segment.isEmpty() -> Unit
                segment.isBlank() && words.isNotEmpty() -> words[words.lastIndex] = words.last().let { it.copy(text = it.text + segment) }
                else -> words += RawWord(segment, times[i], times.getOrNull(i + 1))
            }
        }
        return words
    }

    private fun stripWordStamps(text: String): String = text.replace(wordStampCandidate, "")

    private fun stampOf(minutes: String, seconds: String, fraction: String): Duration {
        val fractionMs = if (fraction.isEmpty()) 0L else ("0.$fraction".toDouble() * 1000).roundToLong()
        return (minutes.toLong() * 60_000 + seconds.toLong() * 1000 + fractionMs).milliseconds
    }

    private fun lengthSeconds(value: String): Int? {
        val match = lengthValue.matchEntire(value.trim()) ?: return null
        val minutes = match.groupValues[1].toIntOrNull() ?: 0
        val seconds = match.groupValues[2].toIntOrNull() ?: return null
        return minutes * 60 + seconds
    }
}
