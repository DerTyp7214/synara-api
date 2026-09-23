package dev.dertyp.core

import dev.dertyp.data.*
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.IMetadataService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration

class FilterExistingTest {
    private val userId = UUID.randomUUID()
    private val user = mockk<User>().also { every { it.id } returns userId }

    private fun track(id: String, isrc: String?) = IMetadataService.Track(
        id = id,
        title = "Avantgarde",
        duration = Duration.ZERO,
        images = emptyList(),
        isrc = isrc,
    )

    private fun userSong(originalUrl: String, isrc: String?, album: Album? = null) = UserSong(
        id = UUID.randomUUID(),
        title = "Avantgarde",
        artists = emptyList(),
        album = album,
        duration = 0L,
        explicit = false,
        path = "",
        originalUrl = originalUrl,
        isrc = isrc,
    )

    private fun album(barcode: String?) = Album(
        id = UUID.randomUUID(),
        name = "Album",
        artists = emptyList(),
        releaseDate = null,
        totalDuration = 0,
        barcode = barcode,
    )

    private fun filter(
        deduplicateByIsrc: Boolean,
        songService: SongService,
        isrcAlbumBarcode: String? = null
    ): List<IMetadataService.Track> =
        runBlocking {
            flowOf(track(id = "album-track", isrc = "DEXXX0000001"))
                .filterExisting(
                    songService = songService,
                    user = user,
                    deduplicateByIsrc = deduplicateByIsrc,
                    isrcAlbumBarcode = isrcAlbumBarcode
                )
                .toList()
                .flatten()
        }

    @Test
    fun `with ISRC dedup on, a track whose ISRC already exists is filtered out`() {
        val songService = mockk<SongService>()

        coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns emptyList()
        coEvery { songService.byOriginalTracks(any(), any()) } returns
            listOf(userSong(originalUrl = "https://tidal.com/track/single-id", isrc = "DEXXX0000001"))

        val result = filter(deduplicateByIsrc = true, songService = songService)
        assertTrue(result.isEmpty(), "ISRC match should filter the track out when dedup is on")
    }

    @Test
    fun `with ISRC dedup off, a track whose ISRC already exists passes through`() {
        val songService = mockk<SongService>()
        coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns emptyList()

        coEvery { songService.byOriginalTracks(any(), any()) } returns
            listOf(userSong(originalUrl = "https://tidal.com/track/single-id", isrc = "DEXXX0000001"))

        val result = filter(deduplicateByIsrc = false, songService = songService)
        assertEquals(1, result.size, "ISRC match must not filter the track when dedup is off")
        assertEquals("album-track", result.single().id)
    }

    @Test
    fun `within a barcode scope, a same-ISRC song of that release is filtered out`() {
        val songService = mockk<SongService>()

        coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns emptyList()
        coEvery { songService.byOriginalTracks(any(), any()) } returns listOf(
            userSong(
                originalUrl = "https://tidal.com/track/other-id",
                isrc = "DEXXX0000001",
                album = album("123456789012")
            )
        )

        val result = filter(deduplicateByIsrc = true, songService = songService, isrcAlbumBarcode = "0123456789012")
        assertTrue(result.isEmpty(), "same ISRC on the same release should filter the track out")
    }

    @Test
    fun `within a barcode scope, a same-ISRC song of another release passes through`() {
        val songService = mockk<SongService>()

        coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns emptyList()
        coEvery { songService.byOriginalTracks(any(), any()) } returns listOf(
            userSong(
                originalUrl = "https://tidal.com/track/other-id",
                isrc = "DEXXX0000001",
                album = album("9999999999999")
            )
        )

        val result = filter(deduplicateByIsrc = true, songService = songService, isrcAlbumBarcode = "0123456789012")
        assertEquals(1, result.size, "a same ISRC on another release must not filter the track")
        assertEquals("album-track", result.single().id)
    }

    @Test
    fun `within a barcode scope, a same-ISRC song without an album passes through`() {
        val songService = mockk<SongService>()

        coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns emptyList()
        coEvery { songService.byOriginalTracks(any(), any()) } returns listOf(
            userSong(originalUrl = "https://tidal.com/track/other-id", isrc = "DEXXX0000001", album = null)
        )

        val result = filter(deduplicateByIsrc = true, songService = songService, isrcAlbumBarcode = "0123456789012")
        assertEquals(1, result.size, "a same ISRC without an album must not filter the track")
    }

    @Test
    fun `without a barcode scope, a same-ISRC song of another release still filters the track`() {
        val songService = mockk<SongService>()

        coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns emptyList()
        coEvery { songService.byOriginalTracks(any(), any()) } returns listOf(
            userSong(
                originalUrl = "https://tidal.com/track/other-id",
                isrc = "DEXXX0000001",
                album = album("9999999999999")
            )
        )

        val result = filter(deduplicateByIsrc = true, songService = songService)
        assertTrue(result.isEmpty(), "without a scope the ISRC match still filters the track out")
    }

    @Test
    fun `exact tidal id skip still applies in both modes`() {
        for (dedup in listOf(true, false)) {
            val songService = mockk<SongService>()

            coEvery { songService.byOriginalIds(any<Collection<String>>(), any()) } returns
                listOf(userSong(originalUrl = "https://tidal.com/track/album-track", isrc = null))
            coEvery { songService.byOriginalTracks(any(), any()) } returns emptyList()

            val result = filter(deduplicateByIsrc = dedup, songService = songService)
            assertTrue(result.isEmpty(), "exact tidal-id match should always filter the track out (dedup=$dedup)")
        }
    }
}
