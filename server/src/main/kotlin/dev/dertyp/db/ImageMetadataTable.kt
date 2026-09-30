package dev.dertyp.db

import dev.dertyp.utils.ColorUtils
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import java.util.UUID

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

fun Query.orderByColorDistance(l: Double, a: Double, b: Double): Query {
    val lDiff = ImageMetadataTable.labL.minus(l)
    val aDiff = ImageMetadataTable.labA.minus(a)
    val bDiff = ImageMetadataTable.labB.minus(b)
    val distanceSq = (lDiff.times(lDiff) plus aDiff.times(aDiff) plus bDiff.times(bDiff))
    return orderBy(distanceSq, SortOrder.ASC)
}

class ColorMatch(color: Int, private val range: Int) {
    private val lab = ColorUtils.rgbToLab((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    fun join(columnSet: ColumnSet, image: Column<EntityID<UUID>?>): ColumnSet =
        columnSet.leftJoin(ImageMetadataTable, onColumn = { image }, otherColumn = { ImageMetadataTable.imageId })

    fun filterAndOrder(query: Query): Query {
        val (l, a, b) = lab
        return query.filterByColor(l, a, b, range).orderByColorDistance(l, a, b)
    }
}
