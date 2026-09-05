package dev.dertyp.services.cover

import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.Service
import dev.dertyp.utils.ColorUtils
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.select
import java.awt.Color
import java.util.UUID
import kotlin.math.sqrt

data class CoverContext(
    val title: String,
    val itemCount: Int,
    val coverImageIds: List<UUID>,
    val palette: List<Int>,
    val genres: Map<String, Int>,
    val moods: Map<String, Int>,
    val energy: Double?,
    val valence: Double?,
    val bpm: Double?,
    val explicitRatio: Double,
)

class CoverSourceCollector : Service() {
    suspend fun collect(target: CoverTarget): CoverContext? = when (target.type) {
        CoverTargetType.PLAYLIST -> playlist(target.id)
        CoverTargetType.COLLECTION -> collection(target.id)
    }

    private suspend fun playlist(id: UUID): CoverContext? = dbQuery {
        val name = UserPlaylistTable.select(UserPlaylistTable.name)
            .where { UserPlaylistTable.id eq id }
            .singleOrNull()?.get(UserPlaylistTable.name) ?: return@dbQuery null

        val songs = UserPlaylistSongTable
            .innerJoin(SongTable, { UserPlaylistSongTable.songId }, { SongTable.id })
            .leftJoin(AlbumTable, { SongTable.albumId }, { AlbumTable.id })
            .select(SongTable.id, SongTable.cover, SongTable.albumId, SongTable.explicit, AlbumTable.cover)
            .where { UserPlaylistSongTable.playlistId eq id }
            .orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
            .limit(SONG_LIMIT)
            .map { SongRow.from(it) }

        val coverIds = songs.mapNotNull { it.albumCover ?: it.cover }.distinct()
        build(name, songs, coverIds, emptyList(), emptyList())
    }

    private suspend fun collection(id: UUID): CoverContext? = dbQuery {
        val name = CollectionTable.select(CollectionTable.name)
            .where { CollectionTable.id eq id }
            .singleOrNull()?.get(CollectionTable.name) ?: return@dbQuery null

        val artistRows = CollectionArtistTable
            .innerJoin(ArtistTable, { CollectionArtistTable.artistId }, { ArtistTable.id })
            .select(ArtistTable.id, ArtistTable.image)
            .where { CollectionArtistTable.collectionId eq id }
            .orderBy(CollectionArtistTable.addedAt, SortOrder.ASC)
            .limit(ITEM_LIMIT)
            .map { it[ArtistTable.id].value to it[ArtistTable.image]?.value }
        val albumRows = CollectionAlbumTable
            .innerJoin(AlbumTable, { CollectionAlbumTable.albumId }, { AlbumTable.id })
            .select(AlbumTable.id, AlbumTable.cover)
            .where { CollectionAlbumTable.collectionId eq id }
            .orderBy(CollectionAlbumTable.addedAt, SortOrder.ASC)
            .limit(ITEM_LIMIT)
            .map { it[AlbumTable.id].value to it[AlbumTable.cover]?.value }
        val playlistCovers = CollectionPlaylistTable
            .innerJoin(UserPlaylistTable, { CollectionPlaylistTable.playlistId }, { UserPlaylistTable.id })
            .select(UserPlaylistTable.imageId)
            .where { CollectionPlaylistTable.collectionId eq id }
            .orderBy(CollectionPlaylistTable.addedAt, SortOrder.ASC)
            .limit(ITEM_LIMIT)
            .mapNotNull { it[UserPlaylistTable.imageId]?.value }
        val directSongs = CollectionSongTable
            .innerJoin(SongTable, { CollectionSongTable.songId }, { SongTable.id })
            .leftJoin(AlbumTable, { SongTable.albumId }, { AlbumTable.id })
            .select(SongTable.id, SongTable.cover, SongTable.albumId, SongTable.explicit, AlbumTable.cover)
            .where { CollectionSongTable.collectionId eq id }
            .orderBy(CollectionSongTable.addedAt, SortOrder.ASC)
            .limit(SONG_LIMIT)
            .map { SongRow.from(it) }
        val albumSongs = albumRows.map { it.first }.chunked(maxBatchSize).flatMap { chunk ->
            SongTable
                .leftJoin(AlbumTable, { SongTable.albumId }, { AlbumTable.id })
                .select(SongTable.id, SongTable.cover, SongTable.albumId, SongTable.explicit, AlbumTable.cover)
                .where { SongTable.albumId inList chunk }
                .limit(SONG_LIMIT)
                .map { SongRow.from(it) }
        }

        val sources = listOf(
            artistRows.mapNotNull { it.second },
            albumRows.mapNotNull { it.second },
            playlistCovers,
            directSongs.mapNotNull { it.albumCover ?: it.cover },
        )
        val coverIds = interleave(sources).distinct()
        val itemCount = artistRows.size + albumRows.size + playlistCovers.size + directSongs.size
        build(
            name,
            (directSongs + albumSongs).distinctBy { it.id },
            coverIds,
            artistRows.map { it.first },
            albumRows.map { it.first },
            itemCount,
        )
    }

    private fun build(
        name: String,
        songs: List<SongRow>,
        coverIds: List<UUID>,
        artistIds: List<UUID>,
        albumIds: List<UUID>,
        itemCount: Int = songs.size,
    ): CoverContext {
        val songIds = songs.map { it.id }
        val albumIdsForGenres = (albumIds + songs.mapNotNull { it.albumId }).distinct()

        val genres = HashMap<String, Int>()
        countGenres(genres, SongGenreTable, SongGenreTable.songId, SongGenreTable.genreId, songIds)
        countGenres(genres, AlbumGenreTable, AlbumGenreTable.albumId, AlbumGenreTable.genreId, albumIdsForGenres)
        countGenres(genres, ArtistGenreTable, ArtistGenreTable.artistId, ArtistGenreTable.genreId, artistIds)

        val moods = HashMap<String, Int>()
        songIds.chunked(maxBatchSize).forEach { chunk ->
            SongEmbeddingTable.select(SongEmbeddingTable.mood)
                .where { SongEmbeddingTable.songId inList chunk }
                .forEach { row -> row[SongEmbeddingTable.mood]?.lowercase()?.let { moods.merge(it, 1, Int::plus) } }
        }

        val energies = ArrayList<Double>()
        val valences = ArrayList<Double>()
        val bpms = ArrayList<Double>()
        songIds.chunked(maxBatchSize).forEach { chunk ->
            SongAudioDataTable.select(SongAudioDataTable.energy, SongAudioDataTable.valence, SongAudioDataTable.bpm)
                .where { SongAudioDataTable.songId inList chunk }
                .forEach { row ->
                    row[SongAudioDataTable.energy]?.let(energies::add)
                    row[SongAudioDataTable.valence]?.let(valences::add)
                    row[SongAudioDataTable.bpm]?.let(bpms::add)
                }
        }

        val paletteSource = coverIds.take(MAX_TILES)
        val palette = paletteSource.chunked(maxBatchSize).flatMap { chunk ->
            ImageMetadataTable
                .select(ImageMetadataTable.imageId, ImageMetadataTable.color1, ImageMetadataTable.color2, ImageMetadataTable.color3, ImageMetadataTable.color4, ImageMetadataTable.color5)
                .where { ImageMetadataTable.imageId inList chunk }
                .associateBy { it[ImageMetadataTable.imageId].value }
                .let { byId -> chunk.mapNotNull { byId[it] } }
                .flatMap { row ->
                    listOfNotNull(
                        row[ImageMetadataTable.color1], row[ImageMetadataTable.color2], row[ImageMetadataTable.color3],
                        row[ImageMetadataTable.color4], row[ImageMetadataTable.color5],
                    )
                }
        }.let(::dedupePalette)

        return CoverContext(
            title = name,
            itemCount = itemCount,
            coverImageIds = coverIds,
            palette = palette,
            genres = genres,
            moods = moods,
            energy = energies.takeIf { it.isNotEmpty() }?.average(),
            valence = valences.takeIf { it.isNotEmpty() }?.average(),
            bpm = bpms.takeIf { it.isNotEmpty() }?.average(),
            explicitRatio = if (songs.isEmpty()) 0.0 else songs.count { it.explicit }.toDouble() / songs.size,
        )
    }

    private fun countGenres(
        into: MutableMap<String, Int>,
        table: org.jetbrains.exposed.v1.core.Table,
        idColumn: Column<EntityID<UUID>>,
        genreColumn: Column<EntityID<UUID>>,
        ids: List<UUID>,
    ) {
        ids.chunked(maxBatchSize).forEach { chunk ->
            table.innerJoin(GenreTable, { genreColumn }, { GenreTable.id })
                .select(GenreTable.name)
                .where { idColumn inList chunk }
                .forEach { into.merge(it[GenreTable.name].lowercase(), 1, Int::plus) }
        }
    }

    private fun dedupePalette(colors: List<Int>): List<Int> {
        val kept = ArrayList<Pair<Int, Triple<Double, Double, Double>>>()
        for (argb in colors) {
            val color = Color(argb)
            val lab = ColorUtils.rgbToLab(color.red, color.green, color.blue)
            val distinct = kept.none { (_, other) ->
                val dl = lab.first - other.first
                val da = lab.second - other.second
                val db = lab.third - other.third
                sqrt(dl * dl + da * da + db * db) < PALETTE_DISTANCE
            }
            if (distinct) kept += argb to lab
            if (kept.size >= MAX_PALETTE) break
        }
        return kept.map { it.first }
    }

    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val result = ArrayList<T>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (list in lists) if (i < list.size) result += list[i]
        return result
    }

    private data class SongRow(val id: UUID, val cover: UUID?, val albumId: UUID?, val albumCover: UUID?, val explicit: Boolean) {
        companion object {
            fun from(row: ResultRow) = SongRow(
                id = row[SongTable.id].value,
                cover = row[SongTable.cover]?.value,
                albumId = row[SongTable.albumId]?.value,
                albumCover = row.getOrNull(AlbumTable.cover)?.value,
                explicit = row[SongTable.explicit],
            )
        }
    }

    companion object {
        const val MAX_TILES = 9
        private const val MAX_PALETTE = 8
        private const val PALETTE_DISTANCE = 12.0
        private const val SONG_LIMIT = 500
        private const val ITEM_LIMIT = 200
    }
}
