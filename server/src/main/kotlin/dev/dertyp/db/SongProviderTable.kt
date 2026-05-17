package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object SongProviderTable : Table("song_provider") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 64)
    val externalId = text("externalId").default("")
    val type = varchar("type", 32).nullable()
    val rawUrl = text("rawUrl")
    val addedAt = long("addedAt").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(songId, provider, externalId)
}
