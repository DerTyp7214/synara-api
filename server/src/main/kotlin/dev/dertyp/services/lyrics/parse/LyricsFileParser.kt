package dev.dertyp.services.lyrics.parse

import net.mamoe.yamlkt.Yaml
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

object LyricsFileParser {
    private val lineBreak = Regex("\r\n|\r|\n")

    fun parse(yaml: String): ParsedLyrics? {
        val root = try {
            Yaml.decodeAnyFromString(yaml.removePrefix("﻿")) as? Map<*, *>
        } catch (_: Exception) {
            null
        } ?: return null
        val metadata = root["metadata"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val offset = metadata.long("offset_ms")?.milliseconds ?: Duration.ZERO
        val shift = { ms: Long -> (ms.milliseconds - offset).coerceAtLeast(Duration.ZERO) }
        val tags = TrackTags(
            title = metadata.string("title")?.takeIf { it.isNotBlank() },
            artist = metadata.string("artist")?.takeIf { it.isNotBlank() },
            album = metadata.string("album")?.takeIf { it.isNotBlank() },
            durationSec = metadata.long("duration_ms")?.takeIf { it > 0 }?.let { (it / 1000.0).roundToInt() },
        )
        val instrumental = metadata.boolean("instrumental")

        val lines = (root["lines"] as? List<*>).orEmpty().mapIndexedNotNull { group, element ->
            val line = element as? Map<*, *> ?: return@mapIndexedNotNull null
            val start = line.long("start_ms")?.takeIf { it >= 0 } ?: return@mapIndexedNotNull null
            val words = (line["words"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.map { word ->
                RawWord(
                    text = word.string("text").orEmpty(),
                    start = word.long("start_ms")?.takeIf { it >= 0 }?.let(shift),
                    end = word.long("end_ms")?.takeIf { it >= 0 }?.let(shift),
                )
            }
            RawLine(
                start = shift(start),
                end = line.long("end_ms")?.takeIf { it >= 0 }?.let(shift),
                text = if (words.isEmpty()) line.string("text").orEmpty() else null,
                words = words,
                group = group,
            )
        }

        val plainLines = root.string("plain").orEmpty().split(lineBreak).map { RawLine(start = null, text = it) }
        val plain = LyricsAssembler.assemble(plainLines, tags = tags)?.plain?.takeIf { it.isNotBlank() }
        if (lines.isEmpty()) return LyricsAssembler.assemble(plainLines, instrumental = instrumental, tags = tags)
        val synced = LyricsAssembler.assemble(lines, instrumental = instrumental, tags = tags) ?: return null
        return if (synced.lyrics != null && plain != null) synced.copy(plain = plain) else synced
    }

    private fun Map<*, *>.string(key: String): String? = when (val value = this[key]) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun Map<*, *>.long(key: String): Long? = when (val value = this[key]) {
        is Number -> value.toDouble().takeIf { it.isFinite() }?.toLong()
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()
        else -> null
    }

    private fun Map<*, *>.boolean(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.trim().equals("true", ignoreCase = true)
        else -> false
    }
}
