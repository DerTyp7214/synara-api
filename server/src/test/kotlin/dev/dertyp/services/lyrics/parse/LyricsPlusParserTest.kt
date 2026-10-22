package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricsSyncLevel
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class LyricsPlusParserTest {
    @Test
    fun `word payload with background syllable`() {
        val parsed = assertNotNull(LyricsPlusParser.parse(LyricsFixtures.lyricsPlusWord))
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(3, lines.size)

        val lead = lines[0]
        assertEquals("Hello world", lead.text)
        assertEquals(listOf("Hel", "lo ", "world"), lead.words.map { it.text })
        assertEquals(1900.milliseconds, lead.words[1].endTime)
        assertEquals(4000.milliseconds, lead.endTime)

        val backing = lines[1]
        assertTrue(backing.background)
        assertEquals(lead.group, backing.group)
        assertEquals("(yeah)", backing.text)
        assertEquals(2700.milliseconds, backing.startTime)
        assertEquals(3600.milliseconds, backing.endTime)

        val early = lines[2]
        assertEquals("Bad timing", early.text)
        assertTrue(early.words.isEmpty())
        assertEquals(5000.milliseconds, early.startTime)

        assertEquals("Hello world\nBad timing", parsed.plain)
    }

    @Test
    fun `all zero times are a miss`() {
        assertNull(LyricsPlusParser.parse(LyricsFixtures.lyricsPlusZero))
    }

    @Test
    fun `syllables without spaces are aligned to the line text`() {
        val json = """{"type":"Word","lyrics":[{"time":1000,"duration":2000,"text":"Hello world","syllabus":[
            {"time":1000,"duration":500,"text":"Hello"},{"time":1500,"duration":500,"text":"world"}]}]}"""
        val line = assertNotNull(LyricsPlusParser.parse(json)?.lyrics).lines.single()
        assertEquals(listOf("Hello ", "world"), line.words.map { it.text })
    }

    @Test
    fun `syllables that do not spell the text stay line synced`() {
        val json = """{"type":"Word","lyrics":[{"time":1000,"duration":2000,"text":"Hello world","syllabus":[
            {"time":1000,"duration":500,"text":"Goodbye "},{"time":1500,"duration":500,"text":"world"}]}]}"""
        val parsed = assertNotNull(LyricsPlusParser.parse(json))
        assertEquals(LyricsSyncLevel.LINE, parsed.syncLevel)
        assertEquals("Hello world", parsed.lyrics?.lines?.single()?.text)
    }

    @Test
    fun `untimed syllable keeps the line line synced`() {
        val json = """{"type":"Word","lyrics":[{"time":1000,"duration":2000,"text":"Hello world","syllabus":[
            {"time":1000,"duration":500,"text":"Hello "},{"text":"world"}]}]}"""
        assertEquals(LyricsSyncLevel.LINE, LyricsPlusParser.parse(json)?.syncLevel)
    }

    @Test
    fun `line payload is line synced and other types are plain`() {
        val line = """{"type":"Line","lyrics":[{"time":1000,"duration":2000,"text":"First"},{"time":3000,"duration":2000,"text":"Second"}]}"""
        val parsedLine = assertNotNull(LyricsPlusParser.parse(line))
        assertEquals(LyricsSyncLevel.LINE, parsedLine.syncLevel)
        assertEquals(3000.milliseconds, parsedLine.lyrics?.lines?.get(1)?.startTime)

        val static = """{"type":"Static","lyrics":[{"time":1000,"duration":2000,"text":"First"},{"time":3000,"duration":2000,"text":"Second"}]}"""
        val parsedStatic = assertNotNull(LyricsPlusParser.parse(static))
        assertEquals(LyricsSyncLevel.PLAIN, parsedStatic.syncLevel)
        assertEquals("First\nSecond", parsedStatic.plain)
    }

    @Test
    fun `invalid json and empty payloads are misses`() {
        assertNull(LyricsPlusParser.parse("not json"))
        assertNull(LyricsPlusParser.parse("""{"type":"Word","lyrics":[]}"""))
        assertNull(LyricsPlusParser.parse("[]"))
    }
}
