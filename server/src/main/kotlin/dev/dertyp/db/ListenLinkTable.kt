package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID

object ListenLinkTable : UUIDTable("listen_link") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val recordingMbid = javaUUID("recordingMbid").nullable()
    val recordingMsid = javaUUID("recordingMsid").nullable()
    val createdAt = long("createdAt")

    init {
        index(false, userId)
        index(false, recordingMbid)
        index(false, recordingMsid)
    }
}
