package dev.dertyp.services.podcast

import dev.dertyp.core.paging
import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastShow
import dev.dertyp.data.PodcastShowSettings
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.PodcastTranscript
import dev.dertyp.data.PodcastTranscriptContent
import dev.dertyp.db.PodcastEpisodeProgressTable
import dev.dertyp.db.PodcastEpisodeTable
import dev.dertyp.db.PodcastShowTable
import dev.dertyp.db.PodcastSubscriptionTable
import dev.dertyp.db.PodcastTranscriptTable
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val LIKE_ESCAPE = '\\'

private fun escapeLikeTerm(value: String): String = buildString {
    value.forEach { character ->
        if (character == LIKE_ESCAPE || character == '%' || character == '_') append(LIKE_ESCAPE)
        append(character)
    }
}

private infix fun <T : String?> Expression<T>.containsTerm(term: String): Op<Boolean> =
    lowerCase() like LikePattern("%${escapeLikeTerm(term.lowercase())}%", LIKE_ESCAPE)

class PodcastService(private val http: PodcastHttp) : Service() {
    companion object {
        const val MAX_PAGE = 500
        const val MAX_DEVICE_ID = 64
        const val MAX_DESCRIPTION = 20_000
        const val COMPLETE_TAIL_MS = 30_000L
        const val COMPLETE_TAIL_FRACTION = 0.05
        const val MAX_TRANSCRIPT_BYTES = 5 * 1024 * 1024

        private const val EPISODE_BATCH = 500
        private const val LOOKUP_CHUNK = 1000
        private const val READ_CHUNK = 8192
    }

    private data class StoredProgress(
        val positionMs: Long,
        val durationMs: Long?,
        val deviceId: String?
    )

    private val showLocks = ConcurrentHashMap<String, Mutex>()
    private val progressChanges = ConcurrentHashMap<UUID, MutableSharedFlow<PodcastEpisodeProgress>>()

    suspend fun subscribeToShow(userId: UUID, showId: UUID): PodcastShow {
        dbQuery {
            val show = showRow(showId)
            requireNotNull(show) { "Podcast show $showId does not exist" }

            val now = Instant.now().toEpochMilli()
            PodcastSubscriptionTable.insertIgnore {
                it[PodcastSubscriptionTable.userId] = userId
                it[PodcastSubscriptionTable.showId] = showId
                it[createdAt] = now
            }

            if (show.orphanedAt != null) {
                PodcastShowTable.update({ PodcastShowTable.id eq showId }) {
                    it[orphanedAt] = null
                    it[updatedAt] = now
                }
            }
        }

        return requireNotNull(getShow(userId, showId)) { "Podcast show $showId does not exist" }
    }

    suspend fun unsubscribe(userId: UUID, showId: UUID): Boolean = dbQuery {
        val removed = PodcastSubscriptionTable.deleteWhere {
            (PodcastSubscriptionTable.userId eq userId) and (PodcastSubscriptionTable.showId eq showId)
        } > 0

        if (removed) {
            val show = showRow(showId)
            if (show != null && show.source == PodcastSource.FEED && countSubscribers(showId) == 0L) {
                val now = Instant.now().toEpochMilli()
                PodcastShowTable.update({ PodcastShowTable.id eq showId }) {
                    it[orphanedAt] = now
                    it[updatedAt] = now
                }
            }
        }

        removed
    }

    suspend fun getSubscriptions(userId: UUID): List<PodcastShow> = dbQuery {
        val rows = showSource(userId)
            .selectAll()
            .where { PodcastSubscriptionTable.userId eq userId }
            .orderBy(PodcastShowTable.title.lowerCase() to SortOrder.ASC, PodcastShowTable.id to SortOrder.ASC)
            .toList()

        showsOf(rows)
    }

    suspend fun browseShows(userId: UUID, query: String, page: Int, pageSize: Int): PaginatedResponse<PodcastShow> {
        val size = pageSize.coerceIn(1, MAX_PAGE)
        return dbQuery {
            val total = showQuery(userId, query).count().toInt()
            val rows = showQuery(userId, query)
                .orderBy(PodcastShowTable.title.lowerCase() to SortOrder.ASC, PodcastShowTable.id to SortOrder.ASC)
                .paging(page, size)
                .toList()

            PaginatedResponse(
                data = showsOf(rows),
                page = page,
                total = total,
                pageSize = size,
                hasNextPage = (page + 1).toLong() * size < total
            )
        }
    }

    suspend fun getShow(userId: UUID, showId: UUID): PodcastShow? = dbQuery {
        val row = showSource(userId)
            .selectAll()
            .where { PodcastShowTable.id eq showId }
            .singleOrNull()

        if (row == null) null else showsOf(listOf(row)).firstOrNull()
    }

    suspend fun getEpisodes(
        userId: UUID,
        showId: UUID,
        page: Int,
        pageSize: Int,
        newestFirst: Boolean
    ): PaginatedResponse<PodcastEpisode> {
        val size = pageSize.coerceIn(1, MAX_PAGE)
        return dbQuery {
            val total = showEpisodes(userId, showId).count().toInt()
            val rows = showEpisodes(userId, showId)
                .orderBy(
                    PodcastEpisodeTable.publishedAt to if (newestFirst) SortOrder.DESC else SortOrder.ASC,
                    PodcastEpisodeTable.id to SortOrder.ASC
                )
                .paging(page, size)
                .toList()

            PaginatedResponse(
                data = episodesOf(rows),
                page = page,
                total = total,
                pageSize = size,
                hasNextPage = (page + 1).toLong() * size < total
            )
        }
    }

    suspend fun getEpisode(userId: UUID, episodeId: UUID): PodcastEpisode? = dbQuery {
        val row = episodeSource(userId)
            .selectAll()
            .where { PodcastEpisodeTable.id eq episodeId }
            .singleOrNull()

        if (row == null) null else episodesOf(listOf(row)).firstOrNull()
    }

    suspend fun getEpisodesByIds(userId: UUID, episodeIds: List<UUID>): List<PodcastEpisode> {
        require(episodeIds.size <= MAX_PAGE) { "At most $MAX_PAGE episodes can be read at once" }
        if (episodeIds.isEmpty()) return emptyList()

        return dbQuery {
            val rows = episodeIds.distinct().chunked(LOOKUP_CHUNK).flatMap { chunk ->
                episodeSource(userId)
                    .selectAll()
                    .where { PodcastEpisodeTable.id inList chunk }
                    .toList()
            }
            val byId = episodesOf(rows).associateBy { it.id }

            episodeIds.mapNotNull { byId[it] }
        }
    }

    suspend fun getEpisodeWindow(userId: UUID, episodeId: UUID, older: Int, newer: Int): List<PodcastEpisode> = dbQuery {
        val anchor = episodeRow(episodeId) ?: return@dbQuery emptyList()
        val olderCount = older.coerceIn(0, MAX_PAGE)
        val newerCount = newer.coerceIn(0, MAX_PAGE)

        val olderRows = if (olderCount == 0) {
            emptyList()
        } else {
            episodeSource(userId)
                .selectAll()
                .where { PodcastEpisodeTable.showId eq anchor.showId }
                .andWhere {
                    (PodcastEpisodeTable.publishedAt less anchor.publishedAt) or
                        ((PodcastEpisodeTable.publishedAt eq anchor.publishedAt) and (PodcastEpisodeTable.id less anchor.id))
                }
                .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.DESC)
                .limit(olderCount)
                .toList()
        }

        val newerRows = if (newerCount == 0) {
            emptyList()
        } else {
            episodeSource(userId)
                .selectAll()
                .where { PodcastEpisodeTable.showId eq anchor.showId }
                .andWhere {
                    (PodcastEpisodeTable.publishedAt greater anchor.publishedAt) or
                        ((PodcastEpisodeTable.publishedAt eq anchor.publishedAt) and (PodcastEpisodeTable.id greater anchor.id))
                }
                .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.ASC, PodcastEpisodeTable.id to SortOrder.ASC)
                .limit(newerCount)
                .toList()
        }

        val anchorRow = episodeSource(userId)
            .selectAll()
            .where { PodcastEpisodeTable.id eq episodeId }
            .single()

        episodesOf(olderRows.reversed()) + episodesOf(listOf(anchorRow)) + episodesOf(newerRows)
    }

    suspend fun getLastPlayed(userId: UUID, includeCompleted: Boolean): PodcastEpisode? = dbQuery {
        val query = if (includeCompleted) {
            episodeSource(userId)
                .selectAll()
                .where { PodcastEpisodeProgressTable.userId eq userId }
        } else {
            startedEpisodes(userId)
        }
        val rows = query
            .orderBy(
                PodcastEpisodeProgressTable.lastPlayedAt to SortOrder.DESC,
                PodcastEpisodeTable.id to SortOrder.ASC
            )
            .limit(1)
            .toList()

        episodesOf(rows).firstOrNull()
    }

    suspend fun searchEpisodes(userId: UUID, query: String, page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> {
        require(query.isNotBlank()) { "A podcast search needs a search term" }

        val size = pageSize.coerceIn(1, MAX_PAGE)
        val term = query.trim()
        return dbQuery {
            val total = episodeSearch(userId, term).count().toInt()
            val rows = episodeSearch(userId, term)
                .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
                .paging(page, size)
                .toList()

            PaginatedResponse(
                data = episodesOf(rows),
                page = page,
                total = total,
                pageSize = size,
                hasNextPage = (page + 1).toLong() * size < total
            )
        }
    }

    suspend fun getLatestEpisodes(userId: UUID, page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> {
        val size = pageSize.coerceIn(1, MAX_PAGE)
        return dbQuery {
            val total = subscribedEpisodes(userId).count().toInt()
            val rows = subscribedEpisodes(userId)
                .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
                .paging(page, size)
                .toList()

            PaginatedResponse(
                data = episodesOf(rows),
                page = page,
                total = total,
                pageSize = size,
                hasNextPage = (page + 1).toLong() * size < total
            )
        }
    }

    suspend fun getInProgress(userId: UUID, page: Int, pageSize: Int): PaginatedResponse<PodcastEpisode> {
        val size = pageSize.coerceIn(1, MAX_PAGE)
        return dbQuery {
            val total = startedEpisodes(userId).count().toInt()
            val rows = startedEpisodes(userId)
                .orderBy(
                    PodcastEpisodeProgressTable.lastPlayedAt to SortOrder.DESC,
                    PodcastEpisodeTable.id to SortOrder.ASC
                )
                .paging(page, size)
                .toList()

            PaginatedResponse(
                data = episodesOf(rows),
                page = page,
                total = total,
                pageSize = size,
                hasNextPage = (page + 1).toLong() * size < total
            )
        }
    }

    suspend fun reportPlayback(userId: UUID, report: EpisodePlaybackReport): PodcastEpisodeProgress {
        val reported = report.durationMs
        require(report.positionMs >= 0) { "A listening position must not be negative" }
        require(reported == null || reported > 0) { "An episode length must be positive" }
        require((report.deviceId?.length ?: 0) <= MAX_DEVICE_ID) { "A device id is at most $MAX_DEVICE_ID characters long" }

        val progress = dbQuery {
            val episode = episodeRow(report.episodeId)
            requireNotNull(episode) { "Podcast episode ${report.episodeId} does not exist" }

            val duration = reported ?: episode.durationMs
            val position = if (duration != null) report.positionMs.coerceAtMost(duration) else report.positionMs
            val completed = report.completed || (duration != null && duration - position <= completionTail(duration))
            val now = Instant.now().toEpochMilli()

            writeProgress(userId, episode.id, position, duration, completed, report.deviceId, now)

            PodcastEpisodeProgress(
                episodeId = episode.id,
                showId = episode.showId,
                positionMs = position,
                durationMs = duration,
                completed = completed,
                lastPlayedAt = now,
                updatedAt = now,
                deviceId = report.deviceId
            )
        }

        changeFlow(userId).emit(progress)
        return progress
    }

    suspend fun setPlayed(userId: UUID, episodeId: UUID, played: Boolean): PodcastEpisodeProgress {
        val progress = dbQuery {
            val episode = episodeRow(episodeId)
            requireNotNull(episode) { "Podcast episode $episodeId does not exist" }

            val current = progressRow(userId, episodeId)
            val duration = episode.durationMs ?: current?.durationMs
            val position = if (played) duration ?: current?.positionMs ?: 0L else 0L
            val deviceId = current?.deviceId
            val now = Instant.now().toEpochMilli()

            writeProgress(userId, episodeId, position, duration, played, deviceId, now)

            PodcastEpisodeProgress(
                episodeId = episodeId,
                showId = episode.showId,
                positionMs = position,
                durationMs = duration,
                completed = played,
                lastPlayedAt = now,
                updatedAt = now,
                deviceId = deviceId
            )
        }

        changeFlow(userId).emit(progress)
        return progress
    }

    fun observeProgress(userId: UUID): Flow<PodcastEpisodeProgress> = changeFlow(userId).asSharedFlow()

    suspend fun updateShowSettings(showId: UUID, settings: PodcastShowSettings, userId: UUID): PodcastShow {
        val keep = settings.keepEpisodes
        require(keep == null || keep >= 1) { "A show keeps at least one episode on disk" }
        require(settings.retention == PodcastRetention.NEWEST || settings.deliveryMode == PodcastDeliveryMode.IMPORT) {
            "Unlistened retention needs import delivery"
        }

        dbQuery {
            val updated = PodcastShowTable.update({ PodcastShowTable.id eq showId }) {
                it[deliveryMode] = settings.deliveryMode
                it[keepEpisodes] = keep
                it[retention] = settings.retention
                it[updatedAt] = Instant.now().toEpochMilli()
            }
            require(updated == 1) { "Podcast show $showId does not exist" }
        }

        return requireNotNull(getShow(userId, showId)) { "Podcast show $showId does not exist" }
    }

    suspend fun getTranscripts(userId: UUID, episodeId: UUID): List<PodcastTranscript> = dbQuery {
        transcriptRows(episodeId).map(::mapTranscript)
    }

    suspend fun getTranscript(userId: UUID, transcriptId: UUID): PodcastTranscriptContent? {
        val row = dbQuery {
            PodcastTranscriptTable
                .selectAll()
                .where { PodcastTranscriptTable.id eq transcriptId }
                .singleOrNull()
                ?.let(::mapTranscriptRow)
        } ?: return null

        row.content?.let { return PodcastTranscriptContent(mapTranscript(row), it) }

        val sidecar = row.filePath?.let(::File)
        if (sidecar != null) {
            if (!sidecar.exists()) return null
            val text = runCatching { sidecar.readText() }.getOrNull() ?: return null
            return PodcastTranscriptContent(mapTranscript(row), text)
        }

        val url = row.url ?: return null
        val fetched = runCatching { downloadTranscript(url) }
        val now = Instant.now().toEpochMilli()
        val content = fetched.getOrNull()

        dbQuery {
            PodcastTranscriptTable.update({ PodcastTranscriptTable.id eq transcriptId }) {
                if (content != null) {
                    it[PodcastTranscriptTable.content] = content
                    it[fetchedAt] = now
                    it[fetchError] = null
                } else {
                    it[fetchError] = fetched.exceptionOrNull()?.message ?: "The transcript could not be fetched"
                }
            }
        }

        if (content == null) return null
        return PodcastTranscriptContent(mapTranscript(row.copy(content = content, fetchedAt = now)), content)
    }

    suspend fun findShowBySourceKey(sourceKey: String): PodcastShowRow? = dbQuery {
        PodcastShowTable
            .selectAll()
            .where { PodcastShowTable.sourceKey eq sourceKey }
            .singleOrNull()
            ?.let(::mapShowRow)
    }

    suspend fun knownSourceKeys(sourceKeys: Collection<String>): Set<String> {
        if (sourceKeys.isEmpty()) return emptySet()
        return dbQuery {
            PodcastShowTable
                .select(PodcastShowTable.sourceKey)
                .where { PodcastShowTable.sourceKey inList sourceKeys }
                .map { it[PodcastShowTable.sourceKey] }
                .toSet()
        }
    }

    suspend fun showById(showId: UUID): PodcastShowRow? = dbQuery { showRow(showId) }

    suspend fun feedShowsWithSubscribers(): List<PodcastShowRow> = dbQuery {
        val subscribed = PodcastSubscriptionTable
            .select(PodcastSubscriptionTable.showId)
            .withDistinct()
            .map { it[PodcastSubscriptionTable.showId].value }

        if (subscribed.isEmpty()) {
            emptyList()
        } else {
            subscribed.chunked(LOOKUP_CHUNK).flatMap { chunk ->
                PodcastShowTable
                    .selectAll()
                    .where { PodcastShowTable.showSource eq PodcastSource.FEED }
                    .andWhere { PodcastShowTable.orphanedAt.isNull() }
                    .andWhere { PodcastShowTable.id inList chunk }
                    .map(::mapShowRow)
            }
        }
    }

    suspend fun localShows(): List<PodcastShowRow> = dbQuery {
        PodcastShowTable
            .selectAll()
            .where { PodcastShowTable.showSource eq PodcastSource.LOCAL }
            .map(::mapShowRow)
    }

    suspend fun createFeedShow(
        feedUrl: String,
        sourceKey: String,
        parsed: ParsedShow,
        imageId: UUID?,
        imageUrl: String?
    ): UUID = showLock(sourceKey).withLock {
        dbQuery {
            val existing = PodcastShowTable
                .select(PodcastShowTable.id)
                .where { PodcastShowTable.sourceKey eq sourceKey }
                .singleOrNull()

            if (existing != null) {
                return@dbQuery existing[PodcastShowTable.id].value
            }

            val now = Instant.now().toEpochMilli()
            PodcastShowTable.insertAndGetId {
                it[showSource] = PodcastSource.FEED
                it[PodcastShowTable.sourceKey] = sourceKey
                it[PodcastShowTable.feedUrl] = feedUrl
                it[title] = parsed.title
                it[description] = truncate(parsed.description)
                it[author] = parsed.author
                it[language] = parsed.language
                it[link] = parsed.link
                it[PodcastShowTable.imageId] = imageId
                it[PodcastShowTable.imageUrl] = imageUrl ?: parsed.imageUrl
                it[explicit] = parsed.explicit
                it[createdAt] = now
                it[updatedAt] = now
            }.value
        }
    }

    suspend fun updateShowFromFeed(
        showId: UUID,
        parsed: ParsedShow,
        imageId: UUID?,
        imageUrl: String?,
        newFeedUrl: String?
    ) {
        dbQuery {
            PodcastShowTable.update({ PodcastShowTable.id eq showId }) {
                it[title] = parsed.title
                it[description] = truncate(parsed.description)
                it[author] = parsed.author
                it[language] = parsed.language
                it[link] = parsed.link
                it[explicit] = parsed.explicit
                if (imageId != null) it[PodcastShowTable.imageId] = imageId
                if (imageUrl != null) it[PodcastShowTable.imageUrl] = imageUrl
                if (newFeedUrl != null) {
                    it[feedUrl] = newFeedUrl
                    it[sourceKey] = PodcastKeys.feedSourceKey(newFeedUrl)
                }
                it[updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun recordFetchResult(showId: UUID, etag: String?, lastModified: String?, error: String?) {
        dbQuery {
            val now = Instant.now().toEpochMilli()
            PodcastShowTable.update({ PodcastShowTable.id eq showId }) {
                it[lastFetchedAt] = now
                it[lastFetchError] = error
                if (error == null) {
                    it[PodcastShowTable.etag] = etag
                    it[PodcastShowTable.lastModified] = lastModified
                }
                it[updatedAt] = now
            }
        }
    }

    suspend fun upsertEpisodes(showId: UUID, episodes: List<ParsedEpisode>): Pair<Int, Int> {
        if (episodes.isEmpty()) return 0 to 0

        val unique = episodes.distinctBy { PodcastKeys.guidKey(it.guid) }
        var inserted = 0
        var updated = 0
        val transcriptWork = mutableListOf<Pair<UUID, List<ParsedTranscript>>>()

        unique.chunked(EPISODE_BATCH).forEach { chunk ->
            dbQuery {
                val keys = chunk.map { PodcastKeys.guidKey(it.guid) }
                val existing = PodcastEpisodeTable
                    .selectAll()
                    .where { PodcastEpisodeTable.showId eq showId }
                    .andWhere { PodcastEpisodeTable.guidKey inList keys }
                    .associate { it[PodcastEpisodeTable.guidKey] to mapEpisodeRow(it) }

                val now = Instant.now().toEpochMilli()
                chunk.forEach { parsed ->
                    val key = PodcastKeys.guidKey(parsed.guid)
                    val current = existing[key]

                    if (current == null) {
                        val id = PodcastEpisodeTable.insertAndGetId {
                            it[PodcastEpisodeTable.showId] = showId
                            it[guid] = parsed.guid
                            it[guidKey] = key
                            it[title] = parsed.title
                            it[description] = truncate(parsed.description)
                            it[link] = parsed.link
                            it[publishedAt] = parsed.publishedAt
                            it[durationMs] = parsed.durationMs
                            it[enclosureUrl] = parsed.enclosureUrl
                            it[enclosureType] = parsed.enclosureType
                            it[enclosureLength] = parsed.enclosureLength
                            it[seasonNumber] = parsed.seasonNumber
                            it[episodeNumber] = parsed.episodeNumber
                            it[episodeType] = parsed.episodeType
                            it[explicit] = parsed.explicit
                            it[createdAt] = now
                            it[updatedAt] = now
                        }.value

                        inserted++
                        transcriptWork += id to parsed.transcripts
                    } else {
                        PodcastEpisodeTable.update({ PodcastEpisodeTable.id eq current.id }) {
                            it[title] = parsed.title
                            it[description] = truncate(parsed.description)
                            it[link] = parsed.link
                            it[publishedAt] = parsed.publishedAt
                            it[durationMs] = parsed.durationMs ?: current.durationMs
                            it[enclosureUrl] = parsed.enclosureUrl
                            it[enclosureType] = parsed.enclosureType
                            it[enclosureLength] = parsed.enclosureLength
                            it[seasonNumber] = parsed.seasonNumber
                            it[episodeNumber] = parsed.episodeNumber
                            it[episodeType] = parsed.episodeType
                            it[explicit] = parsed.explicit
                            it[updatedAt] = now
                        }

                        updated++
                        transcriptWork += current.id to parsed.transcripts
                    }
                }
            }
        }

        transcriptWork.forEach { (episodeId, entries) -> syncTranscripts(episodeId, entries) }

        return inserted to updated
    }

    suspend fun upsertLocalShow(localPath: String, title: String, imageId: UUID?): UUID {
        val sourceKey = PodcastKeys.localSourceKey(localPath)
        return showLock(sourceKey).withLock {
            dbQuery {
                val existing = PodcastShowTable
                    .select(PodcastShowTable.id)
                    .where { PodcastShowTable.sourceKey eq sourceKey }
                    .singleOrNull()

                val now = Instant.now().toEpochMilli()
                if (existing != null) {
                    val id = existing[PodcastShowTable.id].value
                    PodcastShowTable.update({ PodcastShowTable.id eq id }) {
                        it[PodcastShowTable.localPath] = localPath
                        if (imageId != null) it[PodcastShowTable.imageId] = imageId
                        it[updatedAt] = now
                    }
                    return@dbQuery id
                }

                PodcastShowTable.insertAndGetId {
                    it[showSource] = PodcastSource.LOCAL
                    it[PodcastShowTable.sourceKey] = sourceKey
                    it[PodcastShowTable.localPath] = localPath
                    it[PodcastShowTable.title] = title
                    it[PodcastShowTable.imageId] = imageId
                    it[createdAt] = now
                    it[updatedAt] = now
                }.value
            }
        }
    }

    suspend fun upsertLocalEpisodes(showId: UUID, episodes: List<LocalEpisode>): Pair<Int, Int> {
        if (episodes.isEmpty()) return 0 to 0

        val unique = episodes.distinctBy { PodcastKeys.guidKey(it.guid) }
        var inserted = 0
        var updated = 0
        val transcriptWork = mutableListOf<Pair<UUID, List<LocalTranscript>>>()

        unique.chunked(EPISODE_BATCH).forEach { chunk ->
            dbQuery {
                val keys = chunk.map { PodcastKeys.guidKey(it.guid) }
                val existing = PodcastEpisodeTable
                    .selectAll()
                    .where { PodcastEpisodeTable.showId eq showId }
                    .andWhere { PodcastEpisodeTable.guidKey inList keys }
                    .associate { it[PodcastEpisodeTable.guidKey] to mapEpisodeRow(it) }

                val now = Instant.now().toEpochMilli()
                chunk.forEach { local ->
                    val key = PodcastKeys.guidKey(local.guid)
                    val current = existing[key]

                    if (current == null) {
                        val id = PodcastEpisodeTable.insertAndGetId {
                            it[PodcastEpisodeTable.showId] = showId
                            it[guid] = local.guid
                            it[guidKey] = key
                            it[title] = local.title
                            it[description] = truncate(local.description)
                            it[publishedAt] = local.publishedAt
                            it[durationMs] = local.durationMs
                            it[filePath] = local.filePath
                            it[fileSize] = local.fileSize
                            it[format] = local.format
                            it[imageId] = local.imageId
                            it[episodeNumber] = local.episodeNumber
                            it[importState] = PodcastImportState.IMPORTED
                            it[importError] = null
                            it[importedAt] = now
                            it[createdAt] = now
                            it[updatedAt] = now
                        }.value

                        inserted++
                        transcriptWork += id to local.transcripts
                    } else {
                        PodcastEpisodeTable.update({ PodcastEpisodeTable.id eq current.id }) {
                            it[title] = local.title
                            it[description] = truncate(local.description)
                            it[publishedAt] = local.publishedAt
                            it[durationMs] = local.durationMs ?: current.durationMs
                            it[filePath] = local.filePath
                            it[fileSize] = local.fileSize
                            it[format] = local.format
                            if (local.imageId != null) it[imageId] = local.imageId
                            it[episodeNumber] = local.episodeNumber
                            it[importState] = PodcastImportState.IMPORTED
                            it[importError] = null
                            it[importedAt] = current.importedAt ?: now
                            it[updatedAt] = now
                        }

                        updated++
                        transcriptWork += current.id to local.transcripts
                    }
                }
            }
        }

        transcriptWork.forEach { (episodeId, entries) -> syncLocalTranscripts(episodeId, entries) }

        return inserted to updated
    }

    suspend fun episodesOfShow(showId: UUID): List<PodcastEpisodeRow> = dbQuery {
        PodcastEpisodeTable
            .selectAll()
            .where { PodcastEpisodeTable.showId eq showId }
            .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
            .map(::mapEpisodeRow)
    }

    suspend fun episodeById(episodeId: UUID): PodcastEpisodeRow? = dbQuery { episodeRow(episodeId) }

    suspend fun deleteEpisodes(ids: Collection<UUID>): Int {
        if (ids.isEmpty()) return 0
        return dbQuery {
            val distinctIds = ids.distinct()
            distinctIds.chunked(LOOKUP_CHUNK).forEach { chunk ->
                PodcastTranscriptTable.deleteWhere { PodcastTranscriptTable.episodeId inList chunk }
                PodcastEpisodeProgressTable.deleteWhere { PodcastEpisodeProgressTable.episodeId inList chunk }
            }
            distinctIds.chunked(LOOKUP_CHUNK).sumOf { chunk ->
                PodcastEpisodeTable.deleteWhere { PodcastEpisodeTable.id inList chunk }
            }
        }
    }

    suspend fun deleteShow(showId: UUID) {
        dbQuery {
            val episodeIds = PodcastEpisodeTable
                .select(PodcastEpisodeTable.id)
                .where { PodcastEpisodeTable.showId eq showId }
                .map { it[PodcastEpisodeTable.id].value }

            episodeIds.chunked(LOOKUP_CHUNK).forEach { chunk ->
                PodcastTranscriptTable.deleteWhere { PodcastTranscriptTable.episodeId inList chunk }
                PodcastEpisodeProgressTable.deleteWhere { PodcastEpisodeProgressTable.episodeId inList chunk }
            }

            PodcastSubscriptionTable.deleteWhere { PodcastSubscriptionTable.showId eq showId }
            PodcastEpisodeTable.deleteWhere { PodcastEpisodeTable.showId eq showId }
            PodcastShowTable.deleteWhere { PodcastShowTable.id eq showId }
        }
    }

    suspend fun orphanedFeedShows(olderThanMs: Long): List<PodcastShowRow> = dbQuery {
        val cutoff = Instant.now().toEpochMilli() - olderThanMs
        PodcastShowTable
            .selectAll()
            .where { PodcastShowTable.showSource eq PodcastSource.FEED }
            .andWhere { PodcastShowTable.orphanedAt less cutoff }
            .map(::mapShowRow)
    }

    suspend fun importCandidates(showId: UUID, limit: Int, maxAttempts: Int): List<PodcastEpisodeRow> = dbQuery {
        PodcastEpisodeTable
            .selectAll()
            .where { PodcastEpisodeTable.showId eq showId }
            .andWhere { PodcastEpisodeTable.enclosureUrl.isNotNull() }
            .andWhere { PodcastEpisodeTable.importState inList listOf(PodcastImportState.NONE, PodcastImportState.FAILED) }
            .andWhere { PodcastEpisodeTable.importAttempts less maxAttempts }
            .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
            .limit(limit)
            .map(::mapEpisodeRow)
    }

    suspend fun episodesInState(state: PodcastImportState, limit: Int? = null): List<PodcastEpisodeRow> = dbQuery {
        val query = PodcastEpisodeTable
            .selectAll()
            .where { PodcastEpisodeTable.importState eq state }
            .orderBy(PodcastEpisodeTable.updatedAt to SortOrder.ASC, PodcastEpisodeTable.id to SortOrder.ASC)

        if (limit != null) query.limit(limit)

        query.map(::mapEpisodeRow)
    }

    suspend fun importedBeyond(showId: UUID, keep: Int): List<PodcastEpisodeRow> = dbQuery {
        PodcastEpisodeTable
            .selectAll()
            .where { PodcastEpisodeTable.showId eq showId }
            .andWhere { PodcastEpisodeTable.importState eq PodcastImportState.IMPORTED }
            .andWhere { PodcastEpisodeTable.filePath.isNotNull() }
            .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
            .map(::mapEpisodeRow)
            .drop(max(keep, 0))
    }

    suspend fun unlistenedImportCandidates(showId: UUID, maxAttempts: Int): List<PodcastEpisodeRow> = dbQuery {
        val finished = finishedEpisodeIds(showId)

        PodcastEpisodeTable
            .selectAll()
            .where { PodcastEpisodeTable.showId eq showId }
            .andWhere { PodcastEpisodeTable.enclosureUrl.isNotNull() }
            .andWhere { PodcastEpisodeTable.importState inList listOf(PodcastImportState.NONE, PodcastImportState.FAILED) }
            .andWhere { PodcastEpisodeTable.importAttempts less maxAttempts }
            .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
            .map(::mapEpisodeRow)
            .filterNot { it.id in finished }
    }

    suspend fun finishedImportedEpisodes(showId: UUID): List<PodcastEpisodeRow> = dbQuery {
        val finished = finishedEpisodeIds(showId)

        if (finished.isEmpty()) {
            emptyList()
        } else {
            PodcastEpisodeTable
                .selectAll()
                .where { PodcastEpisodeTable.showId eq showId }
                .andWhere { PodcastEpisodeTable.importState eq PodcastImportState.IMPORTED }
                .andWhere { PodcastEpisodeTable.filePath.isNotNull() }
                .orderBy(PodcastEpisodeTable.publishedAt to SortOrder.DESC, PodcastEpisodeTable.id to SortOrder.ASC)
                .map(::mapEpisodeRow)
                .filter { it.id in finished }
        }
    }

    suspend fun importedOrPendingCount(showId: UUID): Int = dbQuery {
        val states = listOf(PodcastImportState.IMPORTED, PodcastImportState.QUEUED, PodcastImportState.IMPORTING)

        PodcastEpisodeTable
            .selectAll()
            .where { PodcastEpisodeTable.showId eq showId }
            .andWhere { PodcastEpisodeTable.importState inList states }
            .count()
            .toInt()
    }

    private fun finishedEpisodeIds(showId: UUID): Set<UUID> {
        val subscribers = PodcastSubscriptionTable
            .select(PodcastSubscriptionTable.userId)
            .where { PodcastSubscriptionTable.showId eq showId }
            .map { it[PodcastSubscriptionTable.userId].value }
            .toSet()

        if (subscribers.isEmpty()) return emptySet()

        return (PodcastEpisodeProgressTable innerJoin PodcastEpisodeTable)
            .select(PodcastEpisodeProgressTable.episodeId, PodcastEpisodeProgressTable.userId)
            .where { PodcastEpisodeTable.showId eq showId }
            .andWhere { PodcastEpisodeProgressTable.completed eq true }
            .map { it[PodcastEpisodeProgressTable.episodeId].value to it[PodcastEpisodeProgressTable.userId].value }
            .filter { (_, userId) -> userId in subscribers }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.toSet().size == subscribers.size }
            .keys
    }

    suspend fun markImportState(
        episodeId: UUID,
        state: PodcastImportState,
        error: String? = null,
        incrementAttempts: Boolean = false,
        resetAttempts: Boolean = false
    ) {
        dbQuery {
            val current = episodeRow(episodeId) ?: return@dbQuery
            val attempts = when {
                resetAttempts -> 0
                incrementAttempts -> current.importAttempts + 1
                else -> current.importAttempts
            }

            PodcastEpisodeTable.update({ PodcastEpisodeTable.id eq episodeId }) {
                it[importState] = state
                it[importError] = error
                it[importAttempts] = attempts
                it[updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun markImported(episodeId: UUID, filePath: String, fileSize: Long, format: String?, durationMs: Long?) {
        dbQuery {
            val now = Instant.now().toEpochMilli()
            PodcastEpisodeTable.update({ PodcastEpisodeTable.id eq episodeId }) {
                it[PodcastEpisodeTable.filePath] = filePath
                it[PodcastEpisodeTable.fileSize] = fileSize
                if (format != null) it[PodcastEpisodeTable.format] = format
                if (durationMs != null) it[PodcastEpisodeTable.durationMs] = durationMs
                it[importState] = PodcastImportState.IMPORTED
                it[importError] = null
                it[importedAt] = now
                it[updatedAt] = now
            }
        }
    }

    suspend fun clearImport(episodeId: UUID) {
        dbQuery {
            PodcastEpisodeTable.update({ PodcastEpisodeTable.id eq episodeId }) {
                it[importState] = PodcastImportState.NONE
                it[filePath] = null
                it[fileSize] = null
                it[format] = null
                it[importedAt] = null
                it[importError] = null
                it[updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun updateEnclosureLength(episodeId: UUID, length: Long) {
        dbQuery {
            PodcastEpisodeTable.update({ PodcastEpisodeTable.id eq episodeId }) {
                it[enclosureLength] = length
                it[updatedAt] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun addEmbeddedTranscript(episodeId: UUID, content: String, mimeType: String) {
        dbQuery {
            PodcastTranscriptTable.upsert(
                PodcastTranscriptTable.episodeId,
                PodcastTranscriptTable.sourceKey,
                onUpdateExclude = listOf(PodcastTranscriptTable.createdAt)
            ) {
                it[PodcastTranscriptTable.episodeId] = episodeId
                it[sourceKey] = PodcastKeys.EMBEDDED_TRANSCRIPT_KEY
                it[type] = mimeType
                it[PodcastTranscriptTable.content] = content
                it[createdAt] = Instant.now().toEpochMilli()
            }
        }
    }

    private suspend fun syncTranscripts(episodeId: UUID, entries: List<ParsedTranscript>) {
        dbQuery {
            val desired = entries
                .filter { it.url.isNotBlank() }
                .associateBy { PodcastKeys.sha256Hex(it.url) }

            val existing = transcriptRows(episodeId)
            val obsolete = existing
                .filter { it.sourceKey != PodcastKeys.EMBEDDED_TRANSCRIPT_KEY && it.filePath == null && it.sourceKey !in desired.keys }
                .map { it.id }

            if (obsolete.isNotEmpty()) {
                PodcastTranscriptTable.deleteWhere { PodcastTranscriptTable.id inList obsolete }
            }

            val known = existing.associateBy { it.sourceKey }
            val now = Instant.now().toEpochMilli()
            desired.forEach { (key, entry) ->
                val current = known[key]
                if (current == null) {
                    PodcastTranscriptTable.insertAndGetId {
                        it[PodcastTranscriptTable.episodeId] = episodeId
                        it[sourceKey] = key
                        it[url] = entry.url
                        it[type] = entry.type
                        it[language] = entry.language
                        it[rel] = entry.rel
                        it[createdAt] = now
                    }
                } else {
                    PodcastTranscriptTable.update({ PodcastTranscriptTable.id eq current.id }) {
                        it[url] = entry.url
                        it[type] = entry.type
                        it[language] = entry.language
                        it[rel] = entry.rel
                    }
                }
            }
        }
    }

    private suspend fun syncLocalTranscripts(episodeId: UUID, entries: List<LocalTranscript>) {
        dbQuery {
            val desired = entries.associateBy { entry ->
                when (entry) {
                    is LocalTranscript.Sidecar -> PodcastKeys.sha256Hex(entry.filePath)
                    is LocalTranscript.Embedded -> PodcastKeys.EMBEDDED_TRANSCRIPT_KEY
                }
            }

            val existing = transcriptRows(episodeId)
            val obsolete = existing
                .filter { it.url == null && it.sourceKey !in desired.keys }
                .map { it.id }

            if (obsolete.isNotEmpty()) {
                PodcastTranscriptTable.deleteWhere { PodcastTranscriptTable.id inList obsolete }
            }

            val known = existing.associateBy { it.sourceKey }
            val now = Instant.now().toEpochMilli()
            desired.forEach { (key, entry) ->
                val current = known[key]
                if (current == null) {
                    PodcastTranscriptTable.insertAndGetId {
                        it[PodcastTranscriptTable.episodeId] = episodeId
                        it[sourceKey] = key
                        it[filePath] = (entry as? LocalTranscript.Sidecar)?.filePath
                        it[type] = when (entry) {
                            is LocalTranscript.Sidecar -> entry.type
                            is LocalTranscript.Embedded -> entry.type
                        }
                        it[language] = (entry as? LocalTranscript.Sidecar)?.language
                        it[content] = (entry as? LocalTranscript.Embedded)?.content
                        it[createdAt] = now
                    }
                } else {
                    PodcastTranscriptTable.update({ PodcastTranscriptTable.id eq current.id }) {
                        when (entry) {
                            is LocalTranscript.Sidecar -> {
                                it[filePath] = entry.filePath
                                it[type] = entry.type
                                it[language] = entry.language
                            }

                            is LocalTranscript.Embedded -> {
                                it[type] = entry.type
                                it[content] = entry.content
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadTranscript(url: String): String {
        http.requirePublicHttpUrl(url)

        return http.feedClient.prepareGet(url).execute { response ->
            require(response.status.isSuccess()) { "The transcript could not be fetched: ${response.status}" }

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(READ_CHUNK)
            val collected = ByteArrayOutputStream()

            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                require(collected.size() + read <= MAX_TRANSCRIPT_BYTES) {
                    "The transcript is larger than $MAX_TRANSCRIPT_BYTES bytes"
                }
                collected.write(buffer, 0, read)
            }

            collected.toString(Charsets.UTF_8)
        }
    }

    private fun completionTail(duration: Long): Long =
        max(COMPLETE_TAIL_MS, (duration * COMPLETE_TAIL_FRACTION).toLong())

    private fun writeProgress(
        userId: UUID,
        episodeId: UUID,
        position: Long,
        duration: Long?,
        completed: Boolean,
        deviceId: String?,
        at: Long
    ) {
        PodcastEpisodeProgressTable.upsert(PodcastEpisodeProgressTable.userId, PodcastEpisodeProgressTable.episodeId) {
            it[PodcastEpisodeProgressTable.userId] = userId
            it[PodcastEpisodeProgressTable.episodeId] = episodeId
            it[positionMs] = position
            it[durationMs] = duration
            it[PodcastEpisodeProgressTable.completed] = completed
            it[lastPlayedAt] = at
            it[updatedAt] = at
            it[PodcastEpisodeProgressTable.deviceId] = deviceId
        }
    }

    private fun progressRow(userId: UUID, episodeId: UUID): StoredProgress? = PodcastEpisodeProgressTable
        .selectAll()
        .where { PodcastEpisodeProgressTable.userId eq userId }
        .andWhere { PodcastEpisodeProgressTable.episodeId eq episodeId }
        .singleOrNull()
        ?.let { row ->
            StoredProgress(
                positionMs = row[PodcastEpisodeProgressTable.positionMs],
                durationMs = row[PodcastEpisodeProgressTable.durationMs],
                deviceId = row[PodcastEpisodeProgressTable.deviceId]
            )
        }

    private fun changeFlow(userId: UUID): MutableSharedFlow<PodcastEpisodeProgress> =
        progressChanges.computeIfAbsent(userId) {
            MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    private fun showLock(sourceKey: String): Mutex = showLocks.computeIfAbsent(sourceKey) { Mutex() }

    private fun truncate(value: String?): String = (value ?: "").take(MAX_DESCRIPTION)

    private fun showSource(userId: UUID): ColumnSet = PodcastShowTable.leftJoin(
        PodcastSubscriptionTable,
        onColumn = { PodcastShowTable.id },
        otherColumn = { PodcastSubscriptionTable.showId },
        additionalConstraint = { PodcastSubscriptionTable.userId eq userId }
    )

    private fun showQuery(userId: UUID, query: String): Query {
        val shows = showSource(userId).selectAll()
        val term = query.trim()

        if (term.isNotBlank()) {
            shows.andWhere {
                (PodcastShowTable.title containsTerm term) or
                    (PodcastShowTable.author containsTerm term) or
                    (PodcastShowTable.description containsTerm term)
            }
        }

        return shows
    }

    private fun episodeSource(userId: UUID): ColumnSet = PodcastEpisodeTable
        .innerJoin(
            PodcastShowTable,
            onColumn = { PodcastEpisodeTable.showId },
            otherColumn = { PodcastShowTable.id }
        )
        .leftJoin(
            PodcastEpisodeProgressTable,
            onColumn = { PodcastEpisodeTable.id },
            otherColumn = { PodcastEpisodeProgressTable.episodeId },
            additionalConstraint = { PodcastEpisodeProgressTable.userId eq userId }
        )

    private fun showEpisodes(userId: UUID, showId: UUID): Query = episodeSource(userId)
        .selectAll()
        .where { PodcastEpisodeTable.showId eq showId }

    private fun episodeSearch(userId: UUID, term: String): Query = episodeSource(userId)
        .selectAll()
        .where {
            (PodcastEpisodeTable.title containsTerm term) or
                (PodcastEpisodeTable.description containsTerm term) or
                (PodcastShowTable.title containsTerm term)
        }

    private fun subscribedEpisodes(userId: UUID): Query = episodeSource(userId)
        .innerJoin(
            PodcastSubscriptionTable,
            onColumn = { PodcastShowTable.id },
            otherColumn = { PodcastSubscriptionTable.showId },
            additionalConstraint = { PodcastSubscriptionTable.userId eq userId }
        )
        .selectAll()

    private fun startedEpisodes(userId: UUID): Query = episodeSource(userId)
        .selectAll()
        .where { PodcastEpisodeProgressTable.userId eq userId }
        .andWhere { PodcastEpisodeProgressTable.completed eq false }
        .andWhere { PodcastEpisodeProgressTable.positionMs greater 0L }

    private fun showRow(showId: UUID): PodcastShowRow? = PodcastShowTable
        .selectAll()
        .where { PodcastShowTable.id eq showId }
        .singleOrNull()
        ?.let(::mapShowRow)

    private fun episodeRow(episodeId: UUID): PodcastEpisodeRow? = PodcastEpisodeTable
        .selectAll()
        .where { PodcastEpisodeTable.id eq episodeId }
        .singleOrNull()
        ?.let(::mapEpisodeRow)

    private fun transcriptRows(episodeId: UUID): List<PodcastTranscriptRow> = PodcastTranscriptTable
        .selectAll()
        .where { PodcastTranscriptTable.episodeId eq episodeId }
        .orderBy(PodcastTranscriptTable.createdAt to SortOrder.ASC, PodcastTranscriptTable.id to SortOrder.ASC)
        .map(::mapTranscriptRow)

    private fun countSubscribers(showId: UUID): Long = PodcastSubscriptionTable
        .selectAll()
        .where { PodcastSubscriptionTable.showId eq showId }
        .count()

    private fun episodeCounts(showIds: List<UUID>): Map<UUID, Int> {
        if (showIds.isEmpty()) return emptyMap()

        val counter = PodcastEpisodeTable.id.count()
        return showIds.chunked(LOOKUP_CHUNK).flatMap { chunk ->
            PodcastEpisodeTable
                .select(PodcastEpisodeTable.showId, counter)
                .where { PodcastEpisodeTable.showId inList chunk }
                .groupBy(PodcastEpisodeTable.showId)
                .map { it[PodcastEpisodeTable.showId].value to it[counter].toInt() }
        }.toMap()
    }

    private fun subscriberCounts(showIds: List<UUID>): Map<UUID, Int> {
        if (showIds.isEmpty()) return emptyMap()

        val counter = PodcastSubscriptionTable.userId.count()
        return showIds.chunked(LOOKUP_CHUNK).flatMap { chunk ->
            PodcastSubscriptionTable
                .select(PodcastSubscriptionTable.showId, counter)
                .where { PodcastSubscriptionTable.showId inList chunk }
                .groupBy(PodcastSubscriptionTable.showId)
                .map { it[PodcastSubscriptionTable.showId].value to it[counter].toInt() }
        }.toMap()
    }

    private fun transcriptedEpisodes(episodeIds: List<UUID>): Set<UUID> {
        if (episodeIds.isEmpty()) return emptySet()

        val counter = PodcastTranscriptTable.id.count()
        return episodeIds.chunked(LOOKUP_CHUNK).flatMap { chunk ->
            PodcastTranscriptTable
                .select(PodcastTranscriptTable.episodeId, counter)
                .where { PodcastTranscriptTable.episodeId inList chunk }
                .groupBy(PodcastTranscriptTable.episodeId)
                .filter { it[counter] > 0 }
                .map { it[PodcastTranscriptTable.episodeId].value }
        }.toSet()
    }

    private fun showsOf(rows: List<ResultRow>): List<PodcastShow> {
        val ids = rows.map { it[PodcastShowTable.id].value }
        val episodes = episodeCounts(ids)
        val subscribers = subscriberCounts(ids)

        return rows.map { row -> mapShow(row, episodes, subscribers) }
    }

    private fun episodesOf(rows: List<ResultRow>): List<PodcastEpisode> {
        val ids = rows.map { it[PodcastEpisodeTable.id].value }
        val transcripts = transcriptedEpisodes(ids)

        return rows.map { row -> mapEpisode(row, transcripts) }
    }

    private fun mapShow(row: ResultRow, episodes: Map<UUID, Int>, subscribers: Map<UUID, Int>): PodcastShow {
        val id = row[PodcastShowTable.id].value

        return PodcastShow(
            id = id,
            source = row[PodcastShowTable.showSource],
            feedUrl = row[PodcastShowTable.feedUrl],
            localPath = row[PodcastShowTable.localPath],
            title = row[PodcastShowTable.title],
            description = row[PodcastShowTable.description],
            author = row[PodcastShowTable.author],
            language = row[PodcastShowTable.language],
            link = row[PodcastShowTable.link],
            imageId = row[PodcastShowTable.imageId]?.value,
            explicit = row[PodcastShowTable.explicit],
            deliveryMode = row[PodcastShowTable.deliveryMode],
            keepEpisodes = row[PodcastShowTable.keepEpisodes],
            retention = row[PodcastShowTable.retention],
            lastFetchedAt = row[PodcastShowTable.lastFetchedAt],
            lastFetchError = row[PodcastShowTable.lastFetchError],
            episodeCount = episodes[id] ?: 0,
            subscriberCount = subscribers[id] ?: 0,
            subscribed = row.getOrNull(PodcastSubscriptionTable.userId) != null,
            createdAt = row[PodcastShowTable.createdAt],
            updatedAt = row[PodcastShowTable.updatedAt]
        )
    }

    private fun mapEpisode(row: ResultRow, transcripts: Set<UUID>): PodcastEpisode {
        val id = row[PodcastEpisodeTable.id].value
        val showId = row[PodcastEpisodeTable.showId].value
        val importState = row[PodcastEpisodeTable.importState]
        val filePath = row[PodcastEpisodeTable.filePath]
        val progress = if (row.getOrNull(PodcastEpisodeProgressTable.userId) == null) {
            null
        } else {
            PodcastEpisodeProgress(
                episodeId = id,
                showId = showId,
                positionMs = row[PodcastEpisodeProgressTable.positionMs],
                durationMs = row[PodcastEpisodeProgressTable.durationMs],
                completed = row[PodcastEpisodeProgressTable.completed],
                lastPlayedAt = row[PodcastEpisodeProgressTable.lastPlayedAt],
                updatedAt = row[PodcastEpisodeProgressTable.updatedAt],
                deviceId = row[PodcastEpisodeProgressTable.deviceId]
            )
        }

        return PodcastEpisode(
            id = id,
            showId = showId,
            showTitle = row[PodcastShowTable.title],
            guid = row[PodcastEpisodeTable.guid],
            title = row[PodcastEpisodeTable.title],
            description = row[PodcastEpisodeTable.description],
            link = row[PodcastEpisodeTable.link],
            publishedAt = row[PodcastEpisodeTable.publishedAt],
            durationMs = row[PodcastEpisodeTable.durationMs],
            enclosureUrl = row[PodcastEpisodeTable.enclosureUrl],
            enclosureType = row[PodcastEpisodeTable.enclosureType],
            enclosureLength = row[PodcastEpisodeTable.enclosureLength],
            imageId = row[PodcastEpisodeTable.imageId]?.value,
            showImageId = row[PodcastShowTable.imageId]?.value,
            seasonNumber = row[PodcastEpisodeTable.seasonNumber],
            episodeNumber = row[PodcastEpisodeTable.episodeNumber],
            episodeType = row[PodcastEpisodeTable.episodeType],
            explicit = row[PodcastEpisodeTable.explicit],
            importState = importState,
            imported = importState == PodcastImportState.IMPORTED && filePath != null,
            importedAt = row[PodcastEpisodeTable.importedAt],
            fileSize = row[PodcastEpisodeTable.fileSize],
            format = row[PodcastEpisodeTable.format],
            hasTranscript = id in transcripts,
            progress = progress,
            createdAt = row[PodcastEpisodeTable.createdAt],
            updatedAt = row[PodcastEpisodeTable.updatedAt]
        )
    }

    private fun mapTranscript(row: PodcastTranscriptRow): PodcastTranscript = PodcastTranscript(
        id = row.id,
        episodeId = row.episodeId,
        type = row.type,
        language = row.language,
        rel = row.rel,
        available = row.content != null || row.filePath != null
    )

    private fun mapShowRow(row: ResultRow): PodcastShowRow = PodcastShowRow(
        id = row[PodcastShowTable.id].value,
        source = row[PodcastShowTable.showSource],
        sourceKey = row[PodcastShowTable.sourceKey],
        feedUrl = row[PodcastShowTable.feedUrl],
        localPath = row[PodcastShowTable.localPath],
        title = row[PodcastShowTable.title],
        description = row[PodcastShowTable.description],
        author = row[PodcastShowTable.author],
        language = row[PodcastShowTable.language],
        link = row[PodcastShowTable.link],
        imageId = row[PodcastShowTable.imageId]?.value,
        imageUrl = row[PodcastShowTable.imageUrl],
        explicit = row[PodcastShowTable.explicit],
        deliveryMode = row[PodcastShowTable.deliveryMode],
        keepEpisodes = row[PodcastShowTable.keepEpisodes],
        retention = row[PodcastShowTable.retention],
        etag = row[PodcastShowTable.etag],
        lastModified = row[PodcastShowTable.lastModified],
        lastFetchedAt = row[PodcastShowTable.lastFetchedAt],
        lastFetchError = row[PodcastShowTable.lastFetchError],
        orphanedAt = row[PodcastShowTable.orphanedAt],
        createdAt = row[PodcastShowTable.createdAt],
        updatedAt = row[PodcastShowTable.updatedAt]
    )

    private fun mapEpisodeRow(row: ResultRow): PodcastEpisodeRow = PodcastEpisodeRow(
        id = row[PodcastEpisodeTable.id].value,
        showId = row[PodcastEpisodeTable.showId].value,
        guid = row[PodcastEpisodeTable.guid],
        guidKey = row[PodcastEpisodeTable.guidKey],
        title = row[PodcastEpisodeTable.title],
        description = row[PodcastEpisodeTable.description],
        link = row[PodcastEpisodeTable.link],
        publishedAt = row[PodcastEpisodeTable.publishedAt],
        durationMs = row[PodcastEpisodeTable.durationMs],
        enclosureUrl = row[PodcastEpisodeTable.enclosureUrl],
        enclosureType = row[PodcastEpisodeTable.enclosureType],
        enclosureLength = row[PodcastEpisodeTable.enclosureLength],
        filePath = row[PodcastEpisodeTable.filePath],
        fileSize = row[PodcastEpisodeTable.fileSize],
        format = row[PodcastEpisodeTable.format],
        imageId = row[PodcastEpisodeTable.imageId]?.value,
        seasonNumber = row[PodcastEpisodeTable.seasonNumber],
        episodeNumber = row[PodcastEpisodeTable.episodeNumber],
        episodeType = row[PodcastEpisodeTable.episodeType],
        explicit = row[PodcastEpisodeTable.explicit],
        importState = row[PodcastEpisodeTable.importState],
        importAttempts = row[PodcastEpisodeTable.importAttempts],
        importError = row[PodcastEpisodeTable.importError],
        importedAt = row[PodcastEpisodeTable.importedAt],
        createdAt = row[PodcastEpisodeTable.createdAt],
        updatedAt = row[PodcastEpisodeTable.updatedAt]
    )

    private fun mapTranscriptRow(row: ResultRow): PodcastTranscriptRow = PodcastTranscriptRow(
        id = row[PodcastTranscriptTable.id].value,
        episodeId = row[PodcastTranscriptTable.episodeId].value,
        sourceKey = row[PodcastTranscriptTable.sourceKey],
        url = row[PodcastTranscriptTable.url],
        filePath = row[PodcastTranscriptTable.filePath],
        type = row[PodcastTranscriptTable.type],
        language = row[PodcastTranscriptTable.language],
        rel = row[PodcastTranscriptTable.rel],
        content = row[PodcastTranscriptTable.content],
        fetchedAt = row[PodcastTranscriptTable.fetchedAt],
        fetchError = row[PodcastTranscriptTable.fetchError],
        createdAt = row[PodcastTranscriptTable.createdAt]
    )
}
