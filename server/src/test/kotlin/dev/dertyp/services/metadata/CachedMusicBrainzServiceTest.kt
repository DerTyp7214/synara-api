package dev.dertyp.services.metadata

import dev.dertyp.data.MusicBrainzArtist
import dev.dertyp.data.MusicBrainzRecording
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.data.MusicBrainzReleaseGroup
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

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
        testMetadataFetchLogic(
            rpcCall = { rpcService.getRecording(it) },
            cacheGet = { musicBrainzCacheService.getRecording(any()) },
            networkFetch = { musicBrainzService.fetchRecordingById(any(), any()) },
            cacheUpdate = { musicBrainzCacheService.updateRecordingCache(any()) },
            cachedValue = MusicBrainzRecording(id = id, fetchedAt = 123L),
            fetchedValue = MusicBrainzRecording(id = id, fetchedAt = 456L),
            fetchedAtZeroValue = MusicBrainzRecording(id = id, fetchedAt = 0L)
        )
    }

    @Test
    fun `getRelease fetch logic`() = runBlocking {
        val id = UUID.randomUUID()
        testMetadataFetchLogic(
            rpcCall = { rpcService.getRelease(it) },
            cacheGet = { musicBrainzCacheService.getRelease(any()) },
            networkFetch = { musicBrainzService.fetchReleaseById(any(), any()) },
            cacheUpdate = { musicBrainzCacheService.updateReleaseCache(any()) },
            cachedValue = MusicBrainzRelease(id = id, fetchedAt = 123L),
            fetchedValue = MusicBrainzRelease(id = id, fetchedAt = 456L),
            fetchedAtZeroValue = MusicBrainzRelease(id = id, fetchedAt = 0L)
        )
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
}
