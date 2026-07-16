package dev.dertyp.services.subsonic

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SubsonicIdTest {
    private val uuid = UUID.fromString("a81bc81b-dead-4e5d-abff-90865d1e13b1")

    @Test
    fun `round trips every prefix`() {
        assertEquals(uuid, assertIs<SubsonicId.Song>(SubsonicId.parse(uuid.trId())).uuid)
        assertEquals(uuid, assertIs<SubsonicId.Album>(SubsonicId.parse(uuid.alId())).uuid)
        assertEquals(uuid, assertIs<SubsonicId.Artist>(SubsonicId.parse(uuid.arId())).uuid)
        assertEquals(uuid, assertIs<SubsonicId.Playlist>(SubsonicId.parse(uuid.plId())).uuid)
        assertEquals(uuid, assertIs<SubsonicId.Image>(SubsonicId.parse(uuid.imId())).uuid)
        assertEquals(uuid, assertIs<SubsonicId.RadioChannel>(SubsonicId.parse(uuid.rcId())).uuid)
    }

    @Test
    fun `rejects malformed input`() {
        assertNull(SubsonicId.parse(null))
        assertNull(SubsonicId.parse(""))
        assertNull(SubsonicId.parse("tr-"))
        assertNull(SubsonicId.parse("tr-not-a-uuid"))
        assertNull(SubsonicId.parse("xx-$uuid"))
        assertNull(SubsonicId.parse("$uuid"))
    }
}
