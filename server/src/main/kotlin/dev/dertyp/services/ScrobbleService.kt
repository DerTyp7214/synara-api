package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class ScrobbleService : Service() {
    private val listenService by inject<ListenService>()
    private val songService by inject<SongService>()
    private val hooks by inject<HookBus>()

    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private data class NowPlayingEntry(
        val song: UserSong,
        val firstStartedAt: Long,
        val anchorAt: Long,
        val positionMs: Long,
        val playing: Boolean,
    )

    private val nowPlaying = ConcurrentHashMap<PlatformUUID, NowPlayingEntry>()
    private val timers = ConcurrentHashMap<PlatformUUID, Job>()
    private val generation = ConcurrentHashMap<PlatformUUID, Long>()

    private val nowPlayingChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    internal var playingLeaseMs: Long = PLAYING_LEASE_MS
    internal var pausedLeaseMs: Long = PAUSED_LEASE_MS

    suspend fun setNowPlaying(userId: PlatformUUID, songId: PlatformUUID) {
        val previous = nowPlaying[userId]?.takeIf { it.song.id == songId }
        val positionMs = previous?.let { it.positionMs + if (it.playing) (System.currentTimeMillis() - it.anchorAt).coerceAtLeast(0) else 0L } ?: 0L
        reportPlayback(userId, PlaybackReport(songId, positionMs = positionMs))
    }

    suspend fun reportPlayback(userId: PlatformUUID, report: PlaybackReport): Long {
        val now = System.currentTimeMillis()
        val previous = nowPlaying[userId]?.takeIf { it.song.id == report.songId }
        val song = previous?.song ?: songService.byIds(listOf(report.songId), userId).firstOrNull() ?: return now
        val myGen = generation.merge(userId, 1L, Long::plus)!!
        timers.remove(userId)?.cancel()
        val positionMs = correctedPosition(report, now)
        nowPlaying[userId] = NowPlayingEntry(song, previous?.firstStartedAt ?: now, now, positionMs, report.playing)
        if (previous == null) nowPlayingChanges.tryEmit(Unit)
        hooks.emit(HookEvent.NowPlayingChanged(userId, report.songId, myGen, now, positionMs, report.playing))

        val remaining = if (song.duration > 0) song.duration - positionMs else Long.MAX_VALUE
        val lease = when {
            !report.playing -> pausedLeaseMs
            remaining <= 0 -> END_GRACE_MS
            else -> minOf(remaining, playingLeaseMs)
        }
        timers[userId] = serviceScope.launch {
            delay(lease.milliseconds)
            if (generation[userId] == myGen && nowPlaying.containsKey(userId)) {
                nowPlaying.remove(userId)
                nowPlayingChanges.tryEmit(Unit)
                hooks.emit(HookEvent.NowPlayingChanged(userId, null, myGen, System.currentTimeMillis()))
            }
        }
        return now
    }

    private fun correctedPosition(report: PlaybackReport, now: Long): Long {
        val delay = report.sentAt?.let { now - it } ?: 0L
        val corrected = if (report.playing && delay in 0..MAX_REPORT_DELAY_MS) report.positionMs + delay else report.positionMs
        return corrected.coerceAtLeast(0)
    }

    suspend fun clearNowPlaying(userId: PlatformUUID) {
        val myGen = generation.merge(userId, 1L, Long::plus)!!
        timers.remove(userId)?.cancel()
        nowPlaying.remove(userId)
        nowPlayingChanges.tryEmit(Unit)
        hooks.emit(HookEvent.NowPlayingChanged(userId, null, myGen, System.currentTimeMillis()))
    }

    suspend fun listened(userId: PlatformUUID, request: ScrobbleRequest) {
        listenService.ingestLocal(
            userId = userId,
            songId = request.songId,
            listenedAtMs = request.listenedAt ?: System.currentTimeMillis(),
            msPlayed = request.msPlayed,
        )
    }

    @OptIn(FlowPreview::class)
    fun recentListensFlow(userId: PlatformUUID, limit: Int): Flow<RecentListens> =
        merge(listenService.listenChanges, nowPlayingChanges)
            .onStart { emit(Unit) }
            .debounce(100.milliseconds)
            .map { buildRecentListens(userId, limit) }
            .distinctUntilChanged()

    private suspend fun buildRecentListens(userId: PlatformUUID, limit: Int): RecentListens {
        val recent = listenService.recentListens(userId, limit)
        val current = nowPlaying[userId]?.let { NowPlaying(song = it.song, startedAt = it.firstStartedAt) }
        return RecentListens(nowPlaying = current, recent = recent)
    }

    companion object {
        const val MAX_REPORT_DELAY_MS = 2_000L
        const val PLAYING_LEASE_MS = 180_000L
        const val PAUSED_LEASE_MS = 1_800_000L
        const val END_GRACE_MS = 5_000L
    }
}

class RpcScrobbleService(
    private val user: User,
    private val service: ScrobbleService,
) : IScrobbleService {
    override suspend fun nowPlaying(songId: PlatformUUID) = service.setNowPlaying(user.id, songId)

    override suspend fun reportPlayback(report: PlaybackReport): Long = service.reportPlayback(user.id, report)

    override suspend fun clearNowPlaying() = service.clearNowPlaying(user.id)

    override suspend fun listened(request: ScrobbleRequest) = service.listened(user.id, request)

    override fun recentListensFlow(limit: Int): Flow<RecentListens> = service.recentListensFlow(user.id, limit)
}
