package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricsSyncLevel
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class KrcParserTest {
    @Test
    fun `krc blob is decrypted, inflated and cleaned`() {
        val blob = LyricsFixtures.krcBlob(LyricsFixtures.krcText)
        assertEquals(LyricsFixtures.krcText, KrcParser.decode(blob))

        val parsed = assertNotNull(KrcParser.parse(blob, requestIsCjk = false))
        assertEquals(LyricsSyncLevel.WORD, parsed.syncLevel)
        assertEquals("Song", parsed.title)
        assertEquals("Artist", parsed.artist)
        assertEquals(185, parsed.durationSec)
        val lines = assertNotNull(parsed.lyrics).lines
        assertEquals(listOf("I'm singing", "Second line"), lines.map { it.text })
        assertEquals(listOf("I'm ", "sing", "ing"), lines[0].words.map { it.text })
        assertEquals(listOf(3000, 3500, 4200).map { it.milliseconds }, lines[0].words.map { it.startTime })
        assertEquals(5000.milliseconds, lines[0].words.last().endTime)
        assertEquals(5000.milliseconds, lines[0].endTime)
        assertFalse(parsed.instrumental)
    }

    @Test
    fun `chinese lines are kept when the request is cjk`() {
        val parsed = assertNotNull(KrcParser.parse(LyricsFixtures.krcBlob(LyricsFixtures.krcText), requestIsCjk = true))
        assertEquals(listOf("I'm singing", "我在唱歌", "Second line", "第二行"), parsed.lyrics?.lines?.map { it.text })
    }

    @Test
    fun `han lines without strict alternation are kept`() {
        val krc = "[ti:Song]\n[ar:Artist]\n[1000,1000]<0,1000,0>Hello\n[2000,1000]<0,1000,0>你好\n[3000,1000]<0,1000,0>世界\n[4000,1000]<0,1000,0>World"
        val parsed = assertNotNull(KrcParser.parseText(krc, requestIsCjk = false))
        assertEquals(listOf("Hello", "你好", "世界", "World"), parsed.lyrics?.lines?.map { it.text })
    }

    @Test
    fun `single instrumental line marks the track instrumental`() {
        val krc = "[ti:Song]\n[ar:Artist]\n[0,2000]<0,1000,0>Song <1000,1000,0>- Artist\n[2000,5000]<0,5000,0>纯音乐，请欣赏"
        val parsed = assertNotNull(KrcParser.parse(LyricsFixtures.krcBlob(krc), requestIsCjk = false))
        assertTrue(parsed.instrumental)
        assertNull(parsed.lyrics)
        assertEquals("", parsed.plain)
    }

    @Test
    fun `credit lines with ascii colon are dropped`() {
        val krc = "[ti:Song]\n[ar:Artist]\n[0,1000]<0,1000,0>Composer: Someone\n[1000,1000]<0,1000,0>Lyrics: Other\n[2000,1000]<0,500,0>Real <500,500,0>lyric"
        val parsed = assertNotNull(KrcParser.parseText(krc, requestIsCjk = false))
        assertEquals(listOf("Real lyric"), parsed.lyrics?.lines?.map { it.text })
    }

    @Test
    fun `invalid blobs are rejected`() {
        assertNull(KrcParser.parse(byteArrayOf(1, 2, 3, 4, 5), requestIsCjk = false))
        assertNull(KrcParser.parse("krc1garbage".toByteArray(), requestIsCjk = false))
    }
}
