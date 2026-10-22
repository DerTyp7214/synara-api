package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LyricsAssemblerTest {
    private fun allFixtures(): List<ParsedLyrics> = listOfNotNull(
        TtmlParser.parse(LyricsFixtures.duetTtml),
        EnhancedLrcParser.parse(LyricsFixtures.enhancedLrc),
        KrcParser.parse(LyricsFixtures.krcBlob(LyricsFixtures.krcText), requestIsCjk = false),
        KrcParser.parse(LyricsFixtures.krcBlob(LyricsFixtures.krcText), requestIsCjk = true),
        LyricsPlusParser.parse(LyricsFixtures.lyricsPlusWord),
    )

    @Test
    fun `words spell the line text for every parsed line`() {
        val fixtures = allFixtures()
        assertEquals(5, fixtures.size)
        for (parsed in fixtures) {
            val lyrics = assertNotNull(parsed.lyrics)
            assertNull(LyricsAssembler.validate(lyrics))
            for (line in lyrics.lines) {
                val text = assertNotNull(line.text)
                if (line.words.isNotEmpty()) assertEquals(text, line.words.joinToString("") { it.text })
                assertTrue(line.words.all { it.startTime >= line.startTime && it.endTime >= it.startTime })
            }
        }
    }

    @Test
    fun `html tags are stripped from all text`() {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="1" end="2"><span begin="1" end="1.5">&lt;b&gt;Bold</span> <span begin="1.5" end="2">&lt;</span><span begin="2" end="2.5">script&gt;x</span></p></body></tt>"""
        val line = assertNotNull(TtmlParser.parse(ttml)?.lyrics).lines.single()
        assertFalse(containsHtmlTagOpener(line.text.orEmpty()))
        assertEquals(line.text, line.words.joinToString("") { it.text })
        assertEquals("Bold script>x", line.text)

        val lrc = assertNotNull(EnhancedLrcParser.parse("[00:01.00]<i>Hi</i> there <3 a<b\n[00:02.00]<!-- x -->Next <?php ?>"))
        assertEquals(listOf("Hi there <3 ab", "Next"), lrc.lyrics?.lines?.map { it.text })

        assertEquals("ab", stripHtmlTags("a<<b>b"))
        assertEquals("I <3 you", stripHtmlTags("I <3 you"))
        assertEquals("1 < 2", stripHtmlTags("1 < 2"))
    }

    @Test
    fun `line and word limits make a miss`() {
        val tooManyLines = (1..LyricsAssembler.MAX_LINES + 1).joinToString("\n") { i -> "[${i / 60}:${"%02d".format(i % 60)}.00]Line $i" }
        assertNull(EnhancedLrcParser.parse(tooManyLines))
        val maxLines = (1..LyricsAssembler.MAX_LINES).joinToString("\n") { i -> "[${i / 60}:${"%02d".format(i % 60)}.00]Line $i" }
        assertNotNull(EnhancedLrcParser.parse(maxLines))

        val wordsPerLine = 20
        val lineCount = LyricsAssembler.MAX_WORDS / wordsPerLine + 1
        val tooManyWords = (1..lineCount).joinToString("\n") { i ->
            val base = i * 1000L
            "[${base / 60000}:${"%02d".format((base / 1000) % 60)}.00]" + (0 until wordsPerLine).joinToString("") { w -> "<${base / 60000}:${"%02d".format((base / 1000) % 60)}.00>w$w " }
        }
        assertNull(EnhancedLrcParser.parse(tooManyWords))
    }

    @Test
    fun `validate rejects bad manual lyrics`() {
        val word = LyricWord("Hi ", 1.seconds, 2.seconds)
        val good = SyncedLyrics(listOf(LyricLine(1.seconds, 3.seconds, listOf(word, LyricWord("there", 2.seconds, 3.seconds)), text = "Hi there")))
        assertNull(LyricsAssembler.validate(good))
        assertEquals(LyricsSyncLevel.WORD, LyricsAssembler.syncLevel(good))

        val unsorted = SyncedLyrics(listOf(LyricLine(5.seconds, 6.seconds, emptyList(), "B"), LyricLine(1.seconds, 2.seconds, emptyList(), "A")))
        assertNotNull(LyricsAssembler.validate(unsorted))

        val misspelled = SyncedLyrics(listOf(LyricLine(1.seconds, 3.seconds, listOf(word), text = "Hello")))
        assertNotNull(LyricsAssembler.validate(misspelled))

        val wordBeforeLine = SyncedLyrics(listOf(LyricLine(1.seconds, 3.seconds, listOf(LyricWord("Hi", 500.milliseconds, 2.seconds)), text = "Hi")))
        assertNotNull(LyricsAssembler.validate(wordBeforeLine))

        val unknownVoice = SyncedLyrics(listOf(LyricLine(1.seconds, 2.seconds, emptyList(), "A", voice = "v9")))
        assertNotNull(LyricsAssembler.validate(unknownVoice))

        val markup = SyncedLyrics(listOf(LyricLine(1.seconds, 2.seconds, emptyList(), "<script>")))
        assertNotNull(LyricsAssembler.validate(markup))

        val untimed = SyncedLyrics(listOf(LyricLine(0.seconds, 0.seconds, emptyList(), "A"), LyricLine(0.seconds, 0.seconds, emptyList(), "B")))
        assertNotNull(LyricsAssembler.validate(untimed))
        assertEquals(LyricsSyncLevel.LINE, LyricsAssembler.syncLevel(untimed))
        assertEquals(LyricsSyncLevel.PLAIN, LyricsAssembler.syncLevel(null))
    }

    @Test
    fun `cjk detection and html unescaping`() {
        assertTrue(containsCjk("夜に駆ける"))
        assertTrue(containsCjk("사랑"))
        assertFalse(containsCjk("Hello"))
        assertTrue(isHanOnly("我在唱歌，"))
        assertFalse(isHanOnly("夜に駆ける"))
        assertEquals("I'm \"here\" & <there> 'x' é", htmlUnescape("I&apos;m &quot;here&quot; &amp; &lt;there&gt; &#39;x&#39; &#xE9;"))
        assertEquals("&unknown;", htmlUnescape("&unknown;"))
    }
}
