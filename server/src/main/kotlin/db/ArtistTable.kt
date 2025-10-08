package dev.dertyp.db

import org.jetbrains.exposed.dao.id.UUIDTable

object ArtistTable : UUIDTable("artist") {
    val name = text("name")
    val isGroup = bool("group").default(false)
    val groupId = reference("groupId", id).nullable()
    val about = text("about").default("")
    val image = reference("image", ImageTable.id).nullable()
}