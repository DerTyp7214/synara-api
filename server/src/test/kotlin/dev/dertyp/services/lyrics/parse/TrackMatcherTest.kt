package dev.dertyp.services.lyrics.parse

import dev.dertyp.services.models.LyricsMatch
import kotlin.test.*

class TrackMatcherTest {
    @Test
    fun `matching track is verified`() {
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Hello World", "The Band", 200, "Hello World", "The Band", 203))
    }

    @Test
    fun `wrong artist is rejected`() {
        assertNull(TrackMatcher.verify("Hello World", "The Band", 200, "Hello World", "Other Group", 200))
    }

    @Test
    fun `nine second gap is rejected and eight accepted`() {
        assertNull(TrackMatcher.verify("Hello World", "The Band", 200, "Hello World", "The Band", 209))
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Hello World", "The Band", 200, "Hello World", "The Band", 192))
        assertEquals(9, TrackMatcher.durationGapSec(200, 209))
    }

    @Test
    fun `remastered suffix is accepted`() {
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Hello World", "The Band", 200, "Hello World (Remastered)", "The Band", 200))
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Hello World (Remastered 2011)", "The Band", 200, "Hello World", "The Band", 200))
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Hello World", "The Band", 200, "Hello World [2011 Remaster]", "The Band", null))
    }

    @Test
    fun `title must appear as whole words`() {
        assertNull(TrackMatcher.verify("Helloween", "The Band", 200, "Hello", "The Band", 200))
        assertNull(TrackMatcher.verify("Hello World", "The Band", 200, "Another Song", "The Band", 200))
    }

    @Test
    fun `one of several credited artists is enough, also from our title`() {
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Song", "Alice, Bob", 200, "Song", "Bob & Carol", 200))
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Song (feat. Carol)", "Alice", 200, "Song", "Dave feat. Carol", 200))
        assertNull(TrackMatcher.verify("Song", "Alice", 200, "Song", "Ali", 200))
    }

    @Test
    fun `accents and punctuation are ignored`() {
        assertEquals(LyricsMatch.VERIFIED, TrackMatcher.verify("Café del Mar!", "Beyoncé", 200, "cafe del mar", "BEYONCE", 200))
    }
}
