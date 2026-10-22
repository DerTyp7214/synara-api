package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricVoiceType
import dev.dertyp.services.models.LyricsSyncLevel
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TtmlParserTest {
    @Test
    fun `syllables, backing vocals and two agents are parsed`() {
        val parsed = assertNotNull(TtmlParser.parse(LyricsFixtures.duetTtml))
        val lyrics = assertNotNull(parsed.lyrics)
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
        assertEquals(185, parsed.durationSec)
        assertEquals(setOf("v1", "v2"), lyrics.voices.keys)
        assertEquals("Alice", lyrics.voices.getValue("v1").name)
        assertEquals("Bob", lyrics.voices.getValue("v2").name)
        assertEquals(LyricVoiceType.PERSON, lyrics.voices.getValue("v2").type)

        val lines = lyrics.lines
        assertEquals(4, lines.size)

        val lead = lines[0]
        assertEquals("Hello world", lead.text)
        assertEquals(listOf("Hel", "lo ", "world"), lead.words.map { it.text })
        assertEquals(1.seconds, lead.startTime)
        assertEquals(4.seconds, lead.endTime)
        assertEquals(1400.milliseconds, lead.words[1].startTime)
        assertEquals("v1", lead.voice)
        assertFalse(lead.background)

        val backing = lines[1]
        assertTrue(backing.background)
        assertEquals(lead.group, backing.group)
        assertEquals("(oh yeah)", backing.text)
        assertEquals(listOf("(oh ", "yeah)"), backing.words.map { it.text })
        assertEquals("v1", backing.voice)

        val second = lines[2]
        assertEquals("Second voice, sings", second.text)
        assertEquals(listOf("Second ", "voice, ", "sings"), second.words.map { it.text })
        assertEquals(5500.milliseconds, second.words[1].startTime)
        assertEquals(6300.milliseconds, second.words[2].startTime)
        assertEquals("v2", second.voice)
        assertNotEquals(lead.group, second.group)

        val untimed = lines[3]
        assertEquals("Untimed line", untimed.text)
        assertTrue(untimed.words.isEmpty())

        assertFalse(parsed.plain.contains("Hallo"))
        assertEquals("Hello world\nSecond voice, sings\nUntimed line", parsed.plain)
    }

    @Test
    fun `line timed ttml is line synced`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1.5" end="3">First</p><p begin="00:00:04.000" end="00:00:05.000">Second</p></div></body></tt>"""
        val parsed = assertNotNull(TtmlParser.parse(ttml))
        assertEquals(LyricsSyncLevel.LINE, parsed.syncLevel)
        assertEquals(listOf("First", "Second"), parsed.lyrics?.lines?.map { it.text })
        assertEquals(1500.milliseconds, parsed.lyrics?.lines?.first()?.startTime)
    }

    @Test
    fun `half timed line is downgraded to line sync`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1" end="3"><span begin="1" end="2">Good</span> <span begin="-1s" end="3">bad</span></p><p begin="4" end="5"><span begin="4" end="5">Fine</span></p></body></tt>"""
        val lines = assertNotNull(TtmlParser.parse(ttml)?.lyrics).lines
        assertEquals("Good bad", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
        assertEquals(listOf("Fine"), lines[1].words.map { it.text })
    }

    @Test
    fun `doctype is rejected`() {
        val ttml = """<?xml version="1.0"?><!DOCTYPE tt [<!ENTITY x "boom">]><tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1" end="2">&x;</p></body></tt>"""
        assertNull(TtmlParser.parse(ttml))
    }

    @Test
    fun `external entities and non tt roots are rejected`() {
        assertNull(TtmlParser.parse("""<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1" end="2">&ext;</p></body></tt>"""))
        assertNull(TtmlParser.parse("""<html><body><p begin="1" end="2">Hi</p></body></html>"""))
        assertNull(TtmlParser.parse("not xml"))
    }

    @Test
    fun `nesting deeper than the limit is rejected`() {
        val depth = TtmlParser.MAX_DEPTH + 1
        val open = "<span>".repeat(depth - 3)
        val close = "</span>".repeat(depth - 3)
        val tooDeep = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1" end="2">$open Hi $close</p></body></tt>"""
        assertNull(TtmlParser.parse(tooDeep))
        val atLimit = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1" end="2">${open.removePrefix("<span>")} Hi ${close.removePrefix("</span>")}</p></body></tt>"""
        assertNotNull(TtmlParser.parse(atLimit))
    }

    @Test
    fun `time formats are parsed and invalid ones rejected`() {
        assertEquals(1500.milliseconds, TtmlParser.parseTime("1.5s"))
        assertEquals(250.milliseconds, TtmlParser.parseTime("250ms"))
        assertEquals(90.seconds, TtmlParser.parseTime("1.5m"))
        assertEquals(3600.seconds, TtmlParser.parseTime("1h"))
        assertEquals(3723500.milliseconds, TtmlParser.parseTime("1:02:03.5"))
        assertEquals(63250.milliseconds, TtmlParser.parseTime("01:03.250"))
        assertEquals(12.seconds, TtmlParser.parseTime("12"))
        assertNull(TtmlParser.parseTime("-1s"))
        assertNull(TtmlParser.parseTime("NaN"))
        assertNull(TtmlParser.parseTime("Infinity"))
        assertNull(TtmlParser.parseTime("1e400"))
        assertNull(TtmlParser.parseTime("abc"))
    }

    @Test
    fun `untimed sync is a miss`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="0" end="0">One</p><p begin="0s" end="0s">Two</p></body></tt>"""
        assertNull(TtmlParser.parse(ttml))
    }
}
