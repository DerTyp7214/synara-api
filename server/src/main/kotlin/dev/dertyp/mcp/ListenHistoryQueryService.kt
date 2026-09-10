package dev.dertyp.mcp

import dev.dertyp.core.fullTitle
import dev.dertyp.data.UserSong
import dev.dertyp.db.AlbumMusicBrainzTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistMusicBrainzTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ListenSource
import dev.dertyp.db.ListenTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongMusicBrainzTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.fullSongTitle
import dev.dertyp.db.listenOwnerPredicate
import dev.dertyp.dbQuery
import dev.dertyp.formatISO
import dev.dertyp.services.AlbumService
import dev.dertyp.services.ArtistService
import dev.dertyp.services.ScrobbleService
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class McpTopKind { SONGS, ARTISTS, ALBUMS }

enum class McpTopOrder { LISTEN_COUNT, LISTENED_MS }

enum class McpBucket { HOUR, DAY, WEEK, MONTH, YEAR }

data class ListenFilter(
    val from: Long? = null,
    val to: Long? = null,
    val songId: UUID? = null,
    val artistId: UUID? = null,
    val albumId: UUID? = null,
    val source: ListenSource? = null,
    val qualifiedOnly: Boolean = false,
)

class ListenHistoryQueryService(
    private val songService: SongService,
    private val artistService: ArtistService,
    private val albumService: AlbumService,
    private val scrobbleService: ScrobbleService,
) : Service() {

    suspend fun listens(userId: UUID, filter: ListenFilter, limit: Int, cursor: String?, zone: ZoneId): McpListensPage {
        val capped = limit.coerceIn(1, MAX_LISTENS_LIMIT)
        val position = cursor?.let { decodeCursor(it) }

        val rows = dbQuery {
            var query = filteredQuery(userId, filter)
            if (position != null) {
                query = query.andWhere {
                    (ListenTable.listenedAt less position.first) or
                        ((ListenTable.listenedAt eq position.first) and (ListenTable.id less position.second))
                }
            }
            query
                .orderBy(ListenTable.listenedAt to SortOrder.DESC, ListenTable.id to SortOrder.DESC)
                .limit(capped + 1)
                .map { it.toWindowRow() }
        }

        val hasMore = rows.size > capped
        val page = rows.take(capped)
        val songs = resolveUserSongs(page.mapNotNull { it.songId }.distinct(), userId)
        val last = page.lastOrNull()

        return McpListensPage(
            listens = page.map { row ->
                McpListen(
                    id = row.id.toString(),
                    listenedAt = mcpTime(row.listenedAt, zone),
                    msPlayed = row.msPlayed,
                    playedMs = ListenTable.playedMs(row.msPlayed, row.duration),
                    qualified = ListenTable.isQualifiedPlay(row.msPlayed, row.duration),
                    source = row.source.name,
                    song = row.songId?.let { songs[it] },
                    unmatched = if (row.songId == null) row.toUnmatched() else null,
                )
            },
            nextCursor = if (hasMore && last != null) encodeCursor(last.listenedAt, last.id) else null,
            hasMore = hasMore,
        )
    }

    suspend fun summary(userId: UUID, filter: ListenFilter, zone: ZoneId): McpListeningSummary {
        val aggregate = aggregate(window(userId, filter), zone)
        val library = resolveLibrary(aggregate)

        return McpListeningSummary(
            from = filter.from?.let { mcpTime(it, zone) },
            to = filter.to?.let { mcpTime(it, zone) },
            timezone = zone.id,
            listenCount = aggregate.listenCount,
            listenedMs = aggregate.listenedMs,
            uniqueSongs = aggregate.songs.size,
            uniqueArtists = artistGroups(aggregate, library).size,
            uniqueAlbums = albumGroups(aggregate, library).size,
            firstListen = aggregate.firstListen?.let { mcpTime(it, zone) },
            lastListen = aggregate.lastListen?.let { mcpTime(it, zone) },
            hourOfDay = aggregate.hourOfDay.toList(),
            dayOfWeek = aggregate.dayOfWeek.toList(),
            daysWithListens = aggregate.days.size,
        )
    }

    suspend fun top(
        userId: UUID,
        filter: ListenFilter,
        kind: McpTopKind,
        order: McpTopOrder,
        limit: Int,
        offset: Int,
        zone: ZoneId,
    ): McpTopPage {
        val cappedLimit = limit.coerceIn(1, MAX_TOP_LIMIT)
        val cappedOffset = offset.coerceAtLeast(0)
        val aggregate = aggregate(window(userId, filter), zone)
        val library = resolveLibrary(aggregate)

        val total: Int
        val entries: List<McpTopEntry>
        when (kind) {
            McpTopKind.SONGS -> {
                val sorted = sortGroups(aggregate.songs, order)
                total = sorted.size
                entries = sorted.drop(cappedOffset).take(cappedLimit).mapIndexed { index, (key, group) ->
                    songEntry(cappedOffset + index + 1, key, group, aggregate, library, zone)
                }
            }

            McpTopKind.ARTISTS -> {
                val sorted = sortGroups(artistGroups(aggregate, library), order)
                total = sorted.size
                entries = sorted.drop(cappedOffset).take(cappedLimit).mapIndexed { index, (key, group) ->
                    artistEntry(cappedOffset + index + 1, key, group, aggregate, library, zone)
                }
            }

            McpTopKind.ALBUMS -> {
                val sorted = sortGroups(albumGroups(aggregate, library), order)
                total = sorted.size
                entries = sorted.drop(cappedOffset).take(cappedLimit).mapIndexed { index, (key, group) ->
                    albumEntry(cappedOffset + index + 1, key, group, aggregate, library, zone)
                }
            }
        }

        return McpTopPage(
            kind = kind.name,
            orderBy = order.name,
            offset = cappedOffset,
            limit = cappedLimit,
            total = total,
            entries = entries,
        )
    }

    suspend fun timeline(userId: UUID, filter: ListenFilter, bucket: McpBucket, zone: ZoneId): McpTimeline {
        val rows = window(userId, filter)
        val now = System.currentTimeMillis()
        val startMs = filter.from ?: rows.firstOrNull()?.listenedAt
            ?: return McpTimeline(bucket = bucket.name, timezone = zone.id, buckets = emptyList())
        val endMs = filter.to ?: maxOf(now, rows.lastOrNull()?.listenedAt ?: now)

        val starts = ArrayList<Long>()
        var cursor = bucketStart(Instant.ofEpochMilli(startMs).atZone(zone), bucket)
        while (cursor.toInstant().toEpochMilli() < endMs) {
            require(starts.size < MAX_BUCKETS) {
                "Requested range produces more than $MAX_BUCKETS buckets; narrow the range or use a coarser bucket"
            }
            starts.add(cursor.toInstant().toEpochMilli())
            cursor = cursor.plusBucket(bucket)
        }

        val groups = HashMap<Long, Group>()
        val uniqueSongs = HashMap<Long, MutableSet<SongKey>>()
        for (row in rows) {
            val start = bucketStart(Instant.ofEpochMilli(row.listenedAt).atZone(zone), bucket).toInstant().toEpochMilli()
            groups.getOrPut(start) { Group() }.add(row.listenedAt, ListenTable.playedMs(row.msPlayed, row.duration))
            songKeyOf(row)?.let { uniqueSongs.getOrPut(start) { HashSet() }.add(it) }
        }

        return McpTimeline(
            bucket = bucket.name,
            timezone = zone.id,
            buckets = starts.map { start ->
                val group = groups[start]
                McpTimelineBucket(
                    bucketStart = mcpTime(start, zone),
                    listenCount = group?.listenCount ?: 0L,
                    listenedMs = group?.listenedMs ?: 0L,
                    uniqueSongs = uniqueSongs[start]?.size ?: 0,
                )
            },
        )
    }

    suspend fun search(
        userId: UUID,
        query: String,
        includeSongs: Boolean,
        includeArtists: Boolean,
        includeAlbums: Boolean,
        limit: Int,
    ): McpSearchResult {
        val capped = limit.coerceIn(1, MAX_SEARCH_LIMIT)

        return McpSearchResult(
            songs = if (includeSongs) {
                songService.rankedSearch(1, capped, query, explicit = true, userId = userId).data.map { it.toMcpSong() }
            } else emptyList(),
            artists = if (includeArtists) {
                artistService.rankedSearch(1, capped, query, userId).data.map { McpArtistRef(it.id.toString(), it.name) }
            } else emptyList(),
            albums = if (includeAlbums) {
                albumService.rankedSearch(1, capped, query, userId).data.map { McpAlbumRef(it.id.toString(), it.name) }
            } else emptyList(),
        )
    }

    suspend fun nowPlaying(userId: UUID, zone: ZoneId): McpNowPlaying? {
        val snapshot = scrobbleService.currentNowPlaying(userId) ?: return null
        return McpNowPlaying(
            song = snapshot.song.toMcpSong(),
            startedAt = mcpTime(snapshot.startedAt, zone),
            positionMs = snapshot.positionMs,
            playing = snapshot.playing,
        )
    }

    private fun filteredQuery(userId: UUID, filter: ListenFilter): Query {
        var query = ListenTable
            .leftJoin(SongTable)
            .select(WINDOW_COLUMNS)
            .where { listenOwnerPredicate(userId) }

        filter.from?.let { from -> query = query.andWhere { ListenTable.listenedAt greaterEq from } }
        filter.to?.let { to -> query = query.andWhere { ListenTable.listenedAt less to } }
        filter.songId?.let { songId -> query = query.andWhere { ListenTable.songId eq songId } }
        filter.albumId?.let { albumId -> query = query.andWhere { SongTable.albumId eq albumId } }
        filter.artistId?.let { artistId ->
            query = query.andWhere {
                ListenTable.songId inSubQuery SongArtistTable
                    .select(SongArtistTable.songId)
                    .where { SongArtistTable.artistId eq artistId }
            }
        }
        filter.source?.let { source -> query = query.andWhere { ListenTable.listenSource eq source } }
        if (filter.qualifiedOnly) query = query.andWhere { ListenTable.qualifiedPlay }

        return query
    }

    private suspend fun window(userId: UUID, filter: ListenFilter): List<WindowRow> {
        val rows = dbQuery {
            filteredQuery(userId, filter)
                .orderBy(ListenTable.listenedAt to SortOrder.ASC, ListenTable.id to SortOrder.ASC)
                .map { it.toWindowRow() }
        }

        val kept = ArrayList<WindowRow>(rows.size)
        var previous: WindowRow? = null
        for (row in rows) {
            val duplicate = previous?.let { samePlay(it, row) } == true
            previous = row
            if (!duplicate) kept.add(row)
        }
        return kept
    }

    private fun samePlay(previous: WindowRow, row: WindowRow): Boolean {
        if (row.listenedAt - previous.listenedAt > ListenTable.DEDUP_WINDOW_MS) return false
        if (row.songId != null && row.songId == previous.songId) return true
        if (row.recordingMbid != null && row.recordingMbid == previous.recordingMbid) return true
        return row.isrcs.any { it in previous.isrcs }
    }

    private fun aggregate(rows: List<WindowRow>, zone: ZoneId): Aggregate {
        val aggregate = Aggregate()
        for (row in rows) {
            val playedMs = ListenTable.playedMs(row.msPlayed, row.duration)
            val local = Instant.ofEpochMilli(row.listenedAt).atZone(zone)

            aggregate.listenCount++
            aggregate.listenedMs += playedMs
            aggregate.hourOfDay[local.hour]++
            aggregate.dayOfWeek[local.dayOfWeek.value - 1]++
            aggregate.days.add(local.toLocalDate().toEpochDay())
            if (aggregate.firstListen == null) aggregate.firstListen = row.listenedAt
            aggregate.lastListen = row.listenedAt

            songKeyOf(row)?.let { key ->
                aggregate.songs.getOrPut(key) { Group() }.add(row.listenedAt, playedMs)
                if (row.songId == null) {
                    aggregate.songDisplay.putIfAbsent(
                        key,
                        UnmatchedDisplay(
                            title = row.trackName ?: row.recordingMbid?.toString() ?: "",
                            artistName = row.artistName,
                            albumName = row.releaseName,
                        ),
                    )
                }
            }

            if (row.songId != null) continue

            artistKeyOf(row)?.let { key ->
                aggregate.unmatchedArtists.getOrPut(key) { Group() }.add(row.listenedAt, playedMs)
                row.artistName?.let { aggregate.artistDisplay.putIfAbsent(key, it) }
            }
            albumKeyOf(row)?.let { key ->
                aggregate.unmatchedAlbums.getOrPut(key) { Group() }.add(row.listenedAt, playedMs)
                row.releaseName?.let { aggregate.albumDisplay.putIfAbsent(key, it) }
            }
        }
        return aggregate
    }

    private fun songKeyOf(row: WindowRow): SongKey? = when {
        row.songId != null -> SongKey.Matched(row.songId)
        row.recordingMbid != null -> SongKey.Mbid(row.recordingMbid)
        row.trackName != null -> SongKey.Named(row.trackName.lowercase(), row.artistName?.lowercase() ?: "")
        else -> null
    }

    private fun artistKeyOf(row: WindowRow): ArtistKey? {
        val mbid = row.artistMbids.firstNotNullOfOrNull { runCatching { UUID.fromString(it) }.getOrNull() }
        return when {
            mbid != null -> ArtistKey.Mbid(mbid)
            row.artistName != null -> ArtistKey.Named(row.artistName.lowercase())
            else -> null
        }
    }

    private fun albumKeyOf(row: WindowRow): AlbumKey? = when {
        row.releaseMbid != null -> AlbumKey.Mbid(row.releaseMbid)
        row.releaseName != null -> AlbumKey.Named(row.releaseName.lowercase())
        else -> null
    }

    private suspend fun resolveLibrary(aggregate: Aggregate): Library {
        val songIds = aggregate.songs.keys.filterIsInstance<SongKey.Matched>().map { it.songId }
        val artistMbids = aggregate.unmatchedArtists.keys.filterIsInstance<ArtistKey.Mbid>().map { it.mbid }
        val releaseMbids = aggregate.unmatchedAlbums.keys.filterIsInstance<AlbumKey.Mbid>().map { it.mbid }
        return dbQuery { loadLibrary(songIds, artistMbids, releaseMbids) }
    }

    private fun loadLibrary(songIds: List<UUID>, artistMbids: List<UUID>, releaseMbids: List<UUID>): Library {
        val library = Library()

        songIds.chunked(CHUNK_SIZE).forEach { chunk ->
            SongTable
                .select(SongTable.id, SongTable.title, SongTable.titleTags, SongTable.albumId)
                .where { SongTable.id inList chunk }
                .forEach {
                    val songId = it[SongTable.id].value
                    library.songTitles[songId] = it.fullSongTitle()
                    library.songAlbums[songId] = it[SongTable.albumId].value
                }

            SongArtistTable
                .select(SongArtistTable.songId, SongArtistTable.artistId)
                .where { SongArtistTable.songId inList chunk }
                .orderBy(SongArtistTable.artistId)
                .forEach {
                    library.songArtists.getOrPut(it[SongArtistTable.songId].value) { mutableListOf() }
                        .add(it[SongArtistTable.artistId].value)
                }

            SongMusicBrainzTable
                .select(SongMusicBrainzTable.songId, SongMusicBrainzTable.musicBrainzId)
                .where { SongMusicBrainzTable.songId inList chunk }
                .forEach { row ->
                    row[SongMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.songRecordingMbids[row[SongMusicBrainzTable.songId].value] = it
                    }
                }
        }

        artistMbids.chunked(CHUNK_SIZE).forEach { chunk ->
            ArtistMusicBrainzTable
                .select(ArtistMusicBrainzTable.artistId, ArtistMusicBrainzTable.musicBrainzId)
                .where { ArtistMusicBrainzTable.musicBrainzId inList chunk }
                .forEach { row ->
                    row[ArtistMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.artistIdByMbid.putIfAbsent(it, row[ArtistMusicBrainzTable.artistId].value)
                    }
                }
        }

        releaseMbids.chunked(CHUNK_SIZE).forEach { chunk ->
            AlbumMusicBrainzTable
                .select(AlbumMusicBrainzTable.albumId, AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.musicBrainzId inList chunk }
                .forEach { row ->
                    row[AlbumMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.albumIdByReleaseMbid.putIfAbsent(it, row[AlbumMusicBrainzTable.albumId].value)
                    }
                }
        }

        val artistIds = (library.songArtists.values.flatten() + library.artistIdByMbid.values).distinct()
        artistIds.chunked(CHUNK_SIZE).forEach { chunk ->
            ArtistTable
                .select(ArtistTable.id, ArtistTable.name)
                .where { ArtistTable.id inList chunk }
                .forEach { library.artistNames[it[ArtistTable.id].value] = it[ArtistTable.name] }

            ArtistMusicBrainzTable
                .select(ArtistMusicBrainzTable.artistId, ArtistMusicBrainzTable.musicBrainzId)
                .where { ArtistMusicBrainzTable.artistId inList chunk }
                .forEach { row ->
                    row[ArtistMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.artistMbids[row[ArtistMusicBrainzTable.artistId].value] = it
                    }
                }
        }

        val albumIds = (library.songAlbums.values + library.albumIdByReleaseMbid.values).distinct()
        albumIds.chunked(CHUNK_SIZE).forEach { chunk ->
            AlbumTable
                .select(AlbumTable.id, AlbumTable.name)
                .where { AlbumTable.id inList chunk }
                .forEach { library.albumNames[it[AlbumTable.id].value] = it[AlbumTable.name] }

            AlbumMusicBrainzTable
                .select(AlbumMusicBrainzTable.albumId, AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.albumId inList chunk }
                .forEach { row ->
                    row[AlbumMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.albumMbids[row[AlbumMusicBrainzTable.albumId].value] = it
                    }
                }
        }

        return library
    }

    private fun artistGroups(aggregate: Aggregate, library: Library): LinkedHashMap<ArtistKey, Group> {
        val groups = LinkedHashMap<ArtistKey, Group>()
        aggregate.unmatchedArtists.forEach { (key, group) ->
            val canonical = (key as? ArtistKey.Mbid)
                ?.let { library.artistIdByMbid[it.mbid] }
                ?.let { ArtistKey.Matched(it) } ?: key
            groups.getOrPut(canonical) { Group() }.merge(group)
        }
        aggregate.songs.forEach { (key, group) ->
            if (key !is SongKey.Matched) return@forEach
            library.songArtists[key.songId]?.forEach { artistId ->
                groups.getOrPut(ArtistKey.Matched(artistId)) { Group() }.merge(group)
            }
        }
        return groups
    }

    private fun albumGroups(aggregate: Aggregate, library: Library): LinkedHashMap<AlbumKey, Group> {
        val groups = LinkedHashMap<AlbumKey, Group>()
        aggregate.unmatchedAlbums.forEach { (key, group) ->
            val canonical = (key as? AlbumKey.Mbid)
                ?.let { library.albumIdByReleaseMbid[it.mbid] }
                ?.let { AlbumKey.Matched(it) } ?: key
            groups.getOrPut(canonical) { Group() }.merge(group)
        }
        aggregate.songs.forEach { (key, group) ->
            if (key !is SongKey.Matched) return@forEach
            library.songAlbums[key.songId]?.let { albumId ->
                groups.getOrPut(AlbumKey.Matched(albumId)) { Group() }.merge(group)
            }
        }
        return groups
    }

    private fun <K> sortGroups(groups: Map<K, Group>, order: McpTopOrder): List<Pair<K, Group>> = groups.entries
        .sortedWith(
            compareByDescending<Map.Entry<K, Group>> {
                if (order == McpTopOrder.LISTENED_MS) it.value.listenedMs else it.value.listenCount
            }.thenByDescending { it.value.lastListen },
        )
        .map { it.key to it.value }

    private fun songEntry(
        rank: Int,
        key: SongKey,
        group: Group,
        aggregate: Aggregate,
        library: Library,
        zone: ZoneId,
    ): McpTopEntry = when (key) {
        is SongKey.Matched -> topEntry(
            rank = rank,
            matched = true,
            id = key.songId.toString(),
            name = library.songTitles[key.songId] ?: "",
            artistName = library.songArtists[key.songId]
                ?.mapNotNull { library.artistNames[it] }
                ?.joinToString(", ")
                ?.ifBlank { null },
            albumName = library.songAlbums[key.songId]?.let { library.albumNames[it] },
            mbid = library.songRecordingMbids[key.songId]?.toString(),
            group = group,
            zone = zone,
        )

        else -> {
            val display = aggregate.songDisplay[key]
            topEntry(
                rank = rank,
                matched = false,
                id = null,
                name = display?.title ?: "",
                artistName = display?.artistName,
                albumName = display?.albumName,
                mbid = (key as? SongKey.Mbid)?.mbid?.toString(),
                group = group,
                zone = zone,
            )
        }
    }

    private fun artistEntry(
        rank: Int,
        key: ArtistKey,
        group: Group,
        aggregate: Aggregate,
        library: Library,
        zone: ZoneId,
    ): McpTopEntry = when (key) {
        is ArtistKey.Matched -> topEntry(
            rank = rank,
            matched = true,
            id = key.artistId.toString(),
            name = library.artistNames[key.artistId] ?: "",
            artistName = null,
            albumName = null,
            mbid = library.artistMbids[key.artistId]?.toString(),
            group = group,
            zone = zone,
        )

        else -> topEntry(
            rank = rank,
            matched = false,
            id = null,
            name = aggregate.artistDisplay[key] ?: (key as? ArtistKey.Mbid)?.mbid?.toString() ?: "",
            artistName = null,
            albumName = null,
            mbid = (key as? ArtistKey.Mbid)?.mbid?.toString(),
            group = group,
            zone = zone,
        )
    }

    private fun albumEntry(
        rank: Int,
        key: AlbumKey,
        group: Group,
        aggregate: Aggregate,
        library: Library,
        zone: ZoneId,
    ): McpTopEntry = when (key) {
        is AlbumKey.Matched -> topEntry(
            rank = rank,
            matched = true,
            id = key.albumId.toString(),
            name = library.albumNames[key.albumId] ?: "",
            artistName = null,
            albumName = library.albumNames[key.albumId],
            mbid = library.albumMbids[key.albumId]?.toString(),
            group = group,
            zone = zone,
        )

        else -> topEntry(
            rank = rank,
            matched = false,
            id = null,
            name = aggregate.albumDisplay[key] ?: (key as? AlbumKey.Mbid)?.mbid?.toString() ?: "",
            artistName = null,
            albumName = aggregate.albumDisplay[key],
            mbid = (key as? AlbumKey.Mbid)?.mbid?.toString(),
            group = group,
            zone = zone,
        )
    }

    private fun topEntry(
        rank: Int,
        matched: Boolean,
        id: String?,
        name: String,
        artistName: String?,
        albumName: String?,
        mbid: String?,
        group: Group,
        zone: ZoneId,
    ): McpTopEntry = McpTopEntry(
        rank = rank,
        matched = matched,
        id = id,
        name = name,
        artistName = artistName,
        albumName = albumName,
        mbid = mbid,
        listenCount = group.listenCount,
        listenedMs = group.listenedMs,
        firstListen = mcpTime(group.firstListen, zone),
        lastListen = mcpTime(group.lastListen, zone),
    )

    private suspend fun resolveUserSongs(songIds: List<UUID>, userId: UUID): Map<UUID, McpSong> {
        if (songIds.isEmpty()) return emptyMap()
        val songs = HashMap<UUID, McpSong>(songIds.size)
        songIds.chunked(CHUNK_SIZE).forEach { chunk ->
            songService.byIds(chunk, userId).forEach { songs[it.id] = it.toMcpSong() }
        }
        return songs
    }

    private fun UserSong.toMcpSong(): McpSong = McpSong(
        id = id.toString(),
        title = fullTitle,
        artists = artists.map { McpArtistRef(it.id.toString(), it.creditedName ?: it.name) },
        album = album?.let { McpAlbumRef(it.id.toString(), it.name) },
        durationMs = duration,
        releaseDate = releaseDate?.formatISO(),
        genres = genres.map { it.name },
        isrc = isrc,
        recordingMbid = musicBrainzId?.toString(),
        explicit = explicit,
    )

    private fun WindowRow.toUnmatched(): McpUnmatched = McpUnmatched(
        trackName = trackName,
        artistName = artistName,
        releaseName = releaseName,
        recordingMbid = recordingMbid?.toString(),
        releaseMbid = releaseMbid?.toString(),
        artistMbids = artistMbids,
    )

    private fun ResultRow.toWindowRow(): WindowRow = WindowRow(
        id = this[ListenTable.id].value,
        listenedAt = this[ListenTable.listenedAt],
        msPlayed = this[ListenTable.msPlayed],
        duration = getOrNull(SongTable.duration),
        source = this[ListenTable.listenSource],
        songId = this[ListenTable.songId]?.value,
        recordingMbid = this[ListenTable.recordingMbid],
        releaseMbid = this[ListenTable.releaseMbid],
        isrcs = ListenTable.parseIsrcs(this[ListenTable.isrcs]),
        artistMbids = this[ListenTable.artistMbids]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList(),
        trackName = this[ListenTable.trackName]?.trim()?.ifBlank { null },
        artistName = this[ListenTable.artistName]?.trim()?.ifBlank { null },
        releaseName = this[ListenTable.releaseName]?.trim()?.ifBlank { null },
    )

    private fun bucketStart(time: ZonedDateTime, bucket: McpBucket): ZonedDateTime = when (bucket) {
        McpBucket.HOUR -> time.truncatedTo(ChronoUnit.HOURS)
        McpBucket.DAY -> time.toLocalDate().atStartOfDay(time.zone)
        McpBucket.WEEK -> time.toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(time.zone)

        McpBucket.MONTH -> time.toLocalDate().withDayOfMonth(1).atStartOfDay(time.zone)
        McpBucket.YEAR -> time.toLocalDate().withDayOfYear(1).atStartOfDay(time.zone)
    }

    private fun ZonedDateTime.plusBucket(bucket: McpBucket): ZonedDateTime = when (bucket) {
        McpBucket.HOUR -> plusHours(1)
        McpBucket.DAY -> plusDays(1)
        McpBucket.WEEK -> plusWeeks(1)
        McpBucket.MONTH -> plusMonths(1)
        McpBucket.YEAR -> plusYears(1)
    }

    private fun encodeCursor(listenedAt: Long, id: UUID): String = "$listenedAt:$id"

    private fun decodeCursor(cursor: String): Pair<Long, UUID> {
        val parts = cursor.split(':', limit = 2)
        val listenedAt = parts.getOrNull(0)?.trim()?.toLongOrNull()
        val id = parts.getOrNull(1)?.trim()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        require(listenedAt != null && id != null) { "Invalid cursor '$cursor'" }
        return listenedAt to id
    }

    private data class WindowRow(
        val id: UUID,
        val listenedAt: Long,
        val msPlayed: Long?,
        val duration: Long?,
        val source: ListenSource,
        val songId: UUID?,
        val recordingMbid: UUID?,
        val releaseMbid: UUID?,
        val isrcs: Set<String>,
        val artistMbids: List<String>,
        val trackName: String?,
        val artistName: String?,
        val releaseName: String?,
    )

    private sealed interface SongKey {
        data class Matched(val songId: UUID) : SongKey
        data class Mbid(val mbid: UUID) : SongKey
        data class Named(val track: String, val artist: String) : SongKey
    }

    private sealed interface ArtistKey {
        data class Matched(val artistId: UUID) : ArtistKey
        data class Mbid(val mbid: UUID) : ArtistKey
        data class Named(val name: String) : ArtistKey
    }

    private sealed interface AlbumKey {
        data class Matched(val albumId: UUID) : AlbumKey
        data class Mbid(val mbid: UUID) : AlbumKey
        data class Named(val name: String) : AlbumKey
    }

    private data class UnmatchedDisplay(val title: String, val artistName: String?, val albumName: String?)

    private class Group {
        var listenCount = 0L
        var listenedMs = 0L
        var firstListen = Long.MAX_VALUE
        var lastListen = Long.MIN_VALUE

        fun add(listenedAt: Long, playedMs: Long) {
            listenCount++
            listenedMs += playedMs
            if (listenedAt < firstListen) firstListen = listenedAt
            if (listenedAt > lastListen) lastListen = listenedAt
        }

        fun merge(other: Group) {
            listenCount += other.listenCount
            listenedMs += other.listenedMs
            if (other.firstListen < firstListen) firstListen = other.firstListen
            if (other.lastListen > lastListen) lastListen = other.lastListen
        }
    }

    private class Aggregate {
        var listenCount = 0L
        var listenedMs = 0L
        var firstListen: Long? = null
        var lastListen: Long? = null
        val hourOfDay = LongArray(24)
        val dayOfWeek = LongArray(7)
        val days = HashSet<Long>()
        val songs = LinkedHashMap<SongKey, Group>()
        val unmatchedArtists = LinkedHashMap<ArtistKey, Group>()
        val unmatchedAlbums = LinkedHashMap<AlbumKey, Group>()
        val songDisplay = HashMap<SongKey, UnmatchedDisplay>()
        val artistDisplay = HashMap<ArtistKey, String>()
        val albumDisplay = HashMap<AlbumKey, String>()
    }

    private class Library {
        val songTitles = HashMap<UUID, String>()
        val songAlbums = HashMap<UUID, UUID>()
        val songArtists = HashMap<UUID, MutableList<UUID>>()
        val songRecordingMbids = HashMap<UUID, UUID>()
        val artistNames = HashMap<UUID, String>()
        val artistMbids = HashMap<UUID, UUID>()
        val albumNames = HashMap<UUID, String>()
        val albumMbids = HashMap<UUID, UUID>()
        val artistIdByMbid = HashMap<UUID, UUID>()
        val albumIdByReleaseMbid = HashMap<UUID, UUID>()
    }

    companion object {
        const val MAX_LISTENS_LIMIT = 1000
        const val MAX_TOP_LIMIT = 200
        const val MAX_BUCKETS = 2000
        private const val MAX_SEARCH_LIMIT = 50
        private const val CHUNK_SIZE = 1000

        private val WINDOW_COLUMNS: List<Expression<*>> = listOf(
            ListenTable.id,
            ListenTable.listenedAt,
            ListenTable.msPlayed,
            ListenTable.listenSource,
            ListenTable.songId,
            ListenTable.recordingMbid,
            ListenTable.releaseMbid,
            ListenTable.isrcs,
            ListenTable.artistMbids,
            ListenTable.trackName,
            ListenTable.artistName,
            ListenTable.releaseName,
            SongTable.duration,
        )
    }
}
