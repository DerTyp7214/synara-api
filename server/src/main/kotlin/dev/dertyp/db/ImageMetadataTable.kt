package dev.dertyp.db

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere

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

    val hue = double("hue").nullable()
    val saturation = double("saturation").nullable()
    val lightness = double("lightness").nullable()
    val labL = double("lab_l").nullable()
    val labA = double("lab_a").nullable()
    val labB = double("lab_b").nullable()

    val color1 = integer("color1").nullable()
    val color2 = integer("color2").nullable()
    val color3 = integer("color3").nullable()
    val color4 = integer("color4").nullable()
    val color5 = integer("color5").nullable()

    override val primaryKey = PrimaryKey(imageId)
}

fun Query.filterByColor(l: Double, a: Double, b: Double, range: Int): Query {
    val rangeSq = range * range
    return andWhere {
        val lDiff = ImageMetadataTable.labL.minus(l)
        val aDiff = ImageMetadataTable.labA.minus(a)
        val bDiff = ImageMetadataTable.labB.minus(b)
        (lDiff.times(lDiff) plus aDiff.times(aDiff) plus bDiff.times(bDiff)) lessEq rangeSq.toDouble()
    }
}
