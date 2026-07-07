package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

enum class ListenSource { LISTENBRAINZ, LOCAL }

object ListenTable : UUIDTable("listen") {
    val listenBrainzUserId = reference("listenBrainzUserId", ListenBrainzUserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val synaraUserId = reference("synaraUserId", UserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val listenedAt = long("listenedAt")
    val listenSource = enumeration<ListenSource>("source")
    val msPlayed = long("msPlayed").nullable()

    init {
        uniqueIndex(listenBrainzUserId, songId, listenedAt)
        index(false, songId)
    }
}
