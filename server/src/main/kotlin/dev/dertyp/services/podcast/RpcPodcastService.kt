package dev.dertyp.services.podcast

import dev.dertyp.StreamInfo
import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.data.PodcastIndexInfo
import dev.dertyp.data.PodcastIndexResult
import dev.dertyp.data.PodcastScanResult
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastShowSettings
import dev.dertyp.data.PodcastTranscript
import dev.dertyp.data.PodcastTranscriptContent
import dev.dertyp.data.User
import dev.dertyp.routing.rest.RestFileProvider
import dev.dertyp.services.IPodcastService
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcPodcastService(
    private val user: User,
    private val podcastService: PodcastService,
    private val feedService: PodcastFeedService,
    private val localScanService: PodcastLocalScanService,
    private val importService: PodcastImportService,
    private val streamService: PodcastStreamService,
    private val indexService: PodcastIndexService,
    private val maintenanceService: PodcastMaintenanceService
) : IPodcastService,
    RestFileProvider {
    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        if (methodName == "streamEpisode") {
            val episodeId = args[0] as? UUID ?: return null
            return streamService.resolveFile(episodeId)
        }
        return null
    }

    override suspend fun subscribe(feedUrl: String): PodcastShow = feedService.subscribe(user.id, feedUrl)

    override suspend fun subscribeToShow(showId: UUID): PodcastShow = podcastService.subscribeToShow(user.id, showId)

    override suspend fun unsubscribe(showId: UUID): Boolean = podcastService.unsubscribe(user.id, showId)

    override suspend fun getSubscriptions(): List<PodcastShow> = podcastService.getSubscriptions(user.id)

    override suspend fun browseShows(query: String, page: Int, pageSize: Int): PaginatedResponse<PodcastShow> =
        podcastService.browseShows(user.id, query, page, pageSize)

    override suspend fun searchIndex(query: String, limit: Int, indexes: List<String>): List<PodcastIndexResult> =
        indexService.search(query, limit, indexes)

    override suspend fun getIndexes(): List<PodcastIndexInfo> = indexService.indexes()

    override suspend fun getShow(showId: UUID): PodcastShow? = podcastService.getShow(user.id, showId)

    override suspend fun getEpisodes(
        showId: UUID,
        page: Int,
        pageSize: Int,
        newestFirst: Boolean
    ): PaginatedResponse<PodcastEpisode> = podcastService.getEpisodes(user.id, showId, page, pageSize, newestFirst)

    override suspend fun getEpisode(episodeId: UUID): PodcastEpisode? = podcastService.getEpisode(user.id, episodeId)

    override suspend fun getEpisodesByIds(@LogParam("size") episodeIds: List<UUID>): List<PodcastEpisode> =
        podcastService.getEpisodesByIds(user.id, episodeIds)

    override suspend fun getEpisodeWindow(episodeId: UUID, older: Int, newer: Int): List<PodcastEpisode> =
        podcastService.getEpisodeWindow(user.id, episodeId, older, newer)

    override suspend fun searchEpisodes(query: String, page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> =
        podcastService.searchEpisodes(user.id, query, page, pageSize)

    override suspend fun getLatestEpisodes(page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> =
        podcastService.getLatestEpisodes(user.id, page, pageSize)

    override suspend fun getInProgress(page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> =
        podcastService.getInProgress(user.id, page, pageSize)

    override suspend fun getLastPlayed(includeCompleted: Boolean): PodcastEpisode? =
        podcastService.getLastPlayed(user.id, includeCompleted)

    override suspend fun reportPlayback(report: EpisodePlaybackReport): PodcastEpisodeProgress =
        podcastService.reportPlayback(user.id, report)

    override suspend fun setPlayed(episodeId: UUID, played: Boolean): PodcastEpisodeProgress =
        podcastService.setPlayed(user.id, episodeId, played)

    override fun observeProgress(): Flow<PodcastEpisodeProgress> = podcastService.observeProgress(user.id)

    override suspend fun refreshShow(showId: UUID): PodcastShow {
        feedService.refreshShow(showId)
        return requireNotNull(podcastService.getShow(user.id, showId)) { "Podcast show $showId does not exist" }
    }

    override suspend fun updateShowSettings(showId: UUID, settings: PodcastShowSettings): PodcastShow =
        podcastService.updateShowSettings(showId, settings, user.id)

    override suspend fun importEpisode(episodeId: UUID): PodcastEpisode {
        importService.importEpisode(episodeId)
        return requireNotNull(podcastService.getEpisode(user.id, episodeId)) { "Podcast episode $episodeId does not exist" }
    }

    override suspend fun removeImport(episodeId: UUID): PodcastEpisode {
        importService.removeImport(episodeId)
        return requireNotNull(podcastService.getEpisode(user.id, episodeId)) { "Podcast episode $episodeId does not exist" }
    }

    override suspend fun deleteShow(showId: UUID): Boolean = maintenanceService.deleteShow(showId)

    override suspend fun scanLocal(): PodcastScanResult = localScanService.scan()

    override fun streamEpisode(episodeId: UUID, offset: Long, chunkSize: Int): Flow<ByteArray>? =
        streamService.streamEpisode(episodeId, offset, chunkSize)

    override suspend fun getStreamSize(episodeId: UUID): Long = streamService.getStreamSize(episodeId)

    override suspend fun getTranscripts(episodeId: UUID): List<PodcastTranscript> =
        podcastService.getTranscripts(user.id, episodeId)

    override suspend fun getTranscript(transcriptId: UUID): PodcastTranscriptContent? =
        podcastService.getTranscript(user.id, transcriptId)
}
