package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.db.dbQuery
import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.data.ArtistTopSong
import dev.dertyp.data.ArtistTopSongOrder
import dev.dertyp.db.*
import dev.dertyp.platformUUIDFromString
import dev.dertyp.services.sync.ListenBrainzService
import dev.dertyp.services.sync.resolveRecordingMatches
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ArtistTopSongsService : Service() {
    private val listenBrainzService by inject<ListenBrainzService>()
    private val listeningStatsService by inject<ListeningStatsService>()
    private val songService by inject<SongService>()
    private val fetchRetryAt = ConcurrentHashMap<PlatformUUID, Long>()

    suspend fun topSongs(
        userId: PlatformUUID,
        artistId: PlatformUUID,
        limit: Int,
        order: ArtistTopSongOrder,
    ): List<ArtistTopSong> {
        val artistMbid = dbQuery {
            ArtistMusicBrainzTable
                .select(ArtistMusicBrainzTable.musicBrainzId)
                .where { ArtistMusicBrainzTable.artistId eq artistId }
                .singleOrNull()?.get(ArtistMusicBrainzTable.musicBrainzId)?.value
        }
        val recordings = artistMbid?.let { recordingPopularity(userId, it) }.orEmpty()

        val global = HashMap<PlatformUUID, RecordingPopularity>()
        if (recordings.isNotEmpty()) {
            val match = dbQuery { resolveRecordingMatches(recordings.map { it.recordingMbid }, emptyList()) }
            recordings.forEach { recording ->
                match.songForRecording(recording.recordingMbid)?.let { songId ->
                    global.merge(songId, recording) { a, b -> if (b.listenCount > a.listenCount) b else a }
                }
            }
        }

        val artistSongs = SongTable
            .select(SongTable.id)
            .where {
                SongTable.id inSubQuery SongArtistTable
                    .select(SongArtistTable.songId)
                    .where { SongArtistTable.artistId eq artistId }
            }
            .orWhere {
                SongTable.albumId inSubQuery AlbumArtistTable
                    .select(AlbumArtistTable.albumId)
                    .where { AlbumArtistTable.artistId eq artistId }
            }
        val artistFilter = ListenTable.songId inSubQuery artistSongs
        val songFilter = if (global.isEmpty()) artistFilter else artistFilter or (ListenTable.songId inList global.keys)
        val own = listeningStatsService.songListenCounts(userId, songFilter)

        val globalCount = { songId: PlatformUUID -> global[songId]?.listenCount ?: -1L }
        val ownCount = { songId: PlatformUUID -> own[songId] ?: 0L }
        val comparator = when (order) {
            ArtistTopSongOrder.GLOBAL_LISTENS -> compareByDescending(globalCount).thenByDescending(ownCount)
            ArtistTopSongOrder.OWN_LISTENS -> compareByDescending(ownCount).thenByDescending(globalCount)
        }.thenBy { it.toString() }

        val rankedIds = (global.keys + own.filterValues { it > 0 }.keys)
            .sortedWith(comparator)
            .take(limit)
        if (rankedIds.isEmpty()) return emptyList()

        return songService.byIds(rankedIds, userId).map { song ->
            val popularity = global[song.id]
            ArtistTopSong(
                song = song,
                globalListenCount = popularity?.listenCount,
                globalUserCount = popularity?.userCount,
                ownListenCount = ownCount(song.id),
            )
        }
    }

    private suspend fun recordingPopularity(userId: PlatformUUID, artistMbid: PlatformUUID): List<RecordingPopularity> {
        val fetchedAt = dbQuery {
            MBArtistTopRecordingFetchTable
                .select(MBArtistTopRecordingFetchTable.fetchedAt)
                .where { MBArtistTopRecordingFetchTable.artistId eq artistMbid }
                .singleOrNull()?.get(MBArtistTopRecordingFetchTable.fetchedAt)
        }
        val now = System.currentTimeMillis()
        val stale = fetchedAt == null || now - fetchedAt >= CACHE_TTL_MS
        val token = if (stale && (fetchRetryAt[artistMbid] ?: 0L) <= now) listenBrainzService.apiToken(userId) else null
        if (token != null) {
            val fetched = listenBrainzService.topRecordingsForArtist(artistMbid, token)
            if (fetched != null) {
                fetchRetryAt.remove(artistMbid)
                val recordings = fetched.mapNotNull { recording ->
                    val mbid = recording.recordingMbid?.let { runCatching { platformUUIDFromString(it) }.getOrNull() }
                    mbid?.let { RecordingPopularity(it, recording.totalListenCount, recording.totalUserCount) }
                }.distinctBy { it.recordingMbid }
                runCatchingCancellable { dbQuery { storeRecordings(artistMbid, recordings, now) } }
                    .onFailure { logger.error("Storing the top recordings of artist $artistMbid failed", it) }
                return recordings
            }
            fetchRetryAt[artistMbid] = now + FETCH_BACKOFF_MS
            logger.warn("Fetching the top recordings of artist $artistMbid failed, serving the cached ones")
        }
        return dbQuery {
            MBArtistTopRecordingTable
                .selectAll()
                .where { MBArtistTopRecordingTable.artistId eq artistMbid }
                .map {
                    RecordingPopularity(
                        recordingMbid = it[MBArtistTopRecordingTable.recordingMbid],
                        listenCount = it[MBArtistTopRecordingTable.listenCount],
                        userCount = it[MBArtistTopRecordingTable.userCount],
                    )
                }
        }
    }

    private fun storeRecordings(artistMbid: PlatformUUID, recordings: List<RecordingPopularity>, now: Long) {
        MBArtistTopRecordingTable.deleteWhere { MBArtistTopRecordingTable.artistId eq artistMbid }
        MBArtistTopRecordingTable.batchInsert(recordings, shouldReturnGeneratedValues = false) { recording ->
            this[MBArtistTopRecordingTable.artistId] = artistMbid
            this[MBArtistTopRecordingTable.recordingMbid] = recording.recordingMbid
            this[MBArtistTopRecordingTable.listenCount] = recording.listenCount
            this[MBArtistTopRecordingTable.userCount] = recording.userCount
        }
        MBArtistTopRecordingFetchTable.upsert {
            it[MBArtistTopRecordingFetchTable.artistId] = artistMbid
            it[MBArtistTopRecordingFetchTable.fetchedAt] = now
        }
    }

    private data class RecordingPopularity(
        val recordingMbid: PlatformUUID,
        val listenCount: Long,
        val userCount: Long,
    )

    private companion object {
        val CACHE_TTL_MS = 7.days.inWholeMilliseconds
        val FETCH_BACKOFF_MS = 1.hours.inWholeMilliseconds
    }
}
