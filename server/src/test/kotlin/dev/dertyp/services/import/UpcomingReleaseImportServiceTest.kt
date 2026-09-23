package dev.dertyp.services.import

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.*
import dev.dertyp.plugins.IImporter
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.AppleMusicService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.IMusicBrainzService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.release.AppleMusicReleaseService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class UpcomingReleaseImportServiceTest {
    private val appleMock = mockk<AppleMusicService>()
    private val tidalMock = mockk<MetadataService>()
    private val musicBrainzService = mockk<IMusicBrainzService>()
    private val appleMusicReleaseService = mockk<AppleMusicReleaseService>()
    private val importService = mockk<ImportService>(relaxed = true)
    private val songService = mockk<SongService>(relaxed = true)
    private val environment = mockk<ApplicationEnvironment>(relaxed = true)

    private val user = User(UUID.randomUUID(), "test", passwordHash = "hash")

    private lateinit var service: UpcomingReleaseImportService

    private val queued = mutableListOf<UrlImportQueueEntry>()

    @BeforeEach
    fun setup() {
        mockkObject(MetadataService)
        every {
            MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any())
        } returns appleMock
        every {
            MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any())
        } returns tidalMock

        coEvery { songService.byOriginalIds(any(), any()) } returns emptyList()
        coEvery { songService.byOriginalTracks(any(), any()) } returns emptyList()

        queued.clear()
        coEvery { importService.addToQueue(*anyVararg()) } answers {
            call.invocation.args.forEach { arg ->
                when (arg) {
                    is Array<*> -> arg.filterIsInstance<UrlImportQueueEntry>().forEach { queued += it }
                    is UrlImportQueueEntry -> queued += arg
                    else -> Unit
                }
            }
        }

        service = UpcomingReleaseImportService(
            importService,
            songService,
            musicBrainzService,
            appleMusicReleaseService,
            environment
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun tidalImporter(): IImporter = mockk<IImporter>().also {
        every { it.metadataType } returns IMetadataService.MetadataType.tidal
        every { it.id } returns "tiddl"
    }

    private fun catalogAlbum(
        id: String = APPLE_ID,
        title: String = "Apple Album",
        releaseDate: LocalDate?,
        isComplete: Boolean = true,
        upc: String? = null,
        trackCount: Int = 12
    ) = AppleMusicService.CatalogAlbum(
        id = id,
        title = title,
        artistName = "Apple Artist",
        artistIds = emptyList(),
        releaseDate = releaseDate,
        isSingle = false,
        isComplete = isComplete,
        isCompilation = false,
        upc = upc,
        url = null,
        trackCount = trackCount,
        image = IMetadataService.Image("https://artwork", 600, 600)
    )

    private fun appleTrack(
        id: String,
        title: String,
        isrc: String?,
        number: Int
    ) = IMetadataService.Track(
        id = id,
        title = title,
        artists = listOf("Apple Artist"),
        duration = 3.minutes,
        trackNumber = number,
        discNumber = 1,
        images = emptyList(),
        isrc = isrc
    )

    private fun tidalTrack(id: String, albumTitle: String? = null) = IMetadataService.Track(
        id = id,
        title = "Tidal Title",
        artists = listOf("Tidal Artist"),
        duration = 4.minutes,
        images = emptyList(),
        albumTitle = albumTitle
    )

    private fun librarySong(isrc: String, barcode: String?) = UserSong(
        id = UUID.randomUUID(),
        title = "Library Title",
        artists = emptyList(),
        album = Album(
            id = UUID.randomUUID(),
            name = "Library Album",
            artists = emptyList(),
            releaseDate = null,
            totalDuration = 0,
            barcode = barcode
        ),
        duration = 0L,
        explicit = false,
        path = "",
        originalUrl = "https://tidal.com/track/existing",
        isrc = isrc
    )

    private fun mbTrack(position: Int, title: String, isrc: String?) = MusicBrainzTrack(
        id = UUID.randomUUID(),
        position = position,
        number = position.toString(),
        title = title,
        recording = MusicBrainzRecording(
            id = UUID.randomUUID(),
            title = title,
            artistCredit = listOf(MusicBrainzArtistCredit(name = "MB Artist", joinphrase = "")),
            isrcs = listOfNotNull(isrc)
        )
    )

    private fun mbRelease(
        title: String = "MB Album",
        status: String? = "Official",
        barcode: String? = null,
        date: String? = null,
        tracks: List<MusicBrainzTrack> = emptyList(),
        trackCount: Int? = null
    ) = MusicBrainzRelease(
        id = UUID.randomUUID(),
        title = title,
        status = status,
        barcode = barcode,
        date = date,
        artistCredit = listOf(MusicBrainzArtistCredit(name = "MB Artist", joinphrase = "")),
        media = listOf(
            MusicBrainzMedia(
                format = "Digital Media",
                trackCount = trackCount ?: tracks.size,
                tracks = tracks
            )
        )
    )

    @Test
    fun `detect ignores importers that are not backed by tidal`() = runBlocking {
        val importer = mockk<IImporter>()
        every { importer.metadataType } returns IMetadataService.MetadataType.appleMusic

        assertNull(service.detect(APPLE_ALBUM_URL, importer))
    }

    @Test
    fun `detect ignores an apple album that is already released`() = runBlocking {
        every { appleMock.catalogEnabled } returns true
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = LocalDate.now().minusYears(1), isComplete = true)
        )

        assertNull(service.detect(APPLE_ALBUM_URL, tidalImporter()))
    }

    @Test
    fun `detect finds an apple album with a future release date`() = runBlocking {
        val date = LocalDate.now().plusMonths(2)
        every { appleMock.catalogEnabled } returns true
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = date, trackCount = 9)
        )

        val detected = assertNotNull(service.detect(APPLE_ALBUM_URL, tidalImporter()))
        assertEquals(UpcomingReleaseImportService.Source.Apple(APPLE_ID), detected.source)
        assertEquals("Apple Album", detected.title)
        assertEquals(listOf("Apple Artist"), detected.artists)
        assertEquals(date, detected.releaseDate)
        assertEquals(9, detected.trackCount)
    }

    @Test
    fun `detect finds an incomplete apple album even with a past date`() = runBlocking {
        every { appleMock.catalogEnabled } returns true
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = LocalDate.now().minusDays(3), isComplete = false)
        )

        assertNotNull(service.detect(APPLE_ALBUM_URL, tidalImporter()))
    }

    @Test
    fun `detect ignores apple song and artist urls`() = runBlocking {
        val importer = tidalImporter()

        assertNull(service.detect("https://music.apple.com/us/album/some-album/1234567890?i=987654321", importer))
        assertNull(service.detect("https://music.apple.com/us/artist/some-artist/1234567890", importer))
    }

    @Test
    fun `detect treats a musicbrainz release group with a partial next year date as upcoming`() = runBlocking {
        val groupId = UUID.randomUUID()
        val nextYear = LocalDate.now().year + 1
        coEvery { musicBrainzService.getReleasesByReleaseGroup(groupId) } returns listOf(
            mbRelease(
                date = "$nextYear-01",
                tracks = listOf(mbTrack(1, "One", "US1111111111"), mbTrack(2, "Two", "US2222222222"))
            )
        )

        val detected = assertNotNull(
            service.detect("https://musicbrainz.org/release-group/$groupId", tidalImporter())
        )
        assertEquals(UpcomingReleaseImportService.Source.MbReleaseGroup(groupId), detected.source)
        assertEquals("MB Album", detected.title)
        assertEquals(LocalDate.of(nextYear, 1, 1), detected.releaseDate)
        assertEquals(2, detected.trackCount)
    }

    @Test
    fun `detect falls back to the itunes lookup when the catalog is disabled`() = runBlocking {
        val date = LocalDate.now().plusMonths(1)
        every { appleMock.catalogEnabled } returns false
        coEvery { appleMock.getAlbumsByIds(listOf(APPLE_ID), any<HttpClientPriority>()) } returns listOf(
            IMetadataService.Album(
                id = APPLE_ID,
                title = "iTunes Album",
                artists = listOf("iTunes Artist"),
                trackCount = 5,
                releaseDate = date
            )
        )

        val detected = assertNotNull(service.detect(APPLE_ALBUM_URL, tidalImporter()))
        assertEquals("iTunes Album", detected.title)
        assertEquals(date, detected.releaseDate)
        assertEquals(5, detected.trackCount)
        coVerify { appleMock.getAlbumsByIds(listOf(APPLE_ID), any<HttpClientPriority>()) }
        coVerify(exactly = 0) { appleMock.getCatalogAlbumsByIds(any(), any()) }
    }

    @Test
    fun `resolve prefers the musicbrainz release found through the release group link`() = runBlocking {
        val groupId = UUID.randomUUID()
        val release = mbRelease(
            barcode = "0602445678901",
            date = "2099-11-06",
            tracks = listOf(mbTrack(3, "MB One", "US1111111111"), mbTrack(4, "MB Two", "US2222222222"))
        )

        every { appleMock.catalogEnabled } returns true
        every { appleMock.getAlbumTracks("appleMusic:$APPLE_ID", any()) } returns listOf(
            appleTrack("a1", "Apple One", "US1111111111", 1),
            appleTrack("a2", "Apple Two", "US2222222222", 2)
        ).asFlow()
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = LocalDate.of(2099, 11, 6), upc = "00602445678901")
        )
        coEvery { appleMusicReleaseService.releaseGroupIdFor(APPLE_ID) } returns groupId
        coEvery { musicBrainzService.getReleasesByReleaseGroup(groupId) } returns listOf(release)
        coEvery { tidalMock.getTrackByIsrc("US1111111111", any<HttpClientPriority>()) } returns tidalTrack("t1")
        coEvery { tidalMock.getTrackByIsrc("US2222222222", any<HttpClientPriority>()) } returns tidalTrack("t2")

        val plan = service.resolve(appleRelease())
        val tracks = plan.album.tracks.toList()

        assertEquals(release.id.toString(), plan.album.id)
        assertEquals("MB Album", plan.album.title)
        assertEquals(listOf("MB Artist"), plan.album.artists)
        assertEquals("0602445678901", plan.album.barcode)
        assertEquals(LocalDate.of(2099, 11, 6), plan.album.releaseDate)
        assertEquals(listOf("MB One", "MB Two"), tracks.map { it.title })
        assertEquals(listOf(3, 4), tracks.map { it.trackNumber })
        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
        assertEquals(listOf("US1111111111", "US2222222222"), tracks.map { it.isrc })
        assertTrue(plan.missing.isEmpty())
    }

    @Test
    fun `resolve keeps the apple values when no musicbrainz release matches`() = runBlocking {
        every { appleMock.catalogEnabled } returns true
        every { appleMock.getAlbumTracks("appleMusic:$APPLE_ID", any()) } returns listOf(
            appleTrack("a1", "Apple One", "US1111111111", 1)
        ).asFlow()
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = LocalDate.of(2099, 11, 6))
        )
        coEvery { appleMusicReleaseService.releaseGroupIdFor(APPLE_ID) } returns null
        coEvery { tidalMock.getTrackByIsrc("US1111111111", any<HttpClientPriority>()) } returns tidalTrack("t1")

        val plan = service.resolve(appleRelease())
        val tracks = plan.album.tracks.toList()

        assertEquals("appleMusic:$APPLE_ID", plan.album.id)
        assertEquals("Apple Album", plan.album.title)
        assertEquals(listOf("Apple Artist"), plan.album.artists)
        assertEquals(listOf("Apple One"), tracks.map { it.title })
        assertEquals(listOf(1), tracks.map { it.trackNumber })
    }

    @Test
    fun `resolve lists tracks without an isrc and tracks that are not on tidal as missing`() = runBlocking {
        every { appleMock.catalogEnabled } returns true
        every { appleMock.getAlbumTracks("appleMusic:$APPLE_ID", any()) } returns listOf(
            appleTrack("a1", "No Code", null, 1),
            appleTrack("a2", "Not Yet", "US2222222222", 2),
            appleTrack("a3", "Available", "US3333333333", 3)
        ).asFlow()
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = LocalDate.of(2099, 11, 6))
        )
        coEvery { appleMusicReleaseService.releaseGroupIdFor(APPLE_ID) } returns null
        coEvery { tidalMock.getTrackByIsrc("US2222222222", any<HttpClientPriority>()) } returns null
        coEvery { tidalMock.getTrackByIsrc("US3333333333", any<HttpClientPriority>()) } returns tidalTrack("t3")

        val plan = service.resolve(appleRelease())
        val tracks = plan.album.tracks.toList()

        assertEquals(listOf("t3"), tracks.map { it.id })
        assertEquals(listOf("No Code (no ISRC)", "Not Yet (not on Tidal yet)"), plan.missing)
    }

    @Test
    fun `resolve picks the release group candidate whose barcode matches the upc`() = runBlocking {
        val groupId = UUID.randomUUID()
        val matching = mbRelease(
            title = "Barcode Match",
            status = null,
            barcode = "0602445678901",
            date = "2099-11-06",
            tracks = listOf(mbTrack(1, "MB One", "US1111111111"))
        )
        val official = mbRelease(
            title = "Official Deluxe",
            status = "Official",
            barcode = "9999999999999",
            date = "2099-11-06",
            tracks = listOf(
                mbTrack(1, "MB One", "US1111111111"),
                mbTrack(2, "MB Two", "US2222222222"),
                mbTrack(3, "MB Three", "US4444444444")
            )
        )

        every { appleMock.catalogEnabled } returns true
        every { appleMock.getAlbumTracks("appleMusic:$APPLE_ID", any()) } returns listOf(
            appleTrack("a1", "Apple One", "US1111111111", 1)
        ).asFlow()
        coEvery { appleMock.getCatalogAlbumsByIds(listOf(APPLE_ID), any()) } returns listOf(
            catalogAlbum(releaseDate = LocalDate.of(2099, 11, 6), upc = "00602445678901")
        )
        coEvery { appleMusicReleaseService.releaseGroupIdFor(APPLE_ID) } returns groupId
        coEvery { musicBrainzService.getReleasesByReleaseGroup(groupId) } returns listOf(official, matching)
        coEvery { tidalMock.getTrackByIsrc("US1111111111", any<HttpClientPriority>()) } returns tidalTrack("t1")

        val plan = service.resolve(appleRelease())

        assertEquals(matching.id.toString(), plan.album.id)
        assertEquals("Barcode Match", plan.album.title)
    }

    @Test
    fun `submit queues the resolved tracks with the album as metadata`() = runBlocking {
        val album = IMetadataService.Album(
            id = "album-1",
            title = "Album",
            artists = listOf("Artist"),
            tracks = listOf(tidalTrack("t1"), tidalTrack("t2")).asFlow()
        )
        val importer = tidalImporter()

        val count = service.submit(UpcomingReleaseImportService.Plan(album, emptyList()), importer, user)

        assertEquals(2, count)
        assertEquals(1, queued.size)
        val entry = queued.single()
        assertEquals(Type.SONG, entry.type)
        assertEquals(ImportBackend("tiddl"), entry.importer)
        assertSame(album, entry.metadata)
        assertEquals(user.id, entry.byUser)
        assertEquals(
            listOf("https://tidal.com/track/t1", "https://tidal.com/track/t2"),
            entry.urls
        )
        assertEquals(listOf("t1", "t2"), entry.ids.toList())
    }

    @Test
    fun `submit keeps a track whose ISRC only exists on another release`() = runBlocking {
        val album = IMetadataService.Album(
            id = "album-1",
            title = "Album",
            barcode = "0123456789012",
            tracks = listOf(tidalTrack("t1").copy(isrc = "US1111111111")).asFlow()
        )
        coEvery { songService.byOriginalTracks(any(), any()) } returns listOf(
            librarySong(isrc = "US1111111111", barcode = "9999999999999")
        )

        val count = service.submit(UpcomingReleaseImportService.Plan(album, emptyList()), tidalImporter(), user)

        assertEquals(1, count)
        assertEquals(listOf("t1"), queued.single().ids.toList())
    }

    @Test
    fun `submit skips a track whose ISRC already exists on the same release`() = runBlocking {
        val album = IMetadataService.Album(
            id = "album-1",
            title = "Album",
            barcode = "0123456789012",
            tracks = listOf(tidalTrack("t1").copy(isrc = "US1111111111")).asFlow()
        )
        coEvery { songService.byOriginalTracks(any(), any()) } returns listOf(
            librarySong(isrc = "US1111111111", barcode = "123456789012")
        )

        val count = service.submit(UpcomingReleaseImportService.Plan(album, emptyList()), tidalImporter(), user)

        assertEquals(0, count)
        coVerify(exactly = 0) { importService.addToQueue(*anyVararg()) }
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `submit chunks the queue entries by twenty tracks`() = runBlocking {
        val album = IMetadataService.Album(
            id = "album-1",
            title = "Album",
            tracks = (1..25).map { tidalTrack("t$it") }.asFlow()
        )

        val count = service.submit(UpcomingReleaseImportService.Plan(album, emptyList()), tidalImporter(), user)

        assertEquals(25, count)
        assertEquals(listOf(20, 5), queued.map { it.urls.size })
    }

    private fun appleRelease() = UpcomingReleaseImportService.UpcomingRelease(
        url = APPLE_ALBUM_URL,
        source = UpcomingReleaseImportService.Source.Apple(APPLE_ID),
        title = "Apple Album",
        artists = listOf("Apple Artist"),
        releaseDate = LocalDate.of(2099, 11, 6),
        trackCount = 12
    )

    companion object {
        private const val APPLE_ID = "1234567890"
        private const val APPLE_ALBUM_URL = "https://music.apple.com/us/album/some-album/1234567890"
    }
}
