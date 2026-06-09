package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object ArtistTable : UUIDTable("artist") {
    val name = text("name")
    val isGroup = bool("group").default(false)
    val about = text("about").default("")
    val image = reference("image", ImageTable.id).nullable()
    val lastImageCheck = long("lastImageCheck").default(0L)
    val lastMetadataCheck = long("lastMetadataCheck").default(0L)
    val searchVector = tsvector("search_vector").nullable()
}

object ArtistMemberTable : Table("artist_member") {
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val groupId = reference("groupId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(artistId, groupId)
}
