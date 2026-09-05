package dev.dertyp.db

import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object CollectionTable : UUIDTable("collection") {
    val name = text("name")
    val description = text("description").nullable()
    val creator = reference("creator", UserTable.id)
    val imageId = reference("imageId", ImageTable.id).nullable()
    val imageSource = varchar("imageSource", 16).nullable()
    val coverStyle = varchar("coverStyle", 32).nullable()
    val coverSeed = long("coverSeed").nullable()
}
