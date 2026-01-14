package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable

object ArtistAliasTable : UUIDTable("artistAlias") {
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
}