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

    private data class NowPlayingEntry(val song: UserSong, val startedAt: Long)

    private val nowPlaying = ConcurrentHashMap<PlatformUUID, NowPlayingEntry>()
    private val timers = ConcurrentHashMap<PlatformUUID, Job>()
    private val generation = ConcurrentHashMap<PlatformUUID, Long>()

    private val nowPlayingChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    suspend fun setNowPlaying(userId: PlatformUUID, songId: PlatformUUID) {
        val song = songService.byIds(listOf(songId), userId).firstOrNull() ?: return
        val myGen = generation.merge(userId, 1L, Long::plus)!!
        timers.remove(userId)?.cancel()
        val startedAt = System.currentTimeMillis()
        nowPlaying[userId] = NowPlayingEntry(song, startedAt)
        nowPlayingChanges.tryEmit(Unit)
        hooks.emit(HookEvent.NowPlayingChanged(userId, songId, myGen, startedAt))

        if (song.duration > 0) {
            timers[userId] = serviceScope.launch {
                delay(song.duration.milliseconds)
                if (generation[userId] == myGen) {
                    nowPlaying.remove(userId)
                    nowPlayingChanges.tryEmit(Unit)
                    hooks.emit(HookEvent.NowPlayingChanged(userId, null, myGen, System.currentTimeMillis()))
                }
            }
        }
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
        val current = nowPlaying[userId]?.let { NowPlaying(song = it.song, startedAt = it.startedAt) }
        return RecentListens(nowPlaying = current, recent = recent)
    }
}

class RpcScrobbleService(
    private val user: User,
    private val service: ScrobbleService,
) : IScrobbleService {
    override suspend fun nowPlaying(songId: PlatformUUID) = service.setNowPlaying(user.id, songId)

    override suspend fun clearNowPlaying() = service.clearNowPlaying(user.id)

    override suspend fun listened(request: ScrobbleRequest) = service.listened(user.id, request)

    override fun recentListensFlow(limit: Int): Flow<RecentListens> = service.recentListensFlow(user.id, limit)
}
