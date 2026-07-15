package dev.dertyp.services

import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.db.ListenTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserListenBrainzLinkTable
import dev.dertyp.db.UserSongTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayDeque
import kotlin.collections.LinkedHashSet
import kotlin.collections.List
import kotlin.collections.Set
import kotlin.collections.emptyList
import kotlin.collections.emptySet
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.ifEmpty
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.collections.set
import kotlin.collections.shuffled
import kotlin.collections.take
import kotlin.collections.toHashSet
import kotlin.collections.toList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

typealias RadioSongSupplier = suspend (exclude: Set<UUID>, limit: Int) -> List<UUID>

class RadioService : Service() {
    private val recommendations by inject<RecommendationServingService>()
    private val songService by inject<SongService>()

    private val sessions = ConcurrentHashMap<UUID, RadioSessionState>()

    class RadioSessionState(
        val userId: UUID,
        val type: RadioType,
        val seed: RadioSeed?,
        val poolSource: RadioSongSupplier? = null,
        val discovery: Boolean = false,
    ) {
        val played = LinkedHashSet<UUID>()
        val queue = ArrayDeque<UUID>()
        val mutex = Mutex()

        @Volatile
        var lastAccessed = System.currentTimeMillis()

        fun remember(id: UUID) {
            played.add(id)
            while (played.size > MAX_PLAYED_HISTORY) {
                val oldest = played.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    fun cleanupExpiredSessions(): Int {
        val cutoff = System.currentTimeMillis() - SESSION_TTL.inWholeMilliseconds
        val before = sessions.size
        sessions.values.removeIf { it.lastAccessed < cutoff }
        return before - sessions.size
    }

    fun createSession(userId: UUID, type: RadioType, seed: RadioSeed?): UUID {
        val id = UUID.randomUUID()
        sessions[id] = RadioSessionState(userId, type, seed?.takeIf { !it.isEmpty() })
        return id
    }

    fun createChannelSession(userId: UUID, discovery: Boolean, poolSource: RadioSongSupplier): UUID {
        val id = UUID.randomUUID()
        sessions[id] = RadioSessionState(userId, RadioType.RANDOM, seed = null, poolSource = poolSource, discovery = discovery)
        return id
    }

    fun getSession(sessionId: UUID, userId: UUID): RadioSessionState {
        val session = sessions[sessionId] ?: throw IllegalArgumentException("Unknown radio session")
        require(session.userId == userId) { "Radio session does not belong to this user" }
        return session
    }

    fun radioFlow(sessionId: UUID, userId: UUID): Flow<UUID> {
        val session = getSession(sessionId, userId)
        return flow {
            while (true) emit(nextSongId(session))
        }
    }

    suspend fun nextSongId(session: RadioSessionState): UUID = session.mutex.withLock {
        session.lastAccessed = System.currentTimeMillis()
        if (session.queue.size < QUEUE_LOW_WATERMARK) refill(session)
        if (session.queue.isEmpty()) {
            session.played.clear()
            refill(session)
        }
        val id = session.queue.removeFirstOrNull()
            ?: throw IllegalStateException("Radio has no songs to play")
        session.remember(id)
        id
    }

    private suspend fun refill(session: RadioSessionState) {
        val source = session.poolSource
        val batch = when {
            source != null && session.discovery -> expand(source(emptySet(), SEED_LIMIT), session.userId)
            source != null -> source(session.played, BATCH_SIZE)
            session.seed != null -> seedBatch(session)
            session.type == RadioType.RANDOM -> emptyList()
            else -> historyBatch(session)
        }.ifEmpty {
            source?.invoke(emptySet(), BATCH_SIZE) ?: randomIds(session.played, BATCH_SIZE)
        }

        for (id in batch) {
            if (id !in session.played && id !in session.queue) session.queue.addLast(id)
        }
    }

    private suspend fun seedBatch(session: RadioSessionState): List<UUID> {
        val seed = session.seed!!
        val seedIds = LinkedHashSet<UUID>()
        seedIds.addAll(seed.songIds)
        seed.playlistId?.let { seedIds.addAll(songService.songIdsByPlaylist(it).toList()) }
        seed.albumId?.let { seedIds.addAll(songService.songIdsByAlbum(it).toList()) }
        seed.artistId?.let { seedIds.addAll(songService.songIdsByArtist(it).toList()) }
        return expand(seedIds.toList(), session.userId)
    }

    private suspend fun historyBatch(session: RadioSessionState): List<UUID> {
        val cutoff = System.currentTimeMillis() - session.type.window().inWholeMilliseconds
        return expand(historySeeds(session.userId, cutoff).toList(), session.userId)
    }

    private suspend fun expand(seedIds: List<UUID>, userId: UUID): List<UUID> {
        val seeds = seedIds.shuffled().take(SEED_LIMIT)
        if (seeds.isEmpty()) return emptyList()
        return recommendations.similarSongs(seeds, userId, BATCH_SIZE * 3).map { it.id }.shuffled()
    }

    private suspend fun randomIds(exclude: Set<UUID>, limit: Int): List<UUID> = dbQuery {
        SongTable.select(SongTable.id)
            .orderBy(Random())
            .limit(limit * 2)
            .map { it[SongTable.id].value }
            .filter { it !in exclude }
            .take(limit)
    }

    private suspend fun historySeeds(userId: UUID, cutoff: Long): Set<UUID> = dbQuery {
        val account = UserListenBrainzLinkTable
            .select(UserListenBrainzLinkTable.listenBrainzUserId)
            .where { UserListenBrainzLinkTable.userId eq userId }
            .firstOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

        val owner = if (account != null) {
            (ListenTable.userId eq userId) or (ListenTable.listenBrainzUserId eq account)
        } else {
            ListenTable.userId eq userId
        }
        val recent = ListenTable.select(ListenTable.songId)
            .where { owner }
            .andWhere { ListenTable.listenedAt greater cutoff }
            .andWhere { ListenTable.songId.isNotNull() }
            .mapNotNull { it[ListenTable.songId]?.value }
            .toHashSet()

        recent.ifEmpty {
            UserSongTable.select(UserSongTable.songId)
                .where { UserSongTable.userId eq userId }
                .andWhere { UserSongTable.isFavourite eq true }
                .map { it[UserSongTable.songId].value }
                .toHashSet()
        }
    }

    private fun RadioType.window(): Duration = when (this) {
        RadioType.LAST_WEEK -> 7.days
        RadioType.LAST_MONTH -> 30.days
        RadioType.LAST_YEAR -> 365.days
        RadioType.RANDOM -> 0.days
    }

    companion object {
        private const val BATCH_SIZE = 100
        private const val SEED_LIMIT = 20
        private const val QUEUE_LOW_WATERMARK = 20
        private const val MAX_PLAYED_HISTORY = 2000
        private val SESSION_TTL = 24.hours
    }
}
