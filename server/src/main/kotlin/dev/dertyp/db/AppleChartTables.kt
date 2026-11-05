package dev.dertyp.db

import dev.dertyp.data.AppleChartKind
import org.jetbrains.exposed.v1.core.Table

object AppleChartEntryTable : Table("apple_chart_entry") {
    val country = varchar("country", 2)
    val kind = enumerationByName("kind", 16, AppleChartKind::class)
    val rank = integer("rank")
    val appleId = varchar("appleId", 64)
    val name = text("name")
    val artistName = text("artistName")
    val releaseDate = varchar("releaseDate", 32).nullable()
    val artworkUrl = text("artworkUrl").nullable()
    val url = text("url")
    val genres = text("genres")
    val isrc = varchar("isrc", 32).nullable()
    val upc = varchar("upc", 32).nullable()

    override val primaryKey = PrimaryKey(country, kind, rank)
}

object AppleChartFetchTable : Table("apple_chart_fetch") {
    val country = varchar("country", 2)
    val kind = enumerationByName("kind", 16, AppleChartKind::class)
    val fetchedAt = long("fetchedAt")
    val feedUpdatedAt = long("feedUpdatedAt")

    override val primaryKey = PrimaryKey(country, kind)
}
