package dev.dertyp.db

import dev.dertyp.data.TimecodeTagType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import java.time.Instant

object TimecodeTagTable : UUIDTable("timecodeTag") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val type = enumerationByName("type", 16, TimecodeTagType::class)
    val text = text("text").default("")
    val timestampMs = long("timestampMs")
    val endMs = long("endMs").nullable()
    val createdAt = long("createdAt").clientDefault { Instant.now().toEpochMilli() }
    val updatedAt = long("updatedAt").clientDefault { Instant.now().toEpochMilli() }

    init {
        index(false, userId, songId, timestampMs)
        index(false, userId, type, createdAt)
    }
}
