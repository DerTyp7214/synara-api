package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.core.safeQueuedGet
import dev.dertyp.data.Album
import dev.dertyp.data.AppleChart
import dev.dertyp.data.AppleChartEntry
import dev.dertyp.data.AppleChartKind
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AppleChartEntryTable
import dev.dertyp.db.AppleChartFetchTable
import dev.dertyp.services.metadata.AppleMusicService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.sync.resolveRecordingMatches
import dev.dertyp.utils.Barcodes
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.hours

class AppleChartService : Service() {
    private val environment by inject<ApplicationEnvironment>()
    private val songService by inject<SongService>()
    private val albumService by inject<AlbumService>()
    private val fetchRetryAt = ConcurrentHashMap<ChartKey, Long>()
    private val fetchLocks = ConcurrentHashMap<ChartKey, Mutex>()

    private val appleMusicService: AppleMusicService
        get() = MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService

    suspend fun chart(userId: PlatformUUID, country: String, kind: AppleChartKind, limit: Int): AppleChart {
        val key = ChartKey(country, kind)
        val cached = cachedChart(key)
        val now = System.currentTimeMillis()
        val chart = if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
            cached
        } else {
            fetchLocks.computeIfAbsent(key) { Mutex() }.withLock { refreshIfStale(key) }
        }
        val rows = chart?.rows.orEmpty().take(limit)
        return AppleChart(
            country = country,
            kind = kind,
            updatedAt = chart?.feedUpdatedAt ?: 0L,
            entries = attachLibrary(userId, kind, rows),
        )
    }

    private suspend fun refreshIfStale(key: ChartKey): CachedChart? {
        val cached = cachedChart(key)
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) return cached
        if ((fetchRetryAt[key] ?: 0L) > now) return cached

        val feed = fetchFeed(key)
        if (feed == null) {
            fetchRetryAt[key] = now + FETCH_BACKOFF_MS
            logger.warn("Fetching the Apple Music ${key.kind} chart of ${key.country} failed, serving the cached one")
            return cached
        }
        fetchRetryAt.remove(key)

        val rows = withIdentifiers(key, feed.results.mapIndexed { index, result -> result.toRow(index + 1) })
        val feedUpdatedAt = parseUpdated(feed.updated) ?: now
        val fresh = CachedChart(fetchedAt = now, feedUpdatedAt = feedUpdatedAt, rows = rows)
        runCatchingCancellable { dbQuery { storeChart(key, fresh) } }
            .onFailure { logger.error("Storing the Apple Music ${key.kind} chart of ${key.country} failed", it) }
        return fresh
    }

    private suspend fun fetchFeed(key: ChartKey): ChartFeed? {
        val path = when (key.kind) {
            AppleChartKind.SONGS -> "songs"
            AppleChartKind.ALBUMS -> "albums"
        }
        val body = ApiClient.queueInstance.safeQueuedGet<String>(
            "$FEED_BASE_URL/${key.country}/music/most-played/$FEED_SIZE/$path.json",
            HttpClientPriority.HIGH
        ) ?: return null
        return runCatching { ApplicationScope.json.decodeFromString<ChartFeedResponse>(body.trim()).feed }
            .onFailure { logger.warn("Parsing the Apple Music ${key.kind} chart of ${key.country} failed", it) }
            .getOrNull()
    }

    private suspend fun withIdentifiers(key: ChartKey, rows: List<ChartRow>): List<ChartRow> {
        val apple = appleMusicService
        if (rows.isEmpty() || !apple.catalogEnabled) return rows
        val ids = rows.map { it.appleId }
        return runCatchingCancellable {
            when (key.kind) {
                AppleChartKind.SONGS -> {
                    val isrcs = apple.getCatalogSongsByIds(ids, HttpClientPriority.HIGH, key.country)
                        .associate { it.id to it.isrc }
                    rows.map { it.copy(isrc = isrcs[it.appleId]?.trim()?.uppercase()?.takeIf { isrc -> isrc.isNotEmpty() }) }
                }

                AppleChartKind.ALBUMS -> {
                    val upcs = apple.getCatalogAlbumsByIds(ids, HttpClientPriority.HIGH, key.country)
                        .associate { it.id to it.upc }
                    rows.map { it.copy(upc = upcs[it.appleId]?.trim()?.takeIf { upc -> upc.isNotEmpty() }) }
                }
            }
        }.onFailure {
            logger.warn("Fetching catalog identifiers for the Apple Music ${key.kind} chart of ${key.country} failed", it)
        }.getOrDefault(rows)
    }

    private suspend fun attachLibrary(
        userId: PlatformUUID,
        kind: AppleChartKind,
        rows: List<ChartRow>,
    ): List<AppleChartEntry> {
        if (rows.isEmpty()) return emptyList()
        val urls = rows.map { it.url }
        return when (kind) {
            AppleChartKind.SONGS -> {
                val byUrl = songService.byOriginalUrls(urls, userId).mapValues { it.value?.id }
                val isrcs = rows.filter { byUrl[it.url] == null }.mapNotNull { it.isrc }.distinct()
                val byIsrc = if (isrcs.isEmpty()) emptyMap()
                else dbQuery { resolveRecordingMatches(emptyList(), isrcs) }.isrcToSong
                val matched = rows.associate { row -> row.rank to (byUrl[row.url] ?: row.isrc?.let { byIsrc[it] }) }
                val songs = songService.byIds(matched.values.filterNotNull().distinct(), userId).associateBy { it.id }
                rows.map { row -> row.toEntry(song = matched[row.rank]?.let { songs[it] }, album = null) }
            }

            AppleChartKind.ALBUMS -> {
                val byUrl = albumService.byOriginalUrls(urls).mapValues { it.value?.id }
                val variantsByRow = rows.filter { byUrl[it.url] == null }
                    .associate { row -> row.rank to Barcodes.variants(row.upc) }
                    .filterValues { it.isNotEmpty() }
                val allVariants = variantsByRow.values.flatten().distinct()
                val albumByBarcode = if (allVariants.isEmpty()) emptyMap()
                else dbQuery {
                    AlbumTable
                        .select(AlbumTable.id, AlbumTable.barcode)
                        .where { AlbumTable.barcode inList allVariants }
                        .orderBy(AlbumTable.id)
                        .mapNotNull { row -> row[AlbumTable.barcode]?.let { it to row[AlbumTable.id].value } }
                        .distinctBy { it.first }
                        .toMap()
                }
                val matched = rows.associate { row ->
                    row.rank to (byUrl[row.url]
                        ?: variantsByRow[row.rank]?.firstNotNullOfOrNull { albumByBarcode[it] })
                }
                val albums = albumService.byIds(matched.values.filterNotNull().distinct(), userId).associateBy { it.id }
                rows.map { row -> row.toEntry(song = null, album = matched[row.rank]?.let { albums[it] }) }
            }
        }
    }

    private suspend fun cachedChart(key: ChartKey): CachedChart? = dbQuery {
        val fetch = AppleChartFetchTable
            .selectAll()
            .where { (AppleChartFetchTable.country eq key.country) and (AppleChartFetchTable.kind eq key.kind) }
            .singleOrNull() ?: return@dbQuery null
        val rows = AppleChartEntryTable
            .selectAll()
            .where { (AppleChartEntryTable.country eq key.country) and (AppleChartEntryTable.kind eq key.kind) }
            .orderBy(AppleChartEntryTable.rank)
            .map {
                ChartRow(
                    rank = it[AppleChartEntryTable.rank],
                    appleId = it[AppleChartEntryTable.appleId],
                    name = it[AppleChartEntryTable.name],
                    artistName = it[AppleChartEntryTable.artistName],
                    releaseDate = it[AppleChartEntryTable.releaseDate],
                    artworkUrl = it[AppleChartEntryTable.artworkUrl],
                    url = it[AppleChartEntryTable.url],
                    genres = it[AppleChartEntryTable.genres].split(GENRE_SEPARATOR).filter { genre -> genre.isNotEmpty() },
                    isrc = it[AppleChartEntryTable.isrc],
                    upc = it[AppleChartEntryTable.upc],
                )
            }
        CachedChart(
            fetchedAt = fetch[AppleChartFetchTable.fetchedAt],
            feedUpdatedAt = fetch[AppleChartFetchTable.feedUpdatedAt],
            rows = rows,
        )
    }

    private fun storeChart(key: ChartKey, chart: CachedChart) {
        AppleChartEntryTable.deleteWhere {
            (AppleChartEntryTable.country eq key.country) and (AppleChartEntryTable.kind eq key.kind)
        }
        AppleChartEntryTable.batchInsert(chart.rows, shouldReturnGeneratedValues = false) { row ->
            this[AppleChartEntryTable.country] = key.country
            this[AppleChartEntryTable.kind] = key.kind
            this[AppleChartEntryTable.rank] = row.rank
            this[AppleChartEntryTable.appleId] = row.appleId
            this[AppleChartEntryTable.name] = row.name
            this[AppleChartEntryTable.artistName] = row.artistName
            this[AppleChartEntryTable.releaseDate] = row.releaseDate
            this[AppleChartEntryTable.artworkUrl] = row.artworkUrl
            this[AppleChartEntryTable.url] = row.url
            this[AppleChartEntryTable.genres] = row.genres.joinToString(GENRE_SEPARATOR)
            this[AppleChartEntryTable.isrc] = row.isrc
            this[AppleChartEntryTable.upc] = row.upc
        }
        AppleChartFetchTable.upsert {
            it[AppleChartFetchTable.country] = key.country
            it[AppleChartFetchTable.kind] = key.kind
            it[AppleChartFetchTable.fetchedAt] = chart.fetchedAt
            it[AppleChartFetchTable.feedUpdatedAt] = chart.feedUpdatedAt
        }
    }

    private fun parseUpdated(value: String?): Long? = value?.let {
        runCatching { OffsetDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun ChartFeedResult.toRow(rank: Int): ChartRow = ChartRow(
        rank = rank,
        appleId = id,
        name = name,
        artistName = artistName,
        releaseDate = releaseDate?.takeIf { it.isNotBlank() },
        artworkUrl = artworkUrl100?.takeIf { it.isNotBlank() }?.let { AppleMusicService.artworkUrlForSize(it, ARTWORK_SIZE) },
        url = url,
        genres = genres.filter { it.genreId != GENERIC_MUSIC_GENRE_ID }.map { it.name }.filter { it.isNotBlank() },
        isrc = null,
        upc = null,
    )

    private fun ChartRow.toEntry(song: UserSong?, album: Album?): AppleChartEntry =
        AppleChartEntry(
            rank = rank,
            appleId = appleId,
            name = name,
            artistName = artistName,
            releaseDate = releaseDate,
            artworkUrl = artworkUrl,
            url = url,
            genres = genres,
            song = song,
            album = album,
        )

    private data class ChartKey(val country: String, val kind: AppleChartKind)

    private data class CachedChart(val fetchedAt: Long, val feedUpdatedAt: Long, val rows: List<ChartRow>)

    private data class ChartRow(
        val rank: Int,
        val appleId: String,
        val name: String,
        val artistName: String,
        val releaseDate: String?,
        val artworkUrl: String?,
        val url: String,
        val genres: List<String>,
        val isrc: String?,
        val upc: String?,
    )

    @Serializable
    private data class ChartFeedResponse(val feed: ChartFeed)

    @Serializable
    private data class ChartFeed(val updated: String? = null, val results: List<ChartFeedResult> = emptyList())

    @Serializable
    private data class ChartFeedResult(
        val id: String,
        val name: String,
        val artistName: String = "",
        val releaseDate: String? = null,
        val artworkUrl100: String? = null,
        val url: String,
        val genres: List<ChartFeedGenre> = emptyList(),
    )

    @Serializable
    private data class ChartFeedGenre(val genreId: String? = null, val name: String = "")

    companion object {
        const val MAX_LIMIT = 100
        private const val FEED_BASE_URL = "https://rss.marketingtools.apple.com/api/v2"
        private const val FEED_SIZE = 100
        private const val ARTWORK_SIZE = 600
        private const val GENERIC_MUSIC_GENRE_ID = "34"
        private const val GENRE_SEPARATOR = "\n"
        private val CACHE_TTL_MS = 6.hours.inWholeMilliseconds
        private val FETCH_BACKOFF_MS = 1.hours.inWholeMilliseconds
    }
}

class RpcAppleChartService(private val user: User, private val service: AppleChartService) : IChartService {
    override suspend fun appleChart(country: String, kind: AppleChartKind, limit: Int): AppleChart {
        val code = country.trim().lowercase()
        require(COUNTRY_CODE.matches(code)) { "Invalid country code: $country" }
        return service.chart(user.id, code, kind, limit.coerceIn(1, AppleChartService.MAX_LIMIT))
    }

    private companion object {
        val COUNTRY_CODE = Regex("^[a-z]{2}$")
    }
}
