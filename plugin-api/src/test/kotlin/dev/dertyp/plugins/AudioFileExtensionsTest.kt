package dev.dertyp.plugins

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFileExtensionsTest {
    @Test
    fun testArtistSplitter() {
        assertEquals(listOf("Artist 1", "Artist 2"), artistSplitter("Artist 1; Artist 2"))
        assertEquals(listOf("Artist 1"), artistSplitter("Artist 1; "))
        assertEquals(listOf("Artist 1", "Artist 2"), artistSplitter("Artist 1 ;Artist 2 "))
        assertEquals(emptyList(), artistSplitter(";"))

        assertEquals(listOf("Artist 1", "Artist 2"), artistSplitter("Artist 1, Artist 2", ","))
    }

    @Test
    fun testAudioFileProperties() {
        val audioFile = mockk<AudioFile>()
        val tag = mockk<Tag>()
        
        every { audioFile.tag } returns tag
        
        every { tag.getFirst(FieldKey.TITLE) } returns "My Title"
        assertEquals("My Title", audioFile.title)

        every { tag.getAll(FieldKey.ARTISTS) } returns listOf("Artist 1; Artist 2")
        assertEquals(listOf("Artist 1", "Artist 2"), audioFile.getArtists())

        every { tag.getFirst(FieldKey.YEAR) } returns "2023"
        assertEquals("2023", audioFile.year)

        every { tag.getFirst(FieldKey.ALBUM) } returns "My Album"
        assertEquals("My Album", audioFile.album)

        every { tag.getFirst(FieldKey.TRACK_TOTAL) } returns "10"
        assertEquals(10, audioFile.songCount)

        every { tag.getAll(FieldKey.ALBUM_ARTISTS) } returns listOf("Album Artist 1; Album Artist 2")
        assertEquals(listOf("Album Artist 1", "Album Artist 2"), audioFile.getAlbumArtists())

        every { tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID) } returns "mb-track-id"
        assertEquals("mb-track-id", audioFile.musicBrainzTrackId)
    }

    @Test
    fun testIsExplicit() {
        val audioFile = mockk<AudioFile>()
        val tag = mockk<Tag>()
        every { audioFile.tag } returns tag

        every { tag.getFirst(any<String>()) } returns ""
        assertFalse(audioFile.isExplicit)

        every { tag.getFirst("ADVISORY") } returns "1"
        assertTrue(audioFile.isExplicit)

        every { tag.getFirst("ADVISORY") } returns "0"
        every { tag.getFirst("ITUNESADVISORY") } returns "1"
        assertTrue(audioFile.isExplicit)

        every { tag.getFirst("ITUNESADVISORY") } returns "2"
        every { tag.getFirst("EXPLICIT") } returns "true"
        assertTrue(audioFile.isExplicit)

        every { tag.getFirst("EXPLICIT") } returns "0"
        every { tag.getFirst("CONTENTRATING") } returns "Explicit lyrics"
        assertTrue(audioFile.isExplicit)

        every { tag.getFirst("CONTENTRATING") } returns ""
        every { tag.getFirst("KEYWORDS") } returns "tag1, explicit, tag2"
        assertTrue(audioFile.isExplicit)
    }

    @Test
    fun testSetExplicitVorbis() {
        val audioFile = mockk<AudioFile>()
        val tag = mockk<VorbisCommentTag>(relaxed = true)
        every { audioFile.tag } returns tag

        audioFile.setExplicit(true)
        verify { tag.setField("ADVISORY", "1") }
        verify { tag.setField("ITUNESADVISORY", "1") }
        verify { tag.setField("EXPLICIT", "1") }
        verify { tag.setField("KEYWORDS", "explicit") }

        every { tag.getFirst("KEYWORDS") } returns "rock, explicit"
        audioFile.setExplicit(false)
        verify { tag.setField("ADVISORY", "0") }
        verify { tag.setField("ITUNESADVISORY", "2") }
        verify { tag.deleteField("EXPLICIT") }
        verify { tag.setField("KEYWORDS", "rock") }
    }
}
