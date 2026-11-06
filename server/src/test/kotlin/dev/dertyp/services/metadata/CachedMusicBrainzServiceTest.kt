package dev.dertyp.services.metadata

import dev.dertyp.data.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class CachedMusicBrainzServiceTest {

    private lateinit var musicBrainzService: MusicBrainzService
    private lateinit var musicBrainzCacheService: MusicBrainzCacheService
    private lateinit var rpcService: CachedMusicBrainzService

    @BeforeEach
    fun setup() {
        musicBrainzService = mockk()
        musicBrainzCacheService = mockk()
        rpcService = CachedMusicBrainzService(musicBrainzService, musicBrainzCacheService)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private suspend fun <T> testMetadataFetchLogic(
        rpcCall: suspend (UUID) -> T?,
        cacheGet: suspend MockKMatcherScope.() -> T?,
        networkFetch: suspend MockKMatcherScope.() -> T?,
        cacheUpdate: suspend MockKMatcherScope.() -> Any?,
        cachedValue: T,
        fetchedValue: T,
        fetchedAtZeroValue: T
    ) {
        val id = UUID.randomUUID()

        coEvery { cacheGet() } returns cachedValue
        assertEquals(cachedValue, rpcCall(id))
        coVerify(exactly = 0) { networkFetch() }

        coEvery { cacheGet() } returns fetchedAtZeroValue
        coEvery { networkFetch() } returns fetchedValue
        coEvery { cacheUpdate() } returns mockk()
        assertEquals(fetchedValue, rpcCall(id))
        coVerify(exactly = 1) { networkFetch() }
        coVerify(exactly = 1) { cacheUpdate() }

        clearMocks(musicBrainzService, musicBrainzCacheService, answers = true, verificationMarks = true)
        coEvery { cacheGet() } returns null
        coEvery { networkFetch() } returns fetchedValue
        coEvery { cacheUpdate() } returns mockk()
        assertEquals(fetchedValue, rpcCall(id))
        coVerify(exactly = 1) { networkFetch() }
        coVerify(exactly = 1) { cacheUpdate() }

        clearMocks(musicBrainzService, musicBrainzCacheService, answers = true, verificationMarks = true)
        coEvery { cacheGet() } returns fetchedAtZeroValue
        coEvery { networkFetch() } returns null
        assertEquals(fetchedAtZeroValue, rpcCall(id))
        coVerify(exactly = 1) { networkFetch() }
    }

    @Test
    fun `getArtist fetch logic`() = runBlocking {
        val id = UUID.randomUUID()
        testMetadataFetchLogic(
            rpcCall = { rpcService.getArtist(it) },
            cacheGet = { musicBrainzCacheService.getArtist(any()) },
            networkFetch = { musicBrainzService.fetchArtistById(any(), any()) },
            cacheUpdate = { musicBrainzCacheService.updateArtistCache(any()) },
            cachedValue = MusicBrainzArtist(id = id, fetchedAt = 123L),
            fetchedValue = MusicBrainzArtist(id = id, fetchedAt = 456L),
            fetchedAtZeroValue = MusicBrainzArtist(id = id, fetchedAt = 0L)
        )
    }

    @Test
    fun `getRecording fetch logic`() = runBlocking {
        val id = UUID.randomUUID()
        val artists = listOf(
            MusicBrainzArtistCredit(
                name = "Artist",
                artist = MusicBrainzArtist(id = UUID.randomUUID(), name = "Artist")
            )
        )
        testMetadataFetchLogic(
            rpcCall = { rpcService.getRecording(it) },
            cacheGet = { musicBrainzCacheService.getRecording(any()) },
            networkFetch = { musicBrainzService.fetchRecordingById(any(), any()) },
            cacheUpdate = { musicBrainzCacheService.updateRecordingCache(any()) },
            cachedValue = MusicBrainzRecording(id = id, title = "Title", artistCredit = artists, fetchedAt = 123L),
            fetchedValue = MusicBrainzRecording(id = id, title = "Title", artistCredit = artists, fetchedAt = 456L),
            fetchedAtZeroValue = MusicBrainzRecording(id = id, title = "Title", artistCredit = artists, fetchedAt = 0L)
        )
    }

    @Test
    fun `getRecording should fetch from network if cached recording is incomplete`() = runBlocking {
        val id = UUID.randomUUID()
        val artists = listOf(
            MusicBrainzArtistCredit(
                name = "Artist",
                artist = MusicBrainzArtist(id = UUID.randomUUID(), name = "Artist")
            )
        )
        val completeRecording = MusicBrainzRecording(id = id, title = "Title", artistCredit = artists, fetchedAt = 456L)

        val incomplete1 = MusicBrainzRecording(id = id, title = null, artistCredit = artists, fetchedAt = 123L)
        coEvery { musicBrainzCacheService.getRecording(id) } returns incomplete1
        coEvery { musicBrainzService.fetchRecordingById(id, any()) } returns completeRecording
        coEvery { musicBrainzCacheService.updateRecordingCache(completeRecording) } returns mockk()

        assertEquals(completeRecording, rpcService.getRecording(id))
        coVerify(exactly = 1) { musicBrainzService.fetchRecordingById(id, any()) }

        val incomplete2 = MusicBrainzRecording(id = id, title = "Title", artistCredit = emptyList(), fetchedAt = 123L)
        coEvery { musicBrainzCacheService.getRecording(id) } returns incomplete2

        assertEquals(completeRecording, rpcService.getRecording(id))
        coVerify(exactly = 2) { musicBrainzService.fetchRecordingById(id, any()) }
    }

    @Test
    fun `getRelease fetch logic`() = runBlocking {
        val id = UUID.randomUUID()
        val mediaWithTracks = listOf(MusicBrainzMedia(tracks = listOf(MusicBrainzTrack(id = UUID.randomUUID()))))
        testMetadataFetchLogic(
            rpcCall = { rpcService.getRelease(it) },
            cacheGet = { musicBrainzCacheService.getRelease(any()) },
            networkFetch = { musicBrainzService.fetchReleaseById(any(), any()) },
            cacheUpdate = { musicBrainzCacheService.updateReleaseCache(any()) },
            cachedValue = MusicBrainzRelease(id = id, date = "2001-03-12", fetchedAt = 123L, media = mediaWithTracks),
            fetchedValue = MusicBrainzRelease(id = id, fetchedAt = 456L, media = mediaWithTracks),
            fetchedAtZeroValue = MusicBrainzRelease(id = id, fetchedAt = 0L, media = mediaWithTracks)
        )
    }

    @Test
    fun `getRelease refetches a recent release whose cache entry is older than a day`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val mediaWithTracks = listOf(MusicBrainzMedia(tracks = listOf(MusicBrainzTrack(id = UUID.randomUUID()))))
        val now = System.currentTimeMillis()
        val recentDate = LocalDate.now(ZoneOffset.UTC).minusDays(3).toString()
        val stale = MusicBrainzRelease(id = id, date = recentDate, fetchedAt = now - 2.days.inWholeMilliseconds, media = mediaWithTracks)
        val fetched = MusicBrainzRelease(id = id, date = recentDate, fetchedAt = now, media = mediaWithTracks)

        coEvery { musicBrainzCacheService.getRelease(id) } returns stale
        coEvery { musicBrainzService.fetchReleaseById(id, any()) } returns fetched
        coEvery { musicBrainzCacheService.updateReleaseCache(fetched) } returns Unit
        assertEquals(fetched, rpcService.getRelease(id))
        coVerify(exactly = 1) { musicBrainzCacheService.updateReleaseCache(fetched) }

        coEvery { musicBrainzService.fetchReleaseById(id, any()) } returns null
        assertEquals(stale, rpcService.getRelease(id))

        val undated = stale.copy(date = null)
        coEvery { musicBrainzCacheService.getRelease(id) } returns undated
        assertEquals(undated, rpcService.getRelease(id))
        coVerify(exactly = 3) { musicBrainzService.fetchReleaseById(id, any()) }
    }

    @Test
    fun `getRelease serves a recent release fetched within the last day from the cache`(): Unit = runBlocking {
        val id = UUID.randomUUID()
        val mediaWithTracks = listOf(MusicBrainzMedia(tracks = listOf(MusicBrainzTrack(id = UUID.randomUUID()))))
        val fresh = MusicBrainzRelease(
            id = id,
            date = LocalDate.now(ZoneOffset.UTC).toString(),
            fetchedAt = System.currentTimeMillis() - 1.hours.inWholeMilliseconds,
            media = mediaWithTracks
        )

        coEvery { musicBrainzCacheService.getRelease(id) } returns fresh
        assertEquals(fresh, rpcService.getRelease(id))
        coVerify(exactly = 0) { musicBrainzService.fetchReleaseById(any(), any()) }
    }

    @Test
    fun `getReleaseGroup fetch logic`() = runBlocking {
        val id = UUID.randomUUID()
        testMetadataFetchLogic(
            rpcCall = { rpcService.getReleaseGroup(it) },
            cacheGet = { musicBrainzCacheService.getReleaseGroup(any()) },
            networkFetch = { musicBrainzService.fetchReleaseGroupById(any(), any()) },
            cacheUpdate = { musicBrainzCacheService.updateReleaseGroupCache(any()) },
            cachedValue = MusicBrainzReleaseGroup(id = id, title = "T", fetchedAt = 123L),
            fetchedValue = MusicBrainzReleaseGroup(id = id, title = "T", fetchedAt = 456L),
            fetchedAtZeroValue = MusicBrainzReleaseGroup(id = id, title = "T", fetchedAt = 0L)
        )
    }

    @Test
    fun `getRecordingByIsrc fetch logic`() = runBlocking {
        val id = UUID.randomUUID()
        val isrc = "USUM71900764"
        val artists = listOf(
            MusicBrainzArtistCredit(
                name = "Artist",
                artist = MusicBrainzArtist(id = UUID.randomUUID(), name = "Artist")
            )
        )
        val cachedValue = MusicBrainzRecording(
            id = id,
            title = "Title",
            artistCredit = artists,
            fetchedAt = 123L,
            isrcs = listOf(isrc)
        )
        val fetchedValue = MusicBrainzRecording(
            id = id,
            title = "Title",
            artistCredit = artists,
            fetchedAt = 456L,
            isrcs = listOf(isrc)
        )
        val fetchedAtZeroValue =
            MusicBrainzRecording(id = id, title = "Title", artistCredit = artists, fetchedAt = 0L, isrcs = listOf(isrc))

        coEvery { musicBrainzCacheService.getRecordingByIsrc(isrc) } returns cachedValue
        assertEquals(cachedValue, rpcService.getRecordingByIsrc(isrc))
        coVerify(exactly = 0) { musicBrainzService.fetchRecordingByIsrc(any(), any()) }

        coEvery { musicBrainzCacheService.getRecordingByIsrc(isrc) } returns fetchedAtZeroValue
        coEvery { musicBrainzService.fetchRecordingByIsrc(isrc, any()) } returns fetchedValue
        coEvery { musicBrainzCacheService.updateRecordingCache(any()) } returns mockk()
        assertEquals(fetchedValue, rpcService.getRecordingByIsrc(isrc))
        coVerify(exactly = 1) { musicBrainzService.fetchRecordingByIsrc(isrc, any()) }
        coVerify(exactly = 1) { musicBrainzCacheService.updateRecordingCache(any()) }

        clearMocks(musicBrainzService, musicBrainzCacheService, answers = true, verificationMarks = true)
        coEvery { musicBrainzCacheService.getRecordingByIsrc(isrc) } returns null
        coEvery { musicBrainzService.fetchRecordingByIsrc(isrc, any()) } returns fetchedValue
        coEvery { musicBrainzCacheService.updateRecordingCache(any()) } returns mockk()
        assertEquals(fetchedValue, rpcService.getRecordingByIsrc(isrc))
        coVerify(exactly = 1) { musicBrainzService.fetchRecordingByIsrc(isrc, any()) }
        coVerify(exactly = 1) { musicBrainzCacheService.updateRecordingCache(any()) }
    }
}
