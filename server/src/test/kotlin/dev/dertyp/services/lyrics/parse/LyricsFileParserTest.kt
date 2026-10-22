package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricsSyncLevel
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class LyricsFileParserTest {
    private val wordSynced = """
        version: '1.0'

        metadata:
          title: 'Paper Lanterns'
          artist: 'The Quiet Hours'
          album: 'Night Market'
          duration_ms: 185400
          instrumental: false
          mood: 'calm'

        lines:
          - text: "Light the paper lanterns"
            start_ms: 12000
            end_ms: 15000
            words:
              - text: 'Light '
                start_ms: 12000
                end_ms: 12600
              - text: 'the '
                start_ms: 12600
                end_ms: 13000
              - text: 'paper '
                start_ms: 13000
                end_ms: 13800
              - text: 'lanterns'
                start_ms: 13800
                end_ms: 15000
          - text: 'Let them drift away'
            start_ms: 16000
            end_ms: 19000

        plain: |
          [Verse 1]
          Light the paper lanterns
          Let them drift away
    """.trimIndent()

    @Test
    fun `word synced document`() {
        val parsed = assertNotNull(LyricsFileParser.parse(wordSynced))
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
        assertEquals("Paper Lanterns", parsed.title)
        assertEquals("The Quiet Hours", parsed.artist)
        assertEquals("Night Market", parsed.album)
        assertEquals(185, parsed.durationSec)
        assertFalse(parsed.instrumental)

        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(2, lines.size)
        val first = lines[0]
        assertEquals("Light the paper lanterns", first.text)
        assertEquals(listOf("Light ", "the ", "paper ", "lanterns"), first.words.map { it.text })
        assertEquals(first.text, first.words.joinToString("") { it.text })
        assertEquals(12000.milliseconds, first.startTime)
        assertEquals(15000.milliseconds, first.endTime)
        assertEquals(12600.milliseconds, first.words[1].startTime)
        assertEquals(13000.milliseconds, first.words[1].endTime)

        val second = lines[1]
        assertEquals("Let them drift away", second.text)
        assertTrue(second.words.isEmpty())
        assertEquals(16000.milliseconds, second.startTime)

        assertEquals("[Verse 1]\nLight the paper lanterns\nLet them drift away", parsed.plain)
    }

    @Test
    fun `line synced document with offset`() {
        val yaml = """
            version: '1.0'
            metadata:
              title: 'Harbor'
              artist: 'Low Tide'
              offset_ms: 500
            lines:
              - text: 'First light on the water'
                start_ms: 2000
              - text: 'Second bell'
                start_ms: 4500
                end_ms: 6000
        """.trimIndent()
        val parsed = assertNotNull(LyricsFileParser.parse(yaml))
        assertEquals(LyricsSyncLevel.LINE, parsed.syncLevel)
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(listOf(1500.milliseconds, 4000.milliseconds), lines.map { it.startTime })
        assertEquals(4000.milliseconds, lines[0].endTime)
        assertEquals(5500.milliseconds, lines[1].endTime)
        assertEquals("First light on the water\nSecond bell", parsed.plain)
    }

    @Test
    fun `unsynced document keeps the plain text`() {
        val yaml = """
            version: '1.0'
            metadata:
              title: 'Sketch'
              artist: 'Nobody'
              instrumental: false
            lines: []
            plain: |
              [Verse 1]
              Not synced yet


              La la la
        """.trimIndent()
        val parsed = assertNotNull(LyricsFileParser.parse(yaml))
        assertEquals(LyricsSyncLevel.PLAIN, parsed.syncLevel)
        assertNull(parsed.lyrics)
        assertEquals("[Verse 1]\nNot synced yet\n\nLa la la", parsed.plain)
    }

    @Test
    fun `instrumental document`() {
        val yaml = """
            version: '1.0'
            metadata:
              title: 'Adagio'
              artist: 'Strings'
              duration_ms: 480000
              instrumental: true
            lines: []
            plain: ''
        """.trimIndent()
        val parsed = assertNotNull(LyricsFileParser.parse(yaml))
        assertTrue(parsed.instrumental)
        assertNull(parsed.lyrics)
        assertEquals("", parsed.plain)
        assertEquals(480, parsed.durationSec)
    }

    @Test
    fun `words that do not spell the line fall back to line timing`() {
        val yaml = """
            version: '1.0'
            metadata:
              title: 'Echo'
              artist: 'Valley'
            lines:
              - text: 'Call and answer'
                start_ms: 1000
                end_ms: 3000
                words:
                  - text: 'Call '
                    start_ms: 1000
                  - text: 'and '
                    start_ms: 900
                  - text: 'answer'
                    start_ms: 2000
        """.trimIndent()
        val parsed = assertNotNull(LyricsFileParser.parse(yaml))
        assertEquals(LyricsSyncLevel.LINE, parsed.syncLevel)
        val line = assertNotNull(parsed.lyrics).lines.single()
        assertEquals("Call and answer", line.text)
        assertTrue(line.words.isEmpty())
    }

    @Test
    fun `empty, malformed and untimed documents are a miss`() {
        assertNull(LyricsFileParser.parse(""))
        assertNull(LyricsFileParser.parse("- just\n- a list"))
        assertNull(LyricsFileParser.parse("version: '1.0'\nmetadata:\n  title: 'X'\n  artist: 'Y'\nlines: []\n"))
        val zero = """
            version: '1.0'
            metadata:
              title: 'Zero'
              artist: 'Clock'
            lines:
              - text: 'One'
                start_ms: 0
              - text: 'Two'
                start_ms: 0
        """.trimIndent()
        assertNull(LyricsFileParser.parse(zero))
    }
}
