package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object ArtistTable : UUIDTable("artist") {
    val name = text("name")
    val isGroup = bool("group").default(false)
    val groupId = reference("groupId", id).nullable()
    val about = text("about").default("")
    val image = reference("image", ImageTable.id).nullable()
    val lastImageCheck = long("lastImageCheck").default(0L)
    val lastMetadataCheck = long("lastMetadataCheck").default(0L)
}