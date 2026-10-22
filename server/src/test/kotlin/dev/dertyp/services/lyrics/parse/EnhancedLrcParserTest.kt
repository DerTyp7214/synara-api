package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricsSyncLevel
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EnhancedLrcParserTest {
    @Test
    fun `word stamps, out of order stamps, repeated line stamps and offset`() {
        val parsed = assertNotNull(EnhancedLrcParser.parse(LyricsFixtures.enhancedLrc))
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
        assertEquals("Song", parsed.title)
        assertEquals("Artist", parsed.artist)
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(4, lines.size)

        val first = lines[0]
        assertEquals("Hello world", first.text)
        assertEquals(10.seconds, first.startTime)
        assertEquals(listOf("Hel", "lo ", "world"), first.words.map { it.text })
        assertEquals(listOf(10000, 10400, 10700).map { it.milliseconds }, first.words.map { it.startTime })
        assertEquals(11500.milliseconds, first.words.last().endTime)

        val outOfOrder = lines[1]
        assertEquals("Out of order", outOfOrder.text)
        assertEquals(12500.milliseconds, outOfOrder.startTime)
        assertTrue(outOfOrder.words.isEmpty())

        assertEquals(listOf(14500, 19500).map { it.milliseconds }, lines.drop(2).map { it.startTime })
        assertTrue(lines.drop(2).all { it.text == "Chorus line" && it.words.isEmpty() })
        assertEquals(19500.milliseconds, lines[2].endTime)
    }

    @Test
    fun `unparseable word stamp keeps the line and drops word timing`() {
        val parsed = assertNotNull(EnhancedLrcParser.parse("[00:01.00]<00:01.00>One <00:1x.50>two\n[00:03.00]<00:03.00>Three <00:03.50>four"))
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals("One two", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
        assertEquals(listOf("Three ", "four"), lines[1].words.map { it.text })
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
    }

    @Test
    fun `plain lrc is line synced`() {
        val parsed = assertNotNull(EnhancedLrcParser.parse("[ar:Artist]\n[00:01.00]First\n[00:02.50]\n[00:04.00]Second\n"))
        assertEquals(LyricsSyncLevel.LINE, parsed.syncLevel)
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(listOf("First", "Second"), lines.map { it.text })
        assertEquals(2500.milliseconds, lines[0].endTime)
        assertEquals("First\nSecond", parsed.plain)
    }

    @Test
    fun `negative offset shifts lines later`() {
        val parsed = assertNotNull(EnhancedLrcParser.parse("[offset:-250]\n[00:01.00]Line"))
        assertEquals(1250.milliseconds, parsed.lyrics?.lines?.single()?.startTime)
    }

    @Test
    fun `text without stamps is plain`() {
        val parsed = assertNotNull(EnhancedLrcParser.parse("First line\nSecond line\n\n\nThird line"))
        assertEquals(LyricsSyncLevel.PLAIN, parsed.syncLevel)
        assertNull(parsed.lyrics)
        assertEquals("First line\nSecond line\n\nThird line", parsed.plain)
    }

    @Test
    fun `empty and untimed lrc are misses`() {
        assertNull(EnhancedLrcParser.parse(""))
        assertNull(EnhancedLrcParser.parse("[ti:Song]\n[ar:Artist]"))
        assertNull(EnhancedLrcParser.parse("[00:00.00]One\n[00:00.00]Two"))
    }
}
