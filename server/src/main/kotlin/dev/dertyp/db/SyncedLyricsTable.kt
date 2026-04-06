package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SyncedLyricsTable : Table("synced_lyrics") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val content = binary("content").nullable()
    val rawLyrics = text("raw_lyrics").nullable()
    val provider = text("provider").default("whisperx_v1")

    override val primaryKey = PrimaryKey(songId)
}
