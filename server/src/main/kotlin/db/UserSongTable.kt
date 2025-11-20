package dev.dertyp.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object UserSongTable : Table("userSong") {
    val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)

    val isFavourite = bool("favourite").default(false)

    val createdAt =
        text("createdAt").clientDefault { LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
    val updatedAt =
        text("updatedAt").clientDefault { LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) }

    override val primaryKey = PrimaryKey(userId, songId)
}