package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.platformUUIDFromString
import dev.dertyp.serializers.AppJson
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.io.BufferedWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RecommendationService : Service() {
    private val environment by inject<ApplicationEnvironment>()

    private val dirty = AtomicBoolean(true)

    private val dataDir: File?
        get() = environment.config.propertyOrNull("recsys.dataDir")?.getString()
            ?.takeIf { it.isNotBlank() }?.let { File(it) }

    fun isConfigured(): Boolean = dataDir != null

    fun markDirty() {
        dirty.set(true)
    }

    suspend fun trainIfDirty(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int {
        if (!isConfigured()) return 0
        if (!dirty.compareAndSet(true, false)) return 0
        return try {
            train(onProgress)
        } catch (e: Exception) {
            dirty.set(true)
            throw e
        }
    }

    suspend fun train(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int {
        val dir = dataDir ?: return 0
        dir.mkdirs()

        listOf("request.ready", "result.ready", "result.failed").forEach { File(dir, it).delete() }

        onProgress(0.0, "Exporting library")
        val songCount = exportSongs(File(dir, "songs.jsonl")) { exported, totalSongs ->
            onProgress(exported.toDouble() / totalSongs.coerceAtLeast(1) * 35.0, "Exporting library: $exported/$totalSongs songs")
        }
        if (songCount == 0) {
            logger.info("No songs to train on, skipping")
            onProgress(100.0, "No songs to train on")
            return 0
        }

        onProgress(38.0, "Exporting listening history & playlists")
        exportSequences(File(dir, "sequences.jsonl"))
        File(dir, "meta.json").writeText(AppJson.encodeToString(Meta(dim = DIM, clusters = CLUSTERS)))
        File(dir, "request.ready").writeText("go")
        logger.info("Exported $songCount songs, waiting for recsys to train the model")

        onProgress(40.0, "Waiting for recsys to train the model")
        val modelVersion = awaitResult(dir) ?: run {
            logger.error("recsys training did not complete in time")
            onProgress(100.0, "recsys training failed or timed out")
            return 0
        }

        onProgress(80.0, "Ingesting embeddings")
        val ingested = ingest(File(dir, "embeddings.jsonl"), modelVersion) { done ->
            onProgress(85.0, "Ingesting embeddings: $done")
        }
        listOf("result.ready", "result.failed").forEach { File(dir, it).delete() }
        logger.info("Recommendation training complete: ingested $ingested embedding(s) (model $modelVersion)")
        onProgress(100.0, "Trained model, ingested $ingested embedding(s)")
        return ingested
    }

    private suspend fun exportSongs(file: File, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int {
        val totalSongs = dbQuery { SongTable.selectAll().count().toInt() }
        var total = 0
        file.bufferedWriter().use { writer ->
            var offset = 0L
            while (true) {
                val ids = dbQuery {
                    SongTable.select(SongTable.id)
                        .orderBy(SongTable.id to SortOrder.ASC)
                        .limit(CHUNK).offset(offset)
                        .map { it[SongTable.id].value }
                }
                if (ids.isEmpty()) break

                val audioBySong = dbQuery {
                    SongAudioEmbeddingTable.select(SongAudioEmbeddingTable.songId, SongAudioEmbeddingTable.vector)
                        .where { SongAudioEmbeddingTable.songId inList ids }
                        .associate { it[SongAudioEmbeddingTable.songId].value to unpackFloats(it[SongAudioEmbeddingTable.vector]) }
                }
                val genresBySong = dbQuery {
                    SongGenreTable.innerJoin(GenreTable)
                        .select(SongGenreTable.songId, GenreTable.name)
                        .where { SongGenreTable.songId inList ids }
                        .groupBy({ it[SongGenreTable.songId].value }, { it[GenreTable.name] })
                }

                ids.forEach { id ->
                    val line = SongLine(id.toString(), audioBySong[id], genresBySong[id] ?: emptyList())
                    writer.appendLine(AppJson.encodeToString(line))
                    total++
                }
                onProgress(total, totalSongs)
                offset += CHUNK
            }
        }
        return total
    }

    private suspend fun exportSequences(file: File) {
        file.bufferedWriter().use { writer ->
            dbQuery {
                emitPlaylistSequences(writer)
                emitAlbumSequences(writer)
                emitListenSequences(writer)
            }
        }
    }

    private fun BufferedWriter.emitSequence(songIds: List<String>) {
        if (songIds.size >= 2) appendLine(AppJson.encodeToString(songIds))
    }

    private fun emitPlaylistSequences(writer: BufferedWriter) {
        UserPlaylistSongTable
            .select(UserPlaylistSongTable.playlistId, UserPlaylistSongTable.songId, UserPlaylistSongTable.addedAt)
            .orderBy(UserPlaylistSongTable.playlistId to SortOrder.ASC, UserPlaylistSongTable.addedAt to SortOrder.ASC)
            .groupBy({ it[UserPlaylistSongTable.playlistId].value }, { it[UserPlaylistSongTable.songId].value.toString() })
            .values.forEach { writer.emitSequence(it) }

        PlaylistSongTable
            .select(PlaylistSongTable.playlistId, PlaylistSongTable.songId, PlaylistSongTable.position)
            .orderBy(PlaylistSongTable.playlistId to SortOrder.ASC, PlaylistSongTable.position to SortOrder.ASC)
            .groupBy({ it[PlaylistSongTable.playlistId].value }, { it[PlaylistSongTable.songId].value.toString() })
            .values.forEach { writer.emitSequence(it) }
    }

    private fun emitAlbumSequences(writer: BufferedWriter) {
        SongTable
            .select(SongTable.id, SongTable.albumId, SongTable.discNumber, SongTable.trackNumber)
            .orderBy(SongTable.albumId to SortOrder.ASC, SongTable.discNumber to SortOrder.ASC, SongTable.trackNumber to SortOrder.ASC)
            .groupBy({ it[SongTable.albumId].value }, { it[SongTable.id].value.toString() })
            .values.forEach { writer.emitSequence(it) }
    }

    private fun emitListenSequences(writer: BufferedWriter) {
        val current = mutableListOf<String>()
        var lastOwner: PlatformUUID? = null
        var lastTs = 0L
        var lastSong: String? = null
        var lastRecordingMbid: PlatformUUID? = null
        var lastIsrcs: Set<String> = emptySet()

        val link = UserListenBrainzLinkTable
        val ownerKey = Coalesce(ListenTable.userId, link.userId, ListenTable.listenBrainzUserId)

        ListenTable
            .join(link, JoinType.LEFT, onColumn = ListenTable.listenBrainzUserId, otherColumn = link.listenBrainzUserId)
            .join(SongTable, JoinType.INNER, onColumn = ListenTable.songId, otherColumn = SongTable.id)
            .select(ListenTable.userId, link.userId, ListenTable.listenBrainzUserId, ListenTable.songId, ListenTable.listenedAt, ListenTable.recordingMbid, ListenTable.isrcs, ListenTable.msPlayed, SongTable.duration)
            .where { ListenTable.songId.isNotNull() }
            .andWhere { ListenTable.userId.isNotNull() or ListenTable.listenBrainzUserId.isNotNull() }
            .orderBy(ownerKey to SortOrder.ASC, ListenTable.listenedAt to SortOrder.ASC)
            .forEach { row ->
                val owner = row[ListenTable.userId]?.value
                    ?: row.getOrNull(link.userId)?.value
                    ?: row[ListenTable.listenBrainzUserId]!!.value
                val ts = row[ListenTable.listenedAt]
                val song = row[ListenTable.songId]!!.value.toString()
                val recordingMbid = row[ListenTable.recordingMbid]
                val isrcs = ListenTable.parseIsrcs(row[ListenTable.isrcs])

                if (owner != lastOwner || (ts - lastTs).milliseconds > SESSION_GAP) {
                    writer.emitSequence(current.toList())
                    current.clear()
                    lastSong = null
                    lastRecordingMbid = null
                    lastIsrcs = emptySet()
                }

                val duplicatePlay = ts - lastTs <= ListenTable.DEDUP_WINDOW_MS && (
                    song == lastSong ||
                        (recordingMbid != null && recordingMbid == lastRecordingMbid) ||
                        isrcs.any { it in lastIsrcs }
                    )
                val qualified = ListenTable.isQualifiedPlay(row[ListenTable.msPlayed], row[SongTable.duration])
                if (!duplicatePlay && qualified) current.add(song)

                lastOwner = owner
                lastTs = ts
                lastSong = song
                lastRecordingMbid = recordingMbid
                lastIsrcs = isrcs
            }
        writer.emitSequence(current.toList())
    }

    private suspend fun awaitResult(dir: File): String? {
        val request = File(dir, "request.ready")
        val ready = File(dir, "result.ready")
        val failed = File(dir, "result.failed")
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = (System.currentTimeMillis() - start).milliseconds
            if (elapsed > RESULT_TIMEOUT) return null
            if (ready.exists()) return ready.readText().trim().ifBlank { "hybrid_v1" }
            if (failed.exists()) {
                logger.error("recsys reported failure: ${failed.readText().take(500)}")
                return null
            }
            if (request.exists() && elapsed > PICKUP_TIMEOUT) {
                logger.error("recsys did not pick up the training request (service not running?)")
                return null
            }
            delay(POLL)
        }
    }

    private suspend fun ingest(file: File, modelVersion: String, onProgress: suspend (Int) -> Unit = {}): Int {
        if (!file.exists()) return 0
        val now = System.currentTimeMillis()
        var count = 0
        file.bufferedReader().useLines { lines ->
            lines.chunked(INGEST_BATCH).forEach { batch ->
                val parsed = batch.mapNotNull { line ->
                    if (line.isBlank()) return@mapNotNull null
                    val emb = AppJson.decodeFromString<EmbeddingLine>(line)
                    val id = runCatching { platformUUIDFromString(emb.id) }.getOrNull() ?: return@mapNotNull null
                    id to emb
                }
                dbQuery {
                    parsed.forEach { (id, emb) ->
                        SongEmbeddingTable.upsert(SongEmbeddingTable.songId) {
                            it[SongEmbeddingTable.songId] = id
                            it[SongEmbeddingTable.vector] = packFloats(emb.vector)
                            it[SongEmbeddingTable.dim] = emb.vector.size
                            it[SongEmbeddingTable.clusterId] = emb.cluster
                            it[SongEmbeddingTable.mood] = emb.mood
                            it[SongEmbeddingTable.modelVersion] = modelVersion
                            it[SongEmbeddingTable.updatedAt] = now
                        }
                    }
                }
                count += parsed.size
                onProgress(count)
            }
        }
        logger.info("Ingested $count song embeddings")
        return count
    }

    private fun packFloats(values: List<Float>): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    private fun unpackFloats(bytes: ByteArray): List<Float> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Float>(bytes.size / 4)
        while (buffer.remaining() >= 4) out.add(buffer.float)
        return out
    }

    companion object {
        private const val DIM = 64
        private const val CLUSTERS = 24
        private const val CHUNK = 5000
        private const val INGEST_BATCH = 1000
        private val SESSION_GAP = 30.minutes
        private val RESULT_TIMEOUT = 6.hours
        private val PICKUP_TIMEOUT = 5.minutes
        private val POLL = 2.seconds
    }
}

@Serializable
private data class SongLine(
    val id: String,
    val audio: List<Float>? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
private data class EmbeddingLine(
    val id: String,
    val vector: List<Float>,
    val cluster: Int? = null,
    val mood: String? = null,
)

@Serializable
private data class Meta(val dim: Int, val clusters: Int)
