package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

enum class SongVariantKind {
    ATMOS
}

object SongVariantTable : Table("song_variant") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val kind = enumerationByName("kind", 16, SongVariantKind::class)
    val path = text("path")
    val codec = varchar("codec", 8).default("")
    val sampleRate = integer("sampleRate").default(0)
    val bitsPerSample = integer("bitsPerSample").default(0)
    val channels = integer("channels").default(0)
    val bitRate = long("bitRate").default(0)
    val fileSize = long("fileSize").default(0)

    override val primaryKey = PrimaryKey(songId, kind)
}
