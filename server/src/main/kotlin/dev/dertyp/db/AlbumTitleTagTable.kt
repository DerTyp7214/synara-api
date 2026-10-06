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

object AlbumTitleTagTable : Table("album_title_tag") {
    val albumId = reference("albumId", AlbumTable.id, onDelete = ReferenceOption.CASCADE)
    val kind = enumerationByName("kind", 32, TitleTagKind::class)

    override val primaryKey = PrimaryKey(albumId, kind)

    init {
        index(false, kind)
    }
}

fun syncAlbumTitleTags(albumId: UUID, tags: List<TitleTag>) = syncAlbumTitleTags(listOf(albumId to tags))

fun syncAlbumTitleTags(albums: List<Pair<UUID, List<TitleTag>>>) {
    for (chunk in albums.chunked(SYNC_CHUNK_SIZE)) {
        val ids = chunk.map { it.first }.distinct()
        AlbumTitleTagTable.deleteWhere { AlbumTitleTagTable.albumId inList ids }

        val rows = chunk
            .associate { (albumId, tags) -> albumId to tags.map { it.kind }.distinct() }
            .flatMap { (albumId, kinds) -> kinds.map { albumId to it } }
        if (rows.isEmpty()) continue

        AlbumTitleTagTable.batchInsert(rows, shouldReturnGeneratedValues = false) { (albumId, kind) ->
            this[AlbumTitleTagTable.albumId] = albumId
            this[AlbumTitleTagTable.kind] = kind
        }
    }
}
