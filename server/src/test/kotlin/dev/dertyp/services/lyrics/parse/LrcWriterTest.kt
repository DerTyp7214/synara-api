package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class LrcWriterTest {
    private fun word(text: String, start: Long, end: Long) = LyricWord(text, start.milliseconds, end.milliseconds)

    @Test
    fun `line synced lyrics are written as LRC`() {
        val lyrics = SyncedLyrics(
            listOf(
                LyricLine(1500.milliseconds, 3000.milliseconds, emptyList(), text = "First line"),
                LyricLine(61_250.milliseconds, 63_000.milliseconds, emptyList(), text = "Second line"),
            )
        )
        assertEquals("[00:01.500]First line\n[01:01.250]Second line", LrcWriter.write(lyrics))
    }

    @Test
    fun `word synced lyrics round trip through the enhanced LRC parser`() {
        val lyrics = SyncedLyrics(
            listOf(
                LyricLine(
                    1000.milliseconds, 2500.milliseconds,
                    listOf(word("Hel", 1000, 1300), word("lo ", 1300, 1600), word("world", 1600, 2500)),
                    text = "Hello world",
                ),
                LyricLine(
                    3000.milliseconds, 4000.milliseconds,
                    listOf(word("second", 3000, 3500), word("line", 3500, 4000)),
                ),
            )
        )

        val written = LrcWriter.write(lyrics)
        assertEquals(
            "[00:01.000]<00:01.000>Hel<00:01.300>lo <00:01.600>world<00:02.500>\n" +
                "[00:03.000]<00:03.000>second <00:03.500>line<00:04.000>",
            written
        )
        val parsed = assertNotNull(EnhancedLrcParser.parse(written))
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(listOf("Hel", "lo ", "world"), lines[0].words.map { it.text })
        assertEquals(listOf(1000.milliseconds, 1300.milliseconds, 1600.milliseconds), lines[0].words.map { it.startTime })
        assertEquals(2500.milliseconds, lines[0].words.last().endTime)
        assertEquals("second line", lines[1].text)
    }
}
