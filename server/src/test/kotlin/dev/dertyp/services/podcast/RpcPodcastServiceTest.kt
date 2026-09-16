package dev.dertyp.services.podcast

import dev.dertyp.StreamInfo
import dev.dertyp.data.EpisodePlaybackReport
import io.ktor.http.ContentType
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastShowSettings
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class RpcPodcastServiceTest {
    private val podcastService = mockk<PodcastService>(relaxed = true)
    private val feedService = mockk<PodcastFeedService>(relaxed = true)
    private val localScanService = mockk<PodcastLocalScanService>(relaxed = true)
    private val importService = mockk<PodcastImportService>(relaxed = true)
    private val streamService = mockk<PodcastStreamService>(relaxed = true)
    private val user = User(UUID.randomUUID(), "user", passwordHash = "hash")
    private val service = RpcPodcastService(user, podcastService, feedService, localScanService, importService, streamService)

    private fun show() = PodcastShow(id = UUID.randomUUID(), source = PodcastSource.FEED, title = "Show", createdAt = 0, updatedAt = 0)

    private fun episode(id: UUID = UUID.randomUUID()) = PodcastEpisode(
        id = id,
        showId = UUID.randomUUID(),
        showTitle = "Show",
        guid = "guid",
        title = "Episode",
        publishedAt = 0,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `subscribe forwards the feedUrl to the feed service`() = runBlocking {
        val feedUrl = "https://feed.example/show.xml"
        coEvery { feedService.subscribe(user.id, feedUrl) } returns show()

        service.subscribe(feedUrl)

        coVerify(exactly = 1) { feedService.subscribe(user.id, feedUrl) }
    }

    @Test
    fun `subscribeToShow forwards the user id and show id`() = runBlocking {
        val showId = UUID.randomUUID()

        service.subscribeToShow(showId)

        coVerify(exactly = 1) { podcastService.subscribeToShow(user.id, showId) }
    }

    @Test
    fun `unsubscribe forwards the user id and show id`() = runBlocking {
        val showId = UUID.randomUUID()

        service.unsubscribe(showId)

        coVerify(exactly = 1) { podcastService.unsubscribe(user.id, showId) }
    }

    @Test
    fun `getSubscriptions forwards the user id`() = runBlocking {
        service.getSubscriptions()

        coVerify(exactly = 1) { podcastService.getSubscriptions(user.id) }
    }

    @Test
    fun `browseShows forwards the user id query page and pageSize`() = runBlocking {
        service.browseShows("term", 2, 25)

        coVerify(exactly = 1) { podcastService.browseShows(user.id, "term", 2, 25) }
    }

    @Test
    fun `getShow forwards the user id and show id`() = runBlocking {
        val showId = UUID.randomUUID()

        service.getShow(showId)

        coVerify(exactly = 1) { podcastService.getShow(user.id, showId) }
    }

    @Test
    fun `getEpisodes forwards the user id show id page pageSize and newestFirst`() = runBlocking {
        val showId = UUID.randomUUID()

        service.getEpisodes(showId, 1, 10, false)

        coVerify(exactly = 1) { podcastService.getEpisodes(user.id, showId, 1, 10, false) }
    }

    @Test
    fun `getEpisode forwards the user id and episode id`() = runBlocking {
        val episodeId = UUID.randomUUID()

        service.getEpisode(episodeId)

        coVerify(exactly = 1) { podcastService.getEpisode(user.id, episodeId) }
    }

    @Test
    fun `getEpisodesByIds forwards the user id and episode ids`() = runBlocking {
        val episodeIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        service.getEpisodesByIds(episodeIds)

        coVerify(exactly = 1) { podcastService.getEpisodesByIds(user.id, episodeIds) }
    }

    @Test
    fun `searchEpisodes forwards the user id query page and pageSize`() = runBlocking {
        service.searchEpisodes("term", 3, 20)

        coVerify(exactly = 1) { podcastService.searchEpisodes(user.id, "term", 3, 20) }
    }

    @Test
    fun `getLatestEpisodes forwards the user id page and pageSize`() = runBlocking {
        service.getLatestEpisodes(0, 50)

        coVerify(exactly = 1) { podcastService.getLatestEpisodes(user.id, 0, 50) }
    }

    @Test
    fun `getInProgress forwards the user id page and pageSize`() = runBlocking {
        service.getInProgress(0, 50)

        coVerify(exactly = 1) { podcastService.getInProgress(user.id, 0, 50) }
    }

    @Test
    fun `getLastPlayed forwards the user id and the includeCompleted flag`() = runBlocking {
        service.getLastPlayed(false)

        coVerify(exactly = 1) { podcastService.getLastPlayed(user.id, false) }
    }

    @Test
    fun `reportPlayback forwards the user id and report`() = runBlocking {
        val report = EpisodePlaybackReport(episodeId = UUID.randomUUID(), positionMs = 1000)

        service.reportPlayback(report)

        coVerify(exactly = 1) { podcastService.reportPlayback(user.id, report) }
    }

    @Test
    fun `setPlayed forwards the user id episode id and played flag`() = runBlocking {
        val episodeId = UUID.randomUUID()

        service.setPlayed(episodeId, true)

        coVerify(exactly = 1) { podcastService.setPlayed(user.id, episodeId, true) }
    }

    @Test
    fun `observeProgress forwards the user id`() {
        service.observeProgress()

        verify(exactly = 1) { podcastService.observeProgress(user.id) }
    }

    @Test
    fun `refreshShow refreshes through the feed service then reads the show through the podcast service`() = runBlocking {
        val showId = UUID.randomUUID()
        val refreshed = show()
        coEvery { podcastService.getShow(user.id, showId) } returns refreshed

        val result = service.refreshShow(showId)

        coVerify(exactly = 1) { feedService.refreshShow(showId) }
        coVerify(exactly = 1) { podcastService.getShow(user.id, showId) }
        assertEquals(refreshed, result)
    }

    @Test
    fun `updateShowSettings forwards the show id settings and user id`() = runBlocking {
        val showId = UUID.randomUUID()
        val settings = PodcastShowSettings(PodcastDeliveryMode.IMPORT, keepEpisodes = 5)

        service.updateShowSettings(showId, settings)

        coVerify(exactly = 1) { podcastService.updateShowSettings(showId, settings, user.id) }
    }

    @Test
    fun `importEpisode imports through the import service then reads the episode through the podcast service`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val imported = episode(episodeId)
        coEvery { podcastService.getEpisode(user.id, episodeId) } returns imported

        val result = service.importEpisode(episodeId)

        coVerify(exactly = 1) { importService.importEpisode(episodeId) }
        coVerify(exactly = 1) { podcastService.getEpisode(user.id, episodeId) }
        assertEquals(imported, result)
    }

    @Test
    fun `removeImport removes through the import service then reads the episode through the podcast service`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val removed = episode(episodeId)
        coEvery { podcastService.getEpisode(user.id, episodeId) } returns removed

        val result = service.removeImport(episodeId)

        coVerify(exactly = 1) { importService.removeImport(episodeId) }
        coVerify(exactly = 1) { podcastService.getEpisode(user.id, episodeId) }
        assertEquals(removed, result)
    }

    @Test
    fun `scanLocal forwards to the local scan service`() = runBlocking {
        service.scanLocal()

        coVerify(exactly = 1) { localScanService.scan(any()) }
    }

    @Test
    fun `streamEpisode forwards the episode id offset and chunkSize`() {
        val episodeId = UUID.randomUUID()

        service.streamEpisode(episodeId, 512, 8192)

        verify(exactly = 1) { streamService.streamEpisode(episodeId, 512, 8192) }
    }

    @Test
    fun `getStreamSize forwards the episode id`() = runBlocking {
        val episodeId = UUID.randomUUID()

        service.getStreamSize(episodeId)

        coVerify(exactly = 1) { streamService.getStreamSize(episodeId) }
    }

    @Test
    fun `getTranscripts forwards the user id and episode id`() = runBlocking {
        val episodeId = UUID.randomUUID()

        service.getTranscripts(episodeId)

        coVerify(exactly = 1) { podcastService.getTranscripts(user.id, episodeId) }
    }

    @Test
    fun `getTranscript forwards the user id and transcript id`() = runBlocking {
        val transcriptId = UUID.randomUUID()

        service.getTranscript(transcriptId)

        coVerify(exactly = 1) { podcastService.getTranscript(user.id, transcriptId) }
    }

    @Test
    fun `getFile resolves the stream service for streamEpisode and null for anything else`() = runBlocking {
        val episodeId = UUID.randomUUID()
        val streamInfo = StreamInfo(File("episode.mp3"), ContentType.Audio.MPEG, 1024L, "episode.mp3")
        coEvery { streamService.resolveFile(episodeId) } returns streamInfo

        val resolved = service.getFile("streamEpisode", listOf(episodeId, 0L, 4096))
        assertSame(streamInfo, resolved)
        coVerify(exactly = 1) { streamService.resolveFile(episodeId) }

        val other = service.getFile("other", listOf(episodeId))
        assertNull(other)
    }
}
