package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object ImageMetadataTable : Table("image_metadata") {
    val imageId = reference("imageId", ImageTable.id, onDelete = ReferenceOption.CASCADE)
    val width = integer("width")
    val height = integer("height")
    val byteSize = long("byte_size")

    val primaryColor = integer("primary_color")
    val red = integer("red")
    val green = integer("green")
    val blue = integer("blue")
    val luminance = double("luminance")

    val color1 = integer("color1").nullable()
    val color2 = integer("color2").nullable()
    val color3 = integer("color3").nullable()
    val color4 = integer("color4").nullable()
    val color5 = integer("color5").nullable()

    override val primaryKey = PrimaryKey(imageId)
}
