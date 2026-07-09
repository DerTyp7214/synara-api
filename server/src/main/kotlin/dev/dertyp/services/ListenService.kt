package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ListenedSong
import dev.dertyp.db.ListenSource
import dev.dertyp.db.ListenTable
import dev.dertyp.db.UserListenBrainzLinkTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.HookBus
import dev.dertyp.plugins.HookEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import kotlin.math.abs

data class IncomingListen(
    val listenedAtMs: Long,
    val songId: PlatformUUID? = null,
    val recordingMbid: PlatformUUID? = null,
    val releaseMbid: PlatformUUID? = null,
    val artistMbids: String? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val releaseName: String? = null,
    val msPlayed: Long? = null,
)

class ListenService : Service() {
    private val hooks by inject<HookBus>()
    private val songService by inject<SongService>()

    private val _listenChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val listenChanges: SharedFlow<Unit> = _listenChanges.asSharedFlow()

    suspend fun ingestListenBrainz(listenBrainzUserId: PlatformUUID, listens: List<IncomingListen>): Int {
        if (listens.isEmpty()) return 0

        dbQuery {
            ListenTable.batchInsert(listens, ignore = true) { listen ->
                this[ListenTable.listenBrainzUserId] = listenBrainzUserId
                this[ListenTable.songId] = listen.songId
                this[ListenTable.recordingMbid] = listen.recordingMbid
                this[ListenTable.releaseMbid] = listen.releaseMbid
                this[ListenTable.artistMbids] = listen.artistMbids
                this[ListenTable.trackName] = listen.trackName
                this[ListenTable.artistName] = listen.artistName
                this[ListenTable.releaseName] = listen.releaseName
                this[ListenTable.listenedAt] = listen.listenedAtMs
                this[ListenTable.listenSource] = ListenSource.LISTENBRAINZ
                this[ListenTable.msPlayed] = listen.msPlayed
            }
        }

        _listenChanges.tryEmit(Unit)
        hooks.emit(HookEvent.ListenIngested(listenBrainzUserId, listens.size))
        return listens.size
    }

    suspend fun ingestLocal(userId: PlatformUUID, songId: PlatformUUID, listenedAtMs: Long, msPlayed: Long?) {
        dbQuery {
            ListenTable.insert {
                it[ListenTable.userId] = userId
                it[ListenTable.songId] = songId
                it[ListenTable.listenedAt] = listenedAtMs
                it[ListenTable.listenSource] = ListenSource.LOCAL
                it[ListenTable.msPlayed] = msPlayed
            }
        }
        _listenChanges.tryEmit(Unit)
    }

    suspend fun recentListens(userId: PlatformUUID, limit: Int): List<ListenedSong> {
        val capped = limit.coerceIn(1, 1000)

        val rows = dbQuery {
            val lbId = UserListenBrainzLinkTable
                .select(UserListenBrainzLinkTable.listenBrainzUserId)
                .where { UserListenBrainzLinkTable.userId eq userId }
                .singleOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

            val owner = if (lbId != null) {
                (ListenTable.userId eq userId) or (ListenTable.listenBrainzUserId eq lbId)
            } else {
                ListenTable.userId eq userId
            }

            ListenTable
                .select(ListenTable.songId, ListenTable.listenedAt)
                .where { owner }
                .andWhere { ListenTable.songId.isNotNull() }
                .orderBy(ListenTable.listenedAt to SortOrder.DESC)
                .limit((capped * 2).coerceAtMost(1000))
                .map { it[ListenTable.songId]!!.value to it[ListenTable.listenedAt] }
        }
        if (rows.isEmpty()) return emptyList()

        val kept = ArrayList<Pair<PlatformUUID, Long>>()
        for ((songId, at) in rows) {
            val duplicatePlay = kept.any { it.first == songId && abs(it.second - at) <= ListenTable.DEDUP_WINDOW_MS }
            if (!duplicatePlay) kept.add(songId to at)
            if (kept.size >= capped) break
        }

        val songs = songService.byIds(kept.map { it.first }.distinct(), userId).associateBy { it.id }
        return kept.mapNotNull { (songId, at) -> songs[songId]?.let { ListenedSong(song = it, listenedAt = at) } }
    }
}
