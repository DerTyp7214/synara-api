package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.SyncedLyrics
import kotlin.time.Duration

object LrcWriter {
    fun write(lyrics: SyncedLyrics): String = lyrics.lines.joinToString("\n") { line ->
        val stamp = "[${stamp(line.startTime)}]"
        if (line.words.isEmpty()) return@joinToString stamp + (line.text ?: "")
        val separator = if (line.text == null) " " else ""
        val words = line.words.mapIndexed { index, word ->
            "<${stamp(word.startTime)}>" + word.text + if (index < line.words.lastIndex) separator else ""
        }
        stamp + words.joinToString("") + "<${stamp(line.words.last().endTime)}>"
    }

    private fun stamp(time: Duration): String {
        val ms = time.inWholeMilliseconds.coerceAtLeast(0)
        return "%02d:%02d.%03d".format(ms / 60_000, ms / 1000 % 60, ms % 1000)
    }
}
