package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.sync.ListenBrainzService
import org.koin.core.component.inject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

class ListeningStatsService : Service() {
    private val listenService by inject<ListenService>()
    private val listenBrainzService by inject<ListenBrainzService>()

    suspend fun linkUnmatched(userId: PlatformUUID, request: LinkUnmatchedTrackRequest): LinkUnmatchedTrackResult {
        val link = listenService.linkUnmatched(userId, request.songId, request.recordingMsid, request.recordingMbid)

        val songMbid = dbQuery {
            SongMusicBrainzTable
                .select(SongMusicBrainzTable.musicBrainzId)
                .where { SongMusicBrainzTable.songId eq request.songId }
                .singleOrNull()?.get(SongMusicBrainzTable.musicBrainzId)?.value
        }
        val submitted = if (songMbid != null && link.recordingMsids.isNotEmpty()) {
            listenBrainzService.submitManualMapping(userId, songMbid, link.recordingMsids)
        } else 0

        return LinkUnmatchedTrackResult(linkedListens = link.linkedListens, submittedToListenBrainz = submitted)
    }

    suspend fun stats(
        userId: PlatformUUID,
        range: StatsRange,
        timezone: String,
        topLimit: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): ListeningStats {
        val zone = runCatching { ZoneId.of(timezone) }.getOrElse { ZoneOffset.UTC }
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val rangeStartZdt = when (range) {
            StatsRange.DAY -> now.toLocalDate().atStartOfDay(zone)
            StatsRange.WEEK -> now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone)
            StatsRange.MONTH -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
            StatsRange.YEAR -> now.toLocalDate().withDayOfYear(1).atStartOfDay(zone)
            StatsRange.ALL_TIME -> null
        }
        val rangeStart = rangeStartZdt?.toInstant()?.toEpochMilli() ?: 0L
        val previousStart = when (range) {
            StatsRange.DAY -> rangeStartZdt?.minusDays(1)
            StatsRange.WEEK -> rangeStartZdt?.minusWeeks(1)
            StatsRange.MONTH -> rangeStartZdt?.minusMonths(1)
            StatsRange.YEAR -> rangeStartZdt?.minusYears(1)
            StatsRange.ALL_TIME -> null
        }?.toInstant()?.toEpochMilli()

        val pass = dbQuery { runPass(userId, rangeStart, previousStart, nowMs, zone) }

        val inRangeSongIds = (pass.songCounts.keys + pass.songPlayed.keys).filterIsInstance<SongKey.Matched>().map { it.songId }.distinct()
        val allSongIds = (pass.songFirstSeen.keys + pass.songPlayed.keys).filterIsInstance<SongKey.Matched>().map { it.songId }.distinct()
        val unmatchedArtistMbids = (pass.unmatchedArtistCounts.keys + pass.unmatchedArtistFirstSeen.keys)
            .filterIsInstance<ArtistKey.Mbid>().map { it.mbid }.distinct()
        val unmatchedReleaseMbids = (pass.unmatchedAlbumCounts.keys.filterIsInstance<AlbumKey.Mbid>().map { it.mbid } +
            pass.songDisplay.values.mapNotNull { it.releaseMbid }).distinct()
        val library = dbQuery { resolveLibrary(inRangeSongIds, allSongIds, unmatchedArtistMbids, unmatchedReleaseMbids) }

        fun canonArtist(key: ArtistKey): ArtistKey =
            (key as? ArtistKey.Mbid)?.let { m -> library.artistIdByMbid[m.mbid]?.let { ArtistKey.Matched(it) } } ?: key

        fun canonAlbum(key: AlbumKey): AlbumKey =
            (key as? AlbumKey.Mbid)?.let { m -> library.albumIdByReleaseMbid[m.mbid]?.let { AlbumKey.Matched(it) } } ?: key

        val artistCounts = LinkedHashMap<ArtistKey, Long>()
        pass.unmatchedArtistCounts.forEach { (key, count) -> artistCounts.merge(canonArtist(key), count, Long::plus) }
        val albumCounts = LinkedHashMap<AlbumKey, Long>()
        pass.unmatchedAlbumCounts.forEach { (key, count) -> albumCounts.merge(canonAlbum(key), count, Long::plus) }
        for ((key, count) in pass.songCounts) {
            if (key !is SongKey.Matched) continue
            library.songArtists[key.songId]?.forEach { artistCounts.merge(ArtistKey.Matched(it), count, Long::plus) }
            library.songMeta[key.songId]?.let { albumCounts.merge(AlbumKey.Matched(it.albumId), count, Long::plus) }
        }

        val artistPlayed = HashMap<ArtistKey, Long>()
        pass.unmatchedArtistPlayed.forEach { (key, ms) -> artistPlayed.merge(canonArtist(key), ms, Long::plus) }
        val albumPlayed = HashMap<AlbumKey, Long>()
        pass.unmatchedAlbumPlayed.forEach { (key, ms) -> albumPlayed.merge(canonAlbum(key), ms, Long::plus) }
        for ((key, ms) in pass.songPlayed) {
            if (key !is SongKey.Matched) continue
            library.songArtists[key.songId]?.forEach { artistPlayed.merge(ArtistKey.Matched(it), ms, Long::plus) }
            library.songMeta[key.songId]?.let { albumPlayed.merge(AlbumKey.Matched(it.albumId), ms, Long::plus) }
        }

        val artistFirstSeen = HashMap<ArtistKey, Long>()
        pass.unmatchedArtistFirstSeen.forEach { (key, firstSeen) -> artistFirstSeen.merge(canonArtist(key), firstSeen, ::minOf) }
        for ((key, firstSeen) in pass.songFirstSeen) {
            if (key !is SongKey.Matched) continue
            library.songArtists[key.songId]?.forEach { artistFirstSeen.merge(ArtistKey.Matched(it), firstSeen, ::minOf) }
        }

        fun songEntry(key: SongKey, count: Long): TopSongEntry = when (key) {
            is SongKey.Matched -> {
                val meta = library.songMeta[key.songId]
                val album = meta?.let { library.albumNames[it.albumId] }
                val artistName = library.creditNames[key.songId]
                    ?: library.songArtists[key.songId]
                        ?.mapNotNull { library.artistNames[it] }
                        ?.sorted()
                        ?.joinToString(", ")?.ifBlank { null }
                TopSongEntry(
                    songId = key.songId,
                    title = meta?.title ?: "",
                    artistName = artistName,
                    albumName = album,
                    coverId = meta?.coverId ?: meta?.let { library.albumCovers[it.albumId] },
                    listenCount = count,
                    listenedMs = pass.songPlayed[key] ?: 0L,
                )
            }

            else -> {
                val display = pass.songDisplay[key]
                TopSongEntry(
                    songId = null,
                    title = display?.title ?: "",
                    artistName = display?.artistName,
                    albumName = display?.albumName,
                    coverId = display?.releaseMbid?.let { library.coverByReleaseMbid[it] },
                    listenCount = count,
                    recordingMbid = (key as? SongKey.Mbid)?.mbid,
                    recordingMsid = pass.songMsid[key],
                    listenedMs = pass.songPlayed[key] ?: 0L,
                )
            }
        }

        fun artistEntry(key: ArtistKey, count: Long): TopArtistEntry = when (key) {
            is ArtistKey.Matched -> TopArtistEntry(
                artistId = key.artistId,
                name = library.artistNames[key.artistId] ?: "",
                imageId = library.artistImages[key.artistId],
                listenCount = count,
                listenedMs = artistPlayed[key] ?: 0L,
            )

            else -> TopArtistEntry(
                artistId = null,
                name = pass.artistDisplay[key] ?: "",
                imageId = null,
                listenCount = count,
                listenedMs = artistPlayed[key] ?: 0L,
            )
        }

        fun albumEntry(key: AlbumKey, count: Long): TopAlbumEntry = when (key) {
            is AlbumKey.Matched -> TopAlbumEntry(
                albumId = key.albumId,
                name = library.albumNames[key.albumId] ?: "",
                coverId = library.albumCovers[key.albumId],
                listenCount = count,
                listenedMs = albumPlayed[key] ?: 0L,
            )

            else -> TopAlbumEntry(
                albumId = null,
                name = pass.albumDisplay[key] ?: "",
                coverId = (key as? AlbumKey.Mbid)?.let { library.coverByReleaseMbid[it.mbid] },
                listenCount = count,
                listenedMs = albumPlayed[key] ?: 0L,
            )
        }

        fun <K> top(counts: Map<K, Long>, filter: (K) -> Boolean = { true }): List<Pair<K, Long>> =
            counts.entries
                .filter { filter(it.key) }
                .sortedByDescending { it.value }
                .take(topLimit)
                .map { it.key to it.value }

        val discoverySongs = top(pass.songCounts) { key -> (pass.songFirstSeen[key] ?: 0L) >= rangeStart }
        val discoveryArtists = top(artistCounts) { key -> (artistFirstSeen[key] ?: 0L) >= rangeStart }

        val comparison = previousStart?.let {
            RangeComparison(
                previousStart = it,
                previousEnd = rangeStart,
                previousCount = pass.previousCount,
                percentChange = if (pass.previousCount > 0) {
                    (pass.listenCount - pass.previousCount) * 100.0 / pass.previousCount
                } else null,
                previousListenedMs = pass.previousListenedMs,
            )
        }

        return ListeningStats(
            range = range,
            timezone = zone.id,
            rangeStart = rangeStart,
            rangeEnd = nowMs,
            listenCount = pass.listenCount,
            listenedMs = pass.listenedMs,
            comparison = comparison,
            uniqueSongs = pass.songCounts.size,
            uniqueArtists = artistCounts.size,
            uniqueAlbums = albumCounts.size,
            topSongs = top(pass.songCounts).map { (key, count) -> songEntry(key, count) },
            topArtists = top(artistCounts).map { (key, count) -> artistEntry(key, count) },
            topAlbums = top(albumCounts).map { (key, count) -> albumEntry(key, count) },
            listenClock = ListenClock(
                hourOfDay = pass.hourBuckets.toList(),
                dayOfWeek = pass.dayBuckets.toList(),
            ),
            streaks = streaks(pass.daysWithListens, now.toLocalDate().toEpochDay()),
            discoveries = Discoveries(
                songs = discoverySongs.map { (key, count) -> songEntry(key, count) },
                artists = discoveryArtists.map { (key, count) -> artistEntry(key, count) },
            ),
        )
    }

    private fun runPass(
        userId: PlatformUUID,
        rangeStart: Long,
        previousStart: Long?,
        nowMs: Long,
        zone: ZoneId,
    ): PassResult {
        val lbId = UserListenBrainzLinkTable
            .select(UserListenBrainzLinkTable.listenBrainzUserId)
            .where { UserListenBrainzLinkTable.userId eq userId }
            .singleOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

        val owner = if (lbId != null) {
            (ListenTable.userId eq userId) or (ListenTable.listenBrainzUserId eq lbId)
        } else {
            ListenTable.userId eq userId
        }

        val result = PassResult()
        var lastTs = 0L
        var lastSongId: PlatformUUID? = null
        var lastRecordingMbid: PlatformUUID? = null
        var lastIsrcs: Set<String> = emptySet()

        ListenTable
            .leftJoin(SongTable)
            .select(
                ListenTable.songId, ListenTable.listenedAt, ListenTable.recordingMbid, ListenTable.recordingMsid,
                ListenTable.isrcs, ListenTable.releaseMbid, ListenTable.artistMbids, ListenTable.trackName,
                ListenTable.artistName, ListenTable.releaseName, ListenTable.msPlayed, SongTable.duration,
            )
            .where { owner }
            .orderBy(ListenTable.listenedAt to SortOrder.ASC)
            .forEach { row ->
                val ts = row[ListenTable.listenedAt]
                val songId = row[ListenTable.songId]?.value
                val recordingMbid = row[ListenTable.recordingMbid]
                val isrcs = ListenTable.parseIsrcs(row[ListenTable.isrcs])

                val duplicatePlay = ts - lastTs <= ListenTable.DEDUP_WINDOW_MS && (
                    (songId != null && songId == lastSongId) ||
                        (recordingMbid != null && recordingMbid == lastRecordingMbid) ||
                        isrcs.any { it in lastIsrcs }
                    )
                lastTs = ts
                lastSongId = songId
                lastRecordingMbid = recordingMbid
                lastIsrcs = isrcs
                if (duplicatePlay) return@forEach

                val msPlayed = row[ListenTable.msPlayed]
                val songDuration = row.getOrNull(SongTable.duration)
                val qualified = ListenTable.isQualifiedPlay(msPlayed, songDuration)
                val playedMs = ListenTable.playedMs(msPlayed, songDuration)

                val zdt = Instant.ofEpochMilli(ts).atZone(zone)
                if (qualified) result.daysWithListens.add(zdt.toLocalDate().toEpochDay())

                val trackName = row[ListenTable.trackName]?.trim()?.ifBlank { null }
                val artistName = row[ListenTable.artistName]?.trim()?.ifBlank { null }
                val releaseName = row[ListenTable.releaseName]?.trim()?.ifBlank { null }

                val songKey = when {
                    songId != null -> SongKey.Matched(songId)
                    recordingMbid != null -> SongKey.Mbid(recordingMbid)
                    trackName != null -> SongKey.Named(trackName.lowercase(), artistName?.lowercase() ?: "")
                    else -> null
                }
                if (qualified && songKey != null) result.songFirstSeen.merge(songKey, ts, ::minOf)

                val artistKey = if (songId == null) {
                    artistKeyOf(row[ListenTable.artistMbids], artistName)
                } else null
                if (qualified && artistKey != null) result.unmatchedArtistFirstSeen.merge(artistKey, ts, ::minOf)

                if (previousStart != null && ts >= previousStart && ts < rangeStart) {
                    if (qualified) result.previousCount++
                    result.previousListenedMs += playedMs
                }
                if (ts !in rangeStart..<nowMs) return@forEach

                result.listenedMs += playedMs
                if (qualified) {
                    result.listenCount++
                    result.hourBuckets[zdt.hour]++
                    result.dayBuckets[zdt.dayOfWeek.value - 1]++
                }

                if (songKey != null) {
                    if (qualified) result.songCounts.merge(songKey, 1L, Long::plus)
                    result.songPlayed.merge(songKey, playedMs, Long::plus)
                    if (songId == null) {
                        row[ListenTable.recordingMsid]?.let { result.songMsid.putIfAbsent(songKey, it) }
                        result.songDisplay.putIfAbsent(
                            songKey,
                            UnmatchedDisplay(
                                title = trackName ?: recordingMbid?.toString() ?: "",
                                artistName = artistName,
                                albumName = releaseName,
                                releaseMbid = row[ListenTable.releaseMbid],
                            ),
                        )
                    }
                }

                if (songId == null) {
                    if (artistKey != null) {
                        if (qualified) result.unmatchedArtistCounts.merge(artistKey, 1L, Long::plus)
                        result.unmatchedArtistPlayed.merge(artistKey, playedMs, Long::plus)
                        if (artistName != null) result.artistDisplay.putIfAbsent(artistKey, artistName)
                    }
                    val albumKey = albumKeyOf(row[ListenTable.releaseMbid], releaseName)
                    if (albumKey != null) {
                        if (qualified) result.unmatchedAlbumCounts.merge(albumKey, 1L, Long::plus)
                        result.unmatchedAlbumPlayed.merge(albumKey, playedMs, Long::plus)
                        if (releaseName != null) result.albumDisplay.putIfAbsent(albumKey, releaseName)
                    }
                }
            }

        return result
    }

    private fun resolveLibrary(
        inRangeSongIds: List<PlatformUUID>,
        allSongIds: List<PlatformUUID>,
        unmatchedArtistMbids: List<PlatformUUID>,
        unmatchedReleaseMbids: List<PlatformUUID>,
    ): Library {
        val library = Library()

        unmatchedArtistMbids.chunked(CHUNK_SIZE).forEach { chunk ->
            ArtistMusicBrainzTable
                .select(ArtistMusicBrainzTable.artistId, ArtistMusicBrainzTable.musicBrainzId)
                .where { ArtistMusicBrainzTable.musicBrainzId inList chunk }
                .forEach { row ->
                    row[ArtistMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.artistIdByMbid.putIfAbsent(it, row[ArtistMusicBrainzTable.artistId].value)
                    }
                }
        }

        unmatchedReleaseMbids.chunked(CHUNK_SIZE).forEach { chunk ->
            AlbumMusicBrainzTable
                .select(AlbumMusicBrainzTable.albumId, AlbumMusicBrainzTable.musicBrainzId)
                .where { AlbumMusicBrainzTable.musicBrainzId inList chunk }
                .forEach { row ->
                    row[AlbumMusicBrainzTable.musicBrainzId]?.value?.let {
                        library.albumIdByReleaseMbid.putIfAbsent(it, row[AlbumMusicBrainzTable.albumId].value)
                    }
                }
        }

        val groupByRelease = HashMap<PlatformUUID, PlatformUUID>()
        unmatchedReleaseMbids.chunked(CHUNK_SIZE).forEach { chunk ->
            MBReleaseTable
                .select(MBReleaseTable.id, MBReleaseTable.releaseGroupId)
                .where { MBReleaseTable.id inList chunk }
                .forEach { row ->
                    row[MBReleaseTable.releaseGroupId]?.value?.let { groupByRelease[row[MBReleaseTable.id].value] = it }
                }
        }
        val coverByGroup = HashMap<PlatformUUID, PlatformUUID>()
        groupByRelease.values.distinct().chunked(CHUNK_SIZE).forEach { chunk ->
            MBReleaseGroupCoverTable
                .select(MBReleaseGroupCoverTable.releaseGroupId, MBReleaseGroupCoverTable.imageId)
                .where { MBReleaseGroupCoverTable.releaseGroupId inList chunk }
                .forEach { row ->
                    row[MBReleaseGroupCoverTable.imageId]?.value?.let {
                        coverByGroup[row[MBReleaseGroupCoverTable.releaseGroupId].value] = it
                    }
                }
        }
        for ((releaseMbid, groupId) in groupByRelease) {
            coverByGroup[groupId]?.let { library.coverByReleaseMbid[releaseMbid] = it }
        }

        allSongIds.chunked(CHUNK_SIZE).forEach { chunk ->
            SongArtistTable
                .select(SongArtistTable.songId, SongArtistTable.artistId)
                .where { SongArtistTable.songId inList chunk }
                .orderBy(SongArtistTable.artistId)
                .forEach {
                    library.songArtists.getOrPut(it[SongArtistTable.songId].value) { mutableListOf() }
                        .add(it[SongArtistTable.artistId].value)
                }
        }

        inRangeSongIds.chunked(CHUNK_SIZE).forEach { chunk ->
            SongTable
                .select(SongTable.id, SongTable.title, SongTable.albumId, SongTable.cover)
                .where { SongTable.id inList chunk }
                .forEach {
                    library.songMeta[it[SongTable.id].value] = SongMeta(
                        title = it[SongTable.title],
                        albumId = it[SongTable.albumId].value,
                        coverId = it[SongTable.cover]?.value,
                    )
                }
        }

        val songToRecording = HashMap<PlatformUUID, PlatformUUID>()
        inRangeSongIds.chunked(CHUNK_SIZE).forEach { chunk ->
            SongMusicBrainzTable
                .select(SongMusicBrainzTable.songId, SongMusicBrainzTable.musicBrainzId)
                .where { SongMusicBrainzTable.songId inList chunk }
                .forEach { row ->
                    row[SongMusicBrainzTable.musicBrainzId]?.value?.let {
                        songToRecording[row[SongMusicBrainzTable.songId].value] = it
                    }
                }
        }
        val creditsByRecording = HashMap<PlatformUUID, StringBuilder>()
        songToRecording.values.distinct().chunked(CHUNK_SIZE).forEach { chunk ->
            MBRecordingArtistCreditTable
                .select(
                    MBRecordingArtistCreditTable.recordingId,
                    MBRecordingArtistCreditTable.name,
                    MBRecordingArtistCreditTable.joinPhrase,
                )
                .where { MBRecordingArtistCreditTable.recordingId inList chunk }
                .orderBy(
                    MBRecordingArtistCreditTable.recordingId to SortOrder.ASC,
                    MBRecordingArtistCreditTable.position to SortOrder.ASC,
                )
                .forEach {
                    creditsByRecording.getOrPut(it[MBRecordingArtistCreditTable.recordingId].value) { StringBuilder() }
                        .append(it[MBRecordingArtistCreditTable.name])
                        .append(it[MBRecordingArtistCreditTable.joinPhrase] ?: "")
                }
        }
        for ((songId, recordingId) in songToRecording) {
            creditsByRecording[recordingId]?.toString()?.trim()?.ifBlank { null }?.let { library.creditNames[songId] = it }
        }

        val artistIds = (inRangeSongIds.flatMap { library.songArtists[it].orEmpty() } + library.artistIdByMbid.values).distinct()
        artistIds.chunked(CHUNK_SIZE).forEach { chunk ->
            ArtistTable
                .select(ArtistTable.id, ArtistTable.name, ArtistTable.image)
                .where { ArtistTable.id inList chunk }
                .forEach {
                    val id = it[ArtistTable.id].value
                    library.artistNames[id] = it[ArtistTable.name]
                    library.artistImages[id] = it[ArtistTable.image]?.value
                }
        }

        val albumIds = (library.songMeta.values.map { it.albumId } + library.albumIdByReleaseMbid.values).distinct()
        albumIds.chunked(CHUNK_SIZE).forEach { chunk ->
            AlbumTable
                .select(AlbumTable.id, AlbumTable.name, AlbumTable.cover)
                .where { AlbumTable.id inList chunk }
                .forEach {
                    val id = it[AlbumTable.id].value
                    library.albumNames[id] = it[AlbumTable.name]
                    library.albumCovers[id] = it[AlbumTable.cover]?.value
                }
        }

        return library
    }

    private fun artistKeyOf(artistMbids: String?, artistName: String?): ArtistKey? {
        val mbid = artistMbids?.split(',')?.firstNotNullOfOrNull {
            runCatching { PlatformUUID.fromString(it.trim()) }.getOrNull()
        }
        return when {
            mbid != null -> ArtistKey.Mbid(mbid)
            artistName != null -> ArtistKey.Named(artistName.lowercase())
            else -> null
        }
    }

    private fun albumKeyOf(releaseMbid: PlatformUUID?, releaseName: String?): AlbumKey? = when {
        releaseMbid != null -> AlbumKey.Mbid(releaseMbid)
        releaseName != null -> AlbumKey.Named(releaseName.lowercase())
        else -> null
    }

    private fun streaks(daysWithListens: Set<Long>, todayEpochDay: Long): ListeningStreaks {
        if (daysWithListens.isEmpty()) return ListeningStreaks(0, 0)

        val sorted = daysWithListens.toLongArray().also { it.sort() }
        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }

        var current = 0
        var day = if (todayEpochDay in daysWithListens) todayEpochDay else todayEpochDay - 1
        while (day in daysWithListens) {
            current++
            day--
        }

        return ListeningStreaks(currentStreakDays = current, longestStreakDays = longest)
    }

    private sealed interface SongKey {
        data class Matched(val songId: PlatformUUID) : SongKey
        data class Mbid(val mbid: PlatformUUID) : SongKey
        data class Named(val track: String, val artist: String) : SongKey
    }

    private sealed interface ArtistKey {
        data class Matched(val artistId: PlatformUUID) : ArtistKey
        data class Mbid(val mbid: PlatformUUID) : ArtistKey
        data class Named(val name: String) : ArtistKey
    }

    private sealed interface AlbumKey {
        data class Matched(val albumId: PlatformUUID) : AlbumKey
        data class Mbid(val mbid: PlatformUUID) : AlbumKey
        data class Named(val name: String) : AlbumKey
    }

    private data class UnmatchedDisplay(
        val title: String,
        val artistName: String?,
        val albumName: String?,
        val releaseMbid: PlatformUUID?,
    )

    private data class SongMeta(val title: String, val albumId: PlatformUUID, val coverId: PlatformUUID?)

    private class PassResult {
        var listenCount = 0L
        var previousCount = 0L
        var listenedMs = 0L
        var previousListenedMs = 0L
        val hourBuckets = LongArray(24)
        val dayBuckets = LongArray(7)
        val songCounts = LinkedHashMap<SongKey, Long>()
        val songPlayed = HashMap<SongKey, Long>()
        val songFirstSeen = HashMap<SongKey, Long>()
        val unmatchedArtistCounts = LinkedHashMap<ArtistKey, Long>()
        val unmatchedAlbumCounts = LinkedHashMap<AlbumKey, Long>()
        val unmatchedArtistPlayed = HashMap<ArtistKey, Long>()
        val unmatchedAlbumPlayed = HashMap<AlbumKey, Long>()
        val unmatchedArtistFirstSeen = HashMap<ArtistKey, Long>()
        val songDisplay = HashMap<SongKey, UnmatchedDisplay>()
        val songMsid = HashMap<SongKey, PlatformUUID>()
        val artistDisplay = HashMap<ArtistKey, String>()
        val albumDisplay = HashMap<AlbumKey, String>()
        val daysWithListens = HashSet<Long>()
    }

    private class Library {
        val songMeta = HashMap<PlatformUUID, SongMeta>()
        val songArtists = HashMap<PlatformUUID, MutableList<PlatformUUID>>()
        val creditNames = HashMap<PlatformUUID, String>()
        val artistNames = HashMap<PlatformUUID, String>()
        val artistImages = HashMap<PlatformUUID, PlatformUUID?>()
        val albumNames = HashMap<PlatformUUID, String>()
        val albumCovers = HashMap<PlatformUUID, PlatformUUID?>()
        val artistIdByMbid = HashMap<PlatformUUID, PlatformUUID>()
        val albumIdByReleaseMbid = HashMap<PlatformUUID, PlatformUUID>()
        val coverByReleaseMbid = HashMap<PlatformUUID, PlatformUUID>()
    }

    private companion object {
        const val CHUNK_SIZE = 1000
    }
}

class RpcListeningStatsService(
    private val user: User,
    private val service: ListeningStatsService,
) : IListeningStatsService {
    override suspend fun getStats(range: StatsRange, timezone: String, topLimit: Int): ListeningStats =
        service.stats(user.id, range, timezone, topLimit.coerceIn(1, 100))

    override suspend fun linkUnmatchedTrack(request: LinkUnmatchedTrackRequest): LinkUnmatchedTrackResult =
        service.linkUnmatched(user.id, request)
}
