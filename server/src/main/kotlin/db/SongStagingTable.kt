package dev.dertyp.db

import org.jetbrains.exposed.v1.core.Table

object SongStagingTable : Table("SongStagingTable") {
    val sessionId = uuid("session_id")
    val title = text("title")
    val trackNumber = integer("track_number")
    val discNumber = integer("disc_number")
}