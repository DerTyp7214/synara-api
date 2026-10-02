package dev.dertyp.db

import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.util.UUID

private const val SYNC_CHUNK_SIZE = 500

object SongTitleTagTable : Table("song_title_tag") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val kind = enumerationByName("kind", 32, TitleTagKind::class)

    override val primaryKey = PrimaryKey(songId, kind)

    init {
        index(false, kind)
    }
}

fun syncSongTitleTags(songId: UUID, tags: List<TitleTag>) = syncSongTitleTags(listOf(songId to tags))

fun syncSongTitleTags(songs: List<Pair<UUID, List<TitleTag>>>) {
    for (chunk in songs.chunked(SYNC_CHUNK_SIZE)) {
        val ids = chunk.map { it.first }.distinct()
        SongTitleTagTable.deleteWhere { SongTitleTagTable.songId inList ids }

        val rows = chunk
            .associate { (songId, tags) -> songId to tags.map { it.kind }.distinct() }
            .flatMap { (songId, kinds) -> kinds.map { songId to it } }
        if (rows.isEmpty()) continue

        SongTitleTagTable.batchInsert(rows, shouldReturnGeneratedValues = false) { (songId, kind) ->
            this[SongTitleTagTable.songId] = songId
            this[SongTitleTagTable.kind] = kind
        }
    }
}
