package dev.dertyp.services

import dev.dertyp.core.Quintuple
import dev.dertyp.core.filterValueNotNull
import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.db.AlbumArtistTable
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.getDateFromISO
import dev.dertyp.getISOFromDate
import dev.dertyp.services.ArtistService.Companion.mapArtist
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import java.io.File
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

class AlbumService : Service() {
    val albumArtistAlias = ArtistTable.alias("albumArtistAlias")

    companion object {
        fun mapAlbum(resultRow: ResultRow): Album {
            val id = resultRow[AlbumTable.id].value

            return Album(
                id = id,
                name = resultRow[AlbumTable.name],
                releaseDate = getDateFromISO(resultRow[AlbumTable.releaseDate]),
                artists = listOf(),
                songCount = resultRow[AlbumTable.songCount],
                totalDuration = -1,
                coverId = resultRow[AlbumTable.cover]?.value,
                originalId = resultRow[AlbumTable.originalId],
            )
        }

        suspend fun calculateAlbumStats(albumIds: List<UUID>): Map<UUID, Pair<Long, Long>> = dbQuery {
            SongTable
                .select(SongTable.albumId, SongTable.duration.sum(), SongTable.fileSize.sum())
                .where { SongTable.albumId inList albumIds }
                .groupBy(SongTable.albumId)
                .associate { row ->
                    row[SongTable.albumId].value to Pair(
                        row[SongTable.duration.sum()] ?: -1L,
                        row[SongTable.fileSize.sum()] ?: -1L
                    )
                }
        }
    }

    fun map(resultRow: ResultRow): Album = mapAlbum(resultRow)

    suspend fun byId(id: UUID): Album? = querySingle {
        where { AlbumTable.id eq id }
    }

    suspend fun byName(page: Int, pageSize: Int, name: String): PaginatedResponse<Album> = queryAlbums(page, pageSize) {
        where { AlbumTable.name eq name }
    }

    suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        singles: Boolean = false
    ): PaginatedResponse<Album> =
        queryAlbums(page, pageSize) {
            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.columns)
                .where { AlbumArtistTable.artistId eq artistId }
                .map { it[AlbumArtistTable.albumId].value }

            if (!singles) where { AlbumTable.songCount greater 1 }
            else where { AlbumTable.songCount eq 1 }
            andWhere { AlbumTable.id inList albumIds }
            orderBy(AlbumTable.releaseDate, SortOrder.DESC_NULLS_LAST)
        }

    suspend fun rankedSearch(page: Int, pageSize: Int, query: String): PaginatedResponse<Album> =
        queryAlbums(page, pageSize) {
            rankedSearchQuery(
                query,
                listOf(10, 5),
                listOf(AlbumTable.name, albumArtistAlias[ArtistTable.name])
            )
            andWhere { AlbumTable.songCount greater 1 }
        }

    suspend fun allAlbums(page: Int, pageSize: Int): PaginatedResponse<Album> = queryAlbums(page, pageSize)

    @Suppress("DuplicatedCode")
    suspend fun deleteAlbums(ids: List<UUID>) = dbQuery {
        val paths = SongTable
            .select(SongTable.albumId, SongTable.filePath)
            .where { SongTable.albumId inList ids }
            .map { it[SongTable.filePath] }

        logger.info("Found ${paths.size} files to delete.")

        val deletedSongs = SongTable.deleteWhere {
            SongTable.albumId inList ids
        }

        logger.info("Deleted $deletedSongs songs from the database")

        AlbumTable.deleteWhere {
            notExists(
                SongTable.select(SongTable.id).where {
                    SongTable.albumId eq AlbumTable.id
                }
            )
        }

        val albumsPath = get<StorageService>().albumsPath?.let { Paths.get(it) }
        val links = if (albumsPath != null) {
            val fileNames = paths.map { File(it).nameWithoutExtension }
            albumsPath.toFile().walkTopDown().filter {
                it.toPath().isSymbolicLink() && fileNames.contains(it.nameWithoutExtension)
            }.map { it.absolutePath }.toList()
        } else emptyList()

        for (path in paths + links) {
            val file = File(path)
            if (file.toPath().isSymbolicLink())
                logger.info(
                    "File is a symbolic link pointing to: ${
                        file.toPath().readSymbolicLink().absolutePathString()
                    }"
                )
            if (file.exists())
                logger.info("Trying to delete ${file.absolutePath} (${file.delete()})")
            if (file.parentFile.list().isEmpty())
                logger.info("Trying to delete parent ${file.parentFile.absolutePath} (${file.parentFile.delete()})")
        }

        deletedSongs == ids.size
    }

    private suspend fun querySingle(query: Query.() -> Query) =
        queryAlbums(0, Int.MAX_VALUE, query).data.singleOrNull()

    private suspend fun queryAlbums(page: Int, pageSize: Int, query: Query.() -> Query = { this }) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
        val rows = AlbumTable
            .leftJoin(AlbumArtistTable, onColumn = { id }, otherColumn = { AlbumArtistTable.albumId })
            .leftJoin(
                albumArtistAlias,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { albumArtistAlias[ArtistTable.id] }
            )
            .select(AlbumTable.columns + albumArtistAlias.columns)
            .query()
            .toList()

        if (rows.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            page = page,
            pageSize = pageSize,
        )

        val albumIds = rows.map { it[AlbumTable.id].value }.distinct()

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val data = mapEagerly(rows, statsByAlbumId, albumArtistAlias)

        PaginatedResponse(
            data = data.take(pageSize),
            page = page,
            pageSize = pageSize,
            hasNextPage = data.size == pageSize + offset,
        )
    }

    private fun mapEagerly(
        rows: List<ResultRow>,
        albumStats: Map<UUID, Pair<Long, Long>>,
        albumArtistAlias: Alias<ArtistTable>
    ): List<Album> {
        val albumMap = mutableMapOf<UUID, Album>()
        val albumArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()

        for (row in rows) {
            val albumId = row[AlbumTable.id].value

            albumMap.getOrPut(albumId) {
                mapAlbum(row)
            }

            if (row.getOrNull(albumArtistAlias[ArtistTable.id]) != null) {
                val artist = mapArtist(row, albumArtistAlias)
                if (artist !in albumArtistsMap.getOrDefault(albumId, emptyList())) {
                    albumArtistsMap.getOrPut(albumId) { mutableListOf() }.add(artist)
                }
            }
        }

        return albumMap.values.map { album ->
            val albumArtists = albumArtistsMap[album.id]?.distinctBy { it.id } ?: listOf()

            album.copy(
                artists = albumArtists,
                totalDuration = albumStats[album.id]?.first ?: -1L,
                totalSize = albumStats[album.id]?.second ?: -1L
            )
        }
    }

    suspend fun getOrBulkCreate(albums: List<InsertableAlbum>): Map<InsertableAlbum, UUID> {
        if (albums.isEmpty()) return emptyMap()

        val artistService = get<ArtistService>()
        val imageService = get<ImageService>()

        val uniqueCoverHashed = albums.distinctBy { it.coverHash }.mapNotNull { it.coverHash }
        val uniqueAlbumMetadata =
            albums.distinctBy {
                Quintuple(
                    it.name,
                    it.releaseDate,
                    it.songCount,
                    it.artists.sorted().joinToString(", "),
                    it.originalId
                )
            }
        val uniqueAlbumNames = uniqueAlbumMetadata.map { it.name }
        val uniqueSongCounts = uniqueAlbumMetadata.map { it.songCount }
        val uniqueReleaseDates = uniqueAlbumMetadata.map { getISOFromDate(it.releaseDate) }
        val uniqueOriginalIds = uniqueAlbumMetadata.map { it.originalId }
        val allRequiredArtistNames = albums.flatMap { it.artists }.distinct()

        val artistIdMap: Map<String, UUID> = artistService.getOrBulkCreate(allRequiredArtistNames)
        val imageMap: Map<String, UUID> = imageService.getCoverHashes(uniqueCoverHashed)

        val potentialAlbumRows = queryAlbums(0, Int.MAX_VALUE) {
            where { AlbumTable.name inList uniqueAlbumNames }
            andWhere { AlbumTable.releaseDate inList uniqueReleaseDates }
            andWhere { AlbumTable.songCount inList uniqueSongCounts }
            andWhere { (AlbumTable.originalId inList uniqueOriginalIds) or (AlbumTable.originalId eq null) }
        }.data

        val potentialAlbumIds = potentialAlbumRows.map { it.id }.toSet()

        val albumArtistLinks = dbQuery {
            AlbumArtistTable
                .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId)
                .where { AlbumArtistTable.albumId inList potentialAlbumIds }
                .toList()
        }

        val artistsByPotentialAlbumId = albumArtistLinks
            .groupBy({ it[AlbumArtistTable.albumId].value }, { it[AlbumArtistTable.artistId].value })
            .mapValues { (_, artistIds) -> artistIds.toSet() }

        val finalMatchMap = mutableMapOf<Quintuple<String, String?, Int, String, String?>, UUID>()

        for (row in potentialAlbumRows) {
            val albumId = row.id
            val albumArtists = artistsByPotentialAlbumId[albumId] ?: emptySet()

            val inputAlbum = albums.firstOrNull {
                it.name == row.name &&
                        getISOFromDate(it.releaseDate) == getISOFromDate(row.releaseDate)
                        && (it.originalId == null || it.originalId == row.originalId)
            }

            val requiredArtistIdsForInput = inputAlbum?.artists?.mapNotNull { artistIdMap[it] }?.toSet() ?: emptySet()

            if (albumArtists == requiredArtistIdsForInput) {
                finalMatchMap[Quintuple(
                    row.name,
                    getISOFromDate(row.releaseDate),
                    row.songCount,
                    row.artists.joinToString(", ") { it.name },
                    row.originalId
                )] = albumId
            }
        }

        val newAlbumsToInsert = albums.filter { album ->
            val key = Quintuple(
                album.name,
                getISOFromDate(album.releaseDate),
                album.songCount,
                album.artists.joinToString(", "),
                album.originalId
            )
            !finalMatchMap.containsKey(key)
        }.distinctBy { Triple(it.name, it.releaseDate, it.songCount) }

        val newRows = if (newAlbumsToInsert.isNotEmpty()) {
            dbQuery {
                AlbumTable.batchInsert(newAlbumsToInsert) { album ->
                    this[AlbumTable.name] = album.name
                    this[AlbumTable.releaseDate] = getISOFromDate(album.releaseDate)
                    this[AlbumTable.songCount] = album.songCount
                    this[AlbumTable.cover] = imageMap[album.coverHash]
                    this[AlbumTable.originalId] = album.originalId
                }
            }
        } else {
            emptyList()
        }

        val newAlbumIdMap: Map<InsertableAlbum, UUID> = newRows.associate { row ->
            val matchedAlbum = newAlbumsToInsert.first {
                it.name == row[AlbumTable.name] && getISOFromDate(it.releaseDate) == row[AlbumTable.releaseDate] && (it.originalId == null || it.originalId == row[AlbumTable.originalId])
            }
            matchedAlbum to row[AlbumTable.id].value
        }

        val newAlbumArtistLinks = newAlbumIdMap.flatMap { (albumData, albumId) ->
            albumData.artists.mapNotNull { artistName ->
                artistIdMap[artistName]?.let { artistId ->
                    Triple(albumId, artistId, albumData)
                }
            }
        }.distinct()

        dbQuery {
            AlbumArtistTable.batchInsert(newAlbumArtistLinks) { (albumId, artistId) ->
                this[AlbumArtistTable.albumId] = albumId
                this[AlbumArtistTable.artistId] = artistId
            }
        }

        val newAlbumIdLookupMap = newAlbumIdMap.entries.associate { (album, id) ->
            Quintuple(
                album.name,
                getISOFromDate(album.releaseDate),
                album.songCount,
                album.artists.joinToString(", "),
                album.originalId
            ) to id
        }

        val finalCombinedIdMap = finalMatchMap + newAlbumIdLookupMap

        return albums.associateWith { album ->
            val key = Quintuple(
                album.name,
                getISOFromDate(album.releaseDate),
                album.songCount,
                album.artists.joinToString(", "),
                album.originalId
            )
            finalCombinedIdMap[key]
        }.filterValueNotNull()
    }
}