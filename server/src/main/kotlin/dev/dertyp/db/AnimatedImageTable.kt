package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable

object AnimatedImageTable : UUIDTable("animated_image") {
    val path = text("path")
    val contentHash = varchar("hash", 255)
    val origin = text("origin")
    val format = varchar("format", 32).nullable()
    val imageId = reference("image_id", ImageTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
}
