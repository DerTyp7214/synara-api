package dev.dertyp.services.import

import dev.dertyp.data.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ReleaseTrackMatchTest {

    private fun track(title: String, isrc: String?) = MusicBrainzTrack(
        id = UUID.randomUUID(),
        title = title,
        recording = MusicBrainzRecording(id = UUID.randomUUID(), title = title, isrcs = listOfNotNull(isrc))
    )

    private fun release(vararg tracks: MusicBrainzTrack) =
        MusicBrainzRelease(id = UUID.randomUUID(), media = listOf(MusicBrainzMedia(tracks = tracks.toList())))

    @Test
    fun `an ISRC match wins over an earlier track with the same title`() {
        val sameTitle = track("Dead Fresh", "USUG12500001")
        val byIsrc = track("Track 2", "USUG12500002")
        val release = release(sameTitle, byIsrc)

        assertEquals(byIsrc, release.findTrack(listOf("USUG12500002"), listOf("Dead Fresh")))
    }

    @Test
    fun `ISRCs are compared normalized`() {
        val byIsrc = track("Track 1", "usug1-25-00003")
        val release = release(track("Other", "USUG12500009"), byIsrc)

        assertEquals(byIsrc, release.findTrack(listOf("USUG12500003"), listOf("Unrelated")))
    }

    @Test
    fun `falls back to the cleaned title when no ISRC matches`() {
        val byTitle = track("Dead Fresh", null)
        val release = release(track("Track 1", "USUG12500001"), byTitle)

        assertEquals(byTitle, release.findTrack(listOf("USUG12500099"), listOf("dead fresh")))
        assertNull(release.findTrack(listOf("USUG12500099"), listOf("Missing")))
    }

    @Test
    fun `hasTracks is false for a release without tracks`() {
        assertFalse(MusicBrainzRelease(id = UUID.randomUUID()).hasTracks())
        assertFalse(release().hasTracks())
        assertTrue(release(track("Track 1", null)).hasTracks())
    }
}
