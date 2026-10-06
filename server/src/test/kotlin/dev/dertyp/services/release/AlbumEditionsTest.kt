package dev.dertyp.services.release

import dev.dertyp.services.release.AlbumEditions.fullName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AlbumEditionsTest {

    @Test
    fun `fullName appends a classified disambiguation to the release title`() {
        assertEquals(
            "The Divine Feminine (10th Anniversary)",
            fullName("The Divine Feminine", "10th Anniversary", "The Divine Feminine (10th Anniversary)")
        )
        assertEquals("Album (Deluxe Edition)", fullName("Album", " Deluxe Edition ", null))
        assertEquals("Album (2011 Remaster)", fullName("Album", "2011 Remaster", "Album"))
    }

    @Test
    fun `fullName never puts an unclassified disambiguation into the name`() {
        assertEquals("Album", fullName("Album", "explicit", "Album"))
        assertEquals("Album", fullName("Album", "clean", "Album"))
        assertEquals("Album", fullName("Album", "special clear vinyl", "Album"))
        assertEquals("Album", fullName("Album", "apple digital master", null))
        assertEquals("Album", fullName("Album", "", "Album"))
    }

    @Test
    fun `fullName keeps the edition of the release title and merges it with the disambiguation`() {
        assertEquals("Album (Deluxe Edition)", fullName("Album (Deluxe Edition)", null, "Album"))
        assertEquals("Album (Deluxe Edition)", fullName("Album (Deluxe Edition)", "deluxe edition", "Album"))
        assertEquals(
            "Album (Deluxe Edition) (2011 Remaster)",
            fullName("Album (Deluxe Edition)", "2011 Remaster", "Album (Expanded)")
        )
    }

    @Test
    fun `fullName falls back to the provider edition when the release carries none`() {
        assertEquals("Album (10th Anniversary)", fullName("Album", null, "Album (10th Anniversary)"))
        assertEquals("Album (Deluxe Edition)", fullName("Album", "special clear vinyl", "Other Title - Deluxe Edition"))
        assertEquals("Album", fullName("Album", null, "Album (Live at Wembley)"))
    }

    @Test
    fun `fullName returns the provider title unchanged without a release title`() {
        assertEquals("Album - Deluxe Edition", fullName(null, "10th Anniversary", "Album - Deluxe Edition"))
        assertEquals("Album (Explicit)", fullName(" ", null, "Album (Explicit)"))
        assertNull(fullName(null, null, null))
        assertNull(fullName(null, "10th Anniversary", null))
    }
}
