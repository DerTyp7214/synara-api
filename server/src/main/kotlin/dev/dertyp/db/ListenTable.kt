package dev.dertyp.db

import dev.dertyp.PlatformUUID
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

enum class ListenSource { LISTENBRAINZ, LOCAL }

enum class ListenScope { OWN, SHARED, RECOMMENDATIONS }

object ListenTable : UUIDTable("listen") {
    val listenBrainzUserId =
        reference("listenBrainzUserId", ListenBrainzUserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val playedSongId = reference("playedSongId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val recordingMbid = javaUUID("recordingMbid").nullable()
    val recordingMsid = javaUUID("recordingMsid").nullable()
    val releaseMbid = javaUUID("releaseMbid").nullable()
    val isrcs = text("isrcs").nullable()
    val artistMbids = text("artistMbids").nullable()
    val trackName = text("trackName").nullable()
    val artistName = text("artistName").nullable()
    val releaseName = text("releaseName").nullable()
    val listenedAt = long("listenedAt")
    val listenSource = enumerationByName("listenSource", 16, ListenSource::class).default(ListenSource.LOCAL)
    val msPlayed = long("msPlayed").nullable()
    val updatedAt = long("updatedAt").default(0L)

    init {
        uniqueIndex(listenBrainzUserId, listenedAt)
        index(false, songId)
        index(false, playedSongId)
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

internal fun listenOwnerIds(userId: PlatformUUID, scope: ListenScope): Set<PlatformUUID> {
    if (scope == ListenScope.OWN) return setOf(userId)
    val partners = ListenShareTable
        .select(ListenShareTable.userA, ListenShareTable.userB)
        .where { (ListenShareTable.userA eq userId) or (ListenShareTable.userB eq userId) }
        .apply { if (scope == ListenScope.RECOMMENDATIONS) andWhere { ListenShareTable.useForRecommendations eq true } }
        .map {
            val a = it[ListenShareTable.userA].value
            if (a == userId) it[ListenShareTable.userB].value else a
        }
    return setOf(userId) + partners
}

internal fun listenOwnerPredicate(userId: PlatformUUID, scope: ListenScope): Op<Boolean> {
    val ownerIds = listenOwnerIds(userId, scope)
    val listenBrainzUserIds = UserListenBrainzLinkTable
        .select(UserListenBrainzLinkTable.listenBrainzUserId)
        .where { UserListenBrainzLinkTable.userId inList ownerIds }
        .map { it[UserListenBrainzLinkTable.listenBrainzUserId].value }
        .distinct()

    return if (listenBrainzUserIds.isNotEmpty()) {
        (ListenTable.userId inList ownerIds) or (ListenTable.listenBrainzUserId inList listenBrainzUserIds)
    } else {
        ListenTable.userId inList ownerIds
    }
}
