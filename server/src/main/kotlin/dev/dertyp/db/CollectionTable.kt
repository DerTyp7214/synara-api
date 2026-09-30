package dev.dertyp.db

import dev.dertyp.data.CoverStyle
import dev.dertyp.data.ImageSource
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object CollectionTable : UUIDTable("collection") {
    val name = text("name")
    val description = text("description").nullable()
    val creator = reference("creator", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val imageSource = enumerationByName("imageSource", 16, ImageSource::class).nullable()
    val coverStyle = enumerationByName("coverStyle", 32, CoverStyle::class).nullable()
    val coverSeed = long("coverSeed").nullable()
}
