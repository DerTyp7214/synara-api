package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.MoodSummary
import dev.dertyp.data.RecommendationWindow
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.db.ListenTable
import dev.dertyp.db.SongEmbeddingTable
import dev.dertyp.db.UserListenBrainzLinkTable
import dev.dertyp.db.UserSongTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class RecommendationServingService : Service() {
    private val songService by inject<SongService>()

    private class Index(
        val stamp: Long,
        val ids: List<PlatformUUID>,
        val vectors: List<FloatArray>,
        val pos: Map<PlatformUUID, Int>,
    )

    @Volatile
    private var cached: Index? = null
    private val mutex = Mutex()

    private suspend fun index(): Index {
        val maxExpr = SongEmbeddingTable.updatedAt.max()
        val stamp = dbQuery {
            SongEmbeddingTable.select(maxExpr).firstOrNull()?.get(maxExpr) ?: 0L
        }
        cached?.let { if (it.stamp == stamp) return it }
        return mutex.withLock {
            cached?.let { if (it.stamp == stamp) return it }
            loadIndex(stamp).also { cached = it }
        }
    }

    private suspend fun loadIndex(stamp: Long): Index = dbQuery {
        val ids = ArrayList<PlatformUUID>()
        val vectors = ArrayList<FloatArray>()
        SongEmbeddingTable.select(SongEmbeddingTable.songId, SongEmbeddingTable.vector).forEach { row ->
            ids.add(row[SongEmbeddingTable.songId].value)
            vectors.add(unit(unpack(row[SongEmbeddingTable.vector])))
        }
        val pos = HashMap<PlatformUUID, Int>(ids.size)
        ids.forEachIndexed { i, id -> pos[id] = i }
        Index(stamp, ids, vectors, pos)
    }

    suspend fun similarSongs(seedIds: List<PlatformUUID>, userId: PlatformUUID, limit: Int): List<UserSong> {
        val idx = index()
        if (idx.ids.isEmpty()) return emptyList()
        val query = seedVector(idx, seedIds) ?: return emptyList()
        return rank(idx, query, seedIds.toHashSet(), limit, userId)
    }

    suspend fun mix(userId: PlatformUUID, window: RecommendationWindow, limit: Int): List<UserSong> {
        val idx = index()
        if (idx.ids.isEmpty()) return emptyList()

        val cutoff = System.currentTimeMillis() - window.duration().inWholeMilliseconds
        val seeds = dbQuery {
            val account = UserListenBrainzLinkTable
                .select(UserListenBrainzLinkTable.listenBrainzUserId)
                .where { UserListenBrainzLinkTable.userId eq userId }
                .firstOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value

            val recent = if (account != null) {
                ListenTable.select(ListenTable.songId)
                    .where { (ListenTable.listenBrainzUserId eq account) and (ListenTable.listenedAt greater cutoff) }
                    .map { it[ListenTable.songId].value }
                    .toHashSet()
            } else emptySet()

            recent.ifEmpty {
                UserSongTable.select(UserSongTable.songId)
                    .where { (UserSongTable.userId eq userId) and (UserSongTable.isFavourite eq true) }
                    .map { it[UserSongTable.songId].value }
                    .toHashSet()
            }
        }

        val query = seedVector(idx, seeds.toList()) ?: return emptyList()
        return rank(idx, query, seeds, limit, userId)
    }

    suspend fun moodPlaylist(mood: String, userId: PlatformUUID, limit: Int): List<UserSong> {
        val ids = dbQuery {
            SongEmbeddingTable.select(SongEmbeddingTable.songId)
                .where { SongEmbeddingTable.mood eq mood }
                .limit(limit)
                .map { it[SongEmbeddingTable.songId].value }
        }
        val byId = songService.byIds(ids, userId).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    suspend fun moods(): List<MoodSummary> = dbQuery {
        val countCol = SongEmbeddingTable.songId.count()
        SongEmbeddingTable
            .select(SongEmbeddingTable.mood, countCol)
            .where { SongEmbeddingTable.mood.isNotNull() }
            .groupBy(SongEmbeddingTable.mood)
            .map { MoodSummary(it[SongEmbeddingTable.mood]!!, it[countCol].toInt()) }
            .sortedByDescending { it.count }
    }

    private fun seedVector(idx: Index, seedIds: List<PlatformUUID>): FloatArray? {
        val vectors = seedIds.mapNotNull { idx.pos[it]?.let { p -> idx.vectors[p] } }
        if (vectors.isEmpty()) return null
        val sum = FloatArray(vectors[0].size)
        vectors.forEach { v -> for (i in sum.indices) sum[i] += v[i] }
        return unit(sum)
    }

    private suspend fun rank(
        idx: Index,
        query: FloatArray,
        exclude: Set<PlatformUUID>,
        limit: Int,
        userId: PlatformUUID,
    ): List<UserSong> {
        val scored = ArrayList<Pair<PlatformUUID, Float>>(idx.ids.size)
        for (i in idx.ids.indices) {
            val id = idx.ids[i]
            if (id in exclude) continue
            scored.add(id to dot(query, idx.vectors[i]))
        }
        val top = scored.sortedByDescending { it.second }.take(limit).map { it.first }
        val byId = songService.byIds(top, userId).associateBy { it.id }
        return top.mapNotNull { byId[it] }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    private fun unit(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm == 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    private fun unpack(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }

    private fun RecommendationWindow.duration(): Duration = when (this) {
        RecommendationWindow.DAY -> 1.days
        RecommendationWindow.WEEK -> 7.days
        RecommendationWindow.MONTH -> 30.days
    }
}

class RpcRecommendationService(
    private val user: User,
    private val service: RecommendationServingService,
) : IRecommendationService {
    override suspend fun getSimilarSongs(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> =
        service.similarSongs(seedSongIds, user.id, limit)

    override suspend fun getMix(window: RecommendationWindow, limit: Int): List<UserSong> =
        service.mix(user.id, window, limit)

    override suspend fun getMoodPlaylist(mood: String, limit: Int): List<UserSong> =
        service.moodPlaylist(mood, user.id, limit)

    override suspend fun getMoods(): List<MoodSummary> = service.moods()
}
