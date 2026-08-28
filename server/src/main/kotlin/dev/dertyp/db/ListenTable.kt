package dev.dertyp.db

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID

enum class ListenSource { LISTENBRAINZ, LOCAL }

object ListenTable : UUIDTable("listen") {
    val listenBrainzUserId = reference("listenBrainzUserId", ListenBrainzUserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val recordingMbid = javaUUID("recordingMbid").nullable()
    val recordingMsid = javaUUID("recordingMsid").nullable()
    val releaseMbid = javaUUID("releaseMbid").nullable()
    val isrcs = text("isrcs").nullable()
    val artistMbids = text("artistMbids").nullable()
    val trackName = text("trackName").nullable()
    val artistName = text("artistName").nullable()
    val releaseName = text("releaseName").nullable()
    val listenedAt = long("listenedAt")
    val listenSource = enumeration<ListenSource>("source")
    val msPlayed = long("msPlayed").nullable()
    val updatedAt = long("updatedAt").default(0L)

    init {
        uniqueIndex(listenBrainzUserId, listenedAt)
        index(false, songId)
        index(false, recordingMbid)
        index(false, userId)
        index(false, userId, listenedAt)
        index(false, listenSource, updatedAt)
    }

    const val DEDUP_WINDOW_MS = 2000L
    const val QUALIFIED_MIN_MS = 3 * 60 * 1000L

    fun isQualifiedPlay(msPlayed: Long?, songDurationMs: Long?): Boolean =
        msPlayed == null || msPlayed >= QUALIFIED_MIN_MS ||
            (songDurationMs != null && songDurationMs > 0 && msPlayed * 2 >= songDurationMs)

    fun playWeight(msPlayed: Long?, songDurationMs: Long?): Float = when {
        msPlayed == null -> 1f
        songDurationMs != null && songDurationMs > 0 -> (msPlayed.toFloat() / songDurationMs).coerceIn(0f, 1f)
        else -> (msPlayed.toFloat() / QUALIFIED_MIN_MS).coerceIn(0f, 1f)
    }

    fun playedMs(msPlayed: Long?, songDurationMs: Long?): Long = msPlayed ?: songDurationMs ?: 0L

    val qualifiedPlay: Op<Boolean>
        get() = msPlayed.isNull() or
            (msPlayed greaterEq QUALIFIED_MIN_MS) or
            ((SongTable.duration greater 0L) and ((msPlayed times 2L) greaterEq SongTable.duration))

    fun parseIsrcs(csv: String?): Set<String> =
        csv?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    fun joinIsrcs(isrcs: Collection<String>): String? =
        isrcs.map { it.uppercase() }.distinct().sorted().joinToString(",").ifBlank { null }
}
