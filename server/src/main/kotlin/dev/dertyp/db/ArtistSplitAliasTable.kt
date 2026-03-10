package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ArtistSplitAliasTable : Table("artistSplitAlias") {
    val name = text("name")
    val artistId = reference("artistId", ArtistTable.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(name, artistId)
}
