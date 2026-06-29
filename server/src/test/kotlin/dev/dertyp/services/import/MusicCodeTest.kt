package dev.dertyp.services.import

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class MusicCodeTest {
    @Test
    fun `classifies isrc`() {
        assertEquals(MusicCode.Isrc("USRC17607839"), MusicCode.classify("USRC17607839"))
    }

    @Test
    fun `classifies isrc with separators and lowercase`() {
        assertEquals(MusicCode.Isrc("USRC17607839"), MusicCode.classify("us-rc1-76-07839"))
    }

    @Test
    fun `classifies upc and ean`() {
        assertEquals(MusicCode.Upc("060243532938"), MusicCode.classify("060243532938")) // 12
        assertEquals(MusicCode.Upc("0602435329383"), MusicCode.classify("0602435329383")) // 13
        assertEquals(MusicCode.Upc("00602435329383"), MusicCode.classify("0 06024-353293 83")) // 14, separators
    }

    @Test
    fun `classifies urls`() {
        assertEquals(MusicCode.Url, MusicCode.classify("https://tidal.com/track/1"))
        assertEquals(MusicCode.Url, MusicCode.classify("tidal.com/album/2"))
        assertEquals(MusicCode.Url, MusicCode.classify("open.spotify.com/track/abc"))
    }

    @Test
    fun `random numeric of wrong length is not a code`() {
        assertEquals(MusicCode.Url, MusicCode.classify("12345"))
        assertEquals(MusicCode.Url, MusicCode.classify("not-a-code"))
    }
}
