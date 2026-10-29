package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

class LyricsUncensorTest {
    private fun wordLine(start: Long, vararg texts: String): LyricLine {
        val words = texts.mapIndexed { index, text ->
            LyricWord(text, (start + index * 300).milliseconds, (start + index * 300 + 300).milliseconds)
        }
        return LyricLine(start.milliseconds, (start + texts.size * 300).milliseconds, words, texts.joinToString(""))
    }

    private fun textLine(start: Long, text: String) =
        LyricLine(start.milliseconds, (start + 2000).milliseconds, emptyList(), text)

    private fun synced(vararg lines: LyricLine) = SyncedLyrics(lines.toList())

    private fun donor(vararg lines: LyricLine): UncensorDonor {
        val lyrics = synced(*lines)
        return UncensorDonor(lyrics, LyricsAssembler.plainText(lyrics))
    }

    private fun texts(lyrics: SyncedLyrics) = lyrics.lines.map { it.text }

    @Test
    fun `hasMask finds masked words only`() {
        assertTrue(LyricsUncensor.hasMask("A ************ California"))
        assertTrue(LyricsUncensor.hasMask("Bang mother****er for"))
        assertTrue(LyricsUncensor.hasMask("My n***a"))
        assertTrue(LyricsUncensor.hasMask("what the f--k"))
        assertTrue(LyricsUncensor.hasMask("you [__]"))
        assertTrue(LyricsUncensor.hasMask("(****!)"))
        assertFalse(LyricsUncensor.hasMask("well-known words - and more"))
        assertFalse(LyricsUncensor.hasMask("Nothing to see here"))
    }

    @Test
    fun `masked words are restored from a donor that splits the lines differently`() {
        val target = synced(
            wordLine(11_700, "A ", "************ ", "California ", "king ", "sized"),
            wordLine(29_000, "Want ", "to ", "kill ", "'em ", "but ", "that ", "**** ", "might"),
            wordLine(30_200, "Really ", "**** ", "my ", "money ", "up"),
        )
        val lrclib = donor(
            textLine(10_500, "Went from sleeping on the porch to a motherfucking California king sized"),
            textLine(28_600, "Want to kill 'em but that shit might really fuck my money up"),
        )

        val result = assertNotNull(LyricsUncensor.uncensor(target, listOf(lrclib)))

        assertEquals(
            listOf("A motherfucking California king sized", "Want to kill 'em but that shit might", "Really fuck my money up"),
            texts(result),
        )
        assertEquals("motherfucking ", result.lines[0].words[1].text)
        assertEquals(target.lines[0].words[1].startTime, result.lines[0].words[1].startTime)
        assertEquals(target.lines[0].words[1].endTime, result.lines[0].words[1].endTime)
        result.lines.forEach { line -> assertEquals(line.text, line.words.joinToString("") { it.text }) }
        assertNull(LyricsAssembler.validate(result))
    }

    @Test
    fun `an ad-lib in parentheses restores the masked word`() {
        val target = synced(wordLine(5_000, "Yeah ", "**** ", "we ", "made ", "it"))
        val result = assertNotNull(LyricsUncensor.uncensor(target, listOf(donor(textLine(4_800, "Yeah (shit!) we made it")))))
        assertEquals(listOf("Yeah shit we made it"), texts(result))
    }

    @Test
    fun `partial masks and full masks of any length are restored`() {
        val target = synced(
            wordLine(1_000, "Bang ", "mother****er ", "for"),
            wordLine(4_000, "My ", "n***a ", "ride ", "with"),
            wordLine(7_000, "N***as ", "know ", "the ", "deal"),
            wordLine(10_000, "Bad ", "***** ", "on ", "all ", "my ", "Banshee"),
        )
        val lrclib = donor(
            textLine(1_000, "Bang motherfucker for the money and the respect"),
            textLine(4_000, "My nigga ride with me"),
            textLine(7_000, "niggas know the deal"),
            textLine(10_000, "Bad bitch on all my Banshee I'm like twelve o' clock"),
        )

        val result = assertNotNull(LyricsUncensor.uncensor(target, listOf(lrclib)))

        assertEquals(
            listOf("Bang motherfucker for", "My nigga ride with", "Niggas know the deal", "Bad bitch on all my Banshee"),
            texts(result),
        )
    }

    @Test
    fun `consecutive masked words are restored together`() {
        val target = synced(wordLine(1_000, "Oh ", "**** ", "**** ", "man"))
        val result = assertNotNull(LyricsUncensor.uncensor(target, listOf(donor(textLine(1_000, "Oh shit fuck man")))))
        assertEquals(listOf("Oh shit fuck man"), texts(result))
    }

    @Test
    fun `a line the donor phrases differently stays masked`() {
        val target = synced(
            wordLine(1_000, "You ", "**** ", "clown"),
            wordLine(4_000, "Bad ", "***** ", "on ", "all"),
        )
        val lrclib = donor(textLine(1_000, "You big silly clown"), textLine(4_000, "Bad bitch on all"))

        val result = assertNotNull(LyricsUncensor.uncensor(target, listOf(lrclib)))

        assertEquals(listOf("You **** clown", "Bad bitch on all"), texts(result))
    }

    @Test
    fun `a donor line more than five seconds away is not used`() {
        val target = synced(wordLine(60_000, "Bad ", "***** ", "on ", "all ", "my ", "Banshee"))
        assertNull(LyricsUncensor.uncensor(target, listOf(donor(textLine(10_000, "Bad bitch on all my Banshee")))))
    }

    @Test
    fun `character timings are spread over the restored word and the trailing space is kept`() {
        val masked = LyricWord(
            "**** ", 1_300.milliseconds, 1_800.milliseconds,
            chars = "**** ".map { LyricChar(it.toString(), 1_300.milliseconds, 1_800.milliseconds) },
        )
        val line = LyricLine(
            1_000.milliseconds, 2_400.milliseconds,
            listOf(LyricWord("Holy ", 1_000.milliseconds, 1_300.milliseconds), masked, LyricWord("man", 1_800.milliseconds, 2_400.milliseconds)),
            "Holy **** man",
        )

        val result = assertNotNull(LyricsUncensor.uncensor(synced(line), listOf(donor(textLine(1_000, "Holy shit man")))))

        val word = result.lines.single().words[1]
        assertEquals("shit ", word.text)
        assertEquals("Holy shit man", result.lines.single().text)
        val chars = assertNotNull(word.chars)
        assertEquals(listOf("s", "h", "i", "t", " "), chars.map { it.char })
        assertEquals(listOf(1_300L, 1_400L, 1_500L, 1_600L, 1_700L), chars.map { it.startTime.inWholeMilliseconds })
        assertEquals(1_800.milliseconds, chars.last().endTime)
    }

    @Test
    fun `a plain target is restored from a plain donor`() {
        val result = LyricsUncensor.uncensorPlain(
            "Bad ***** on all my Banshee\n\nYou [__] clown",
            listOf(UncensorDonor(null, "Bad bitch on all my Banshee\nYou damn clown")),
        )
        assertEquals("Bad bitch on all my Banshee\n\nYou damn clown", result)
    }

    @Test
    fun `later donors restore what earlier donors could not`() {
        val target = synced(wordLine(1_000, "You ", "**** ", "clown"), wordLine(4_000, "Bad ", "***** ", "on"))
        val first = donor(textLine(1_000, "You big silly clown"), textLine(4_000, "Bad bitch on"))
        val second = UncensorDonor(null, "You damn clown")

        val result = assertNotNull(LyricsUncensor.uncensor(target, listOf(first, second)))

        assertEquals(listOf("You damn clown", "Bad bitch on"), texts(result))
    }

    @Test
    fun `nothing restorable returns null`() {
        val target = synced(wordLine(1_000, "You ", "**** ", "clown"))
        assertNull(LyricsUncensor.uncensor(target, listOf(donor(textLine(1_000, "You **** clown")))))
        assertNull(LyricsUncensor.uncensor(target, emptyList()))
        assertNull(LyricsUncensor.uncensorPlain("Nothing masked", listOf(UncensorDonor(null, "Nothing masked"))))
    }
}
