package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID

enum class ListenSource { LISTENBRAINZ, LOCAL }

object ListenTable : UUIDTable("listen") {
    val listenBrainzUserId = reference("listenBrainzUserId", ListenBrainzUserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val recordingMbid = javaUUID("recordingMbid").nullable()
    val releaseMbid = javaUUID("releaseMbid").nullable()
    val artistMbids = text("artistMbids").nullable()
    val trackName = text("trackName").nullable()
    val artistName = text("artistName").nullable()
    val releaseName = text("releaseName").nullable()
    val listenedAt = long("listenedAt")
    val listenSource = enumeration<ListenSource>("source")
    val msPlayed = long("msPlayed").nullable()

    init {
        uniqueIndex(listenBrainzUserId, listenedAt)
        index(false, songId)
        index(false, recordingMbid)
        index(false, userId)
    }

    const val DEDUP_WINDOW_MS = 2000L
}
