package dev.dertyp.plugins

import io.mockk.every
import io.mockk.mockk
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
