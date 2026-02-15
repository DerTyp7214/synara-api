package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object UserPlaylistTable : UUIDTable("userPlaylist") {
    val name = text("name")
    val description = text("description")
    val customIdentifier = text("customIdentifier").nullable()
    val creator = reference("creator", UserTable.id)
    val imageId = reference("imageId", ImageTable.id).nullable()
    val origin = text("origin").nullable()
}