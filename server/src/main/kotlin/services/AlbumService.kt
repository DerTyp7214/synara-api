package dev.dertyp.services

import dev.dertyp.core.Quadruple
import dev.dertyp.core.filterValueNotNull
import dev.dertyp.core.foreignKeyOn
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
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class AlbumService(database: Database) : Service() {
    val albumArtistAlias = ArtistTable.alias("albumArtistAlias")

    init {
        transaction(database) {
            foreignKeyOn(database)
            SchemaUtils.create(AlbumTable)
            SchemaUtils.create(AlbumArtistTable)
        }

        instance = this
    }

    companion object {
        var instance: AlbumService? = null
            private set

        fun mapAlbum(resultRow: ResultRow): Album {
            val id = resultRow[AlbumTable.id].value

            return Album(
                id = id,
                name = resultRow[AlbumTable.name],
                releaseDate = getDateFromISO(resultRow[AlbumTable.releaseDate]),
                artists = listOf(),
                songCount = resultRow[AlbumTable.songCount],
                totalDuration = -1,
                coverId = resultRow[AlbumTable.cover]?.value
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

        val uniqueCoverHashed = albums.distinctBy { it.coverHash }.mapNotNull { it.coverHash }
        val uniqueAlbumMetadata =
            albums.distinctBy {
                Quadruple(
                    it.name,
                    it.releaseDate,
                    it.songCount,
                    it.artists.sorted().joinToString(", ")
                )
            }
        val uniqueAlbumNames = uniqueAlbumMetadata.map { it.name }
        val uniqueSongCounts = uniqueAlbumMetadata.map { it.songCount }
        val uniqueReleaseDates = uniqueAlbumMetadata.map { getISOFromDate(it.releaseDate) }
        val allRequiredArtistNames = albums.flatMap { it.artists }.distinct()

        val artistIdMap: Map<String, UUID> =
            ArtistService.instance?.getOrBulkCreate(allRequiredArtistNames) ?: emptyMap()
        val imageMap: Map<String, UUID> =
            ImageService.instance?.getCoverHashes(uniqueCoverHashed) ?: emptyMap()

        val potentialAlbumRows = queryAlbums(0, Int.MAX_VALUE) {
            where { AlbumTable.name inList uniqueAlbumNames }
            andWhere { AlbumTable.releaseDate inList uniqueReleaseDates }
            andWhere { AlbumTable.songCount inList uniqueSongCounts }
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

        val finalMatchMap = mutableMapOf<Quadruple<String, String?, Int, String>, UUID>()

        for (row in potentialAlbumRows) {
            val albumId = row.id
            val albumArtists = artistsByPotentialAlbumId[albumId] ?: emptySet()

            val inputAlbum = albums.firstOrNull {
                it.name == row.name && getISOFromDate(it.releaseDate) == getISOFromDate(row.releaseDate)
            }

            val requiredArtistIdsForInput = inputAlbum?.artists?.mapNotNull { artistIdMap[it] }?.toSet() ?: emptySet()

            if (albumArtists == requiredArtistIdsForInput) {
                finalMatchMap[Quadruple(
                    row.name,
                    getISOFromDate(row.releaseDate),
                    row.songCount,
                    row.artists.joinToString(", ") { it.name }
                )] = albumId
            }
        }

        val newAlbumsToInsert = albums.filter { album ->
            val key = Quadruple(
                album.name,
                getISOFromDate(album.releaseDate),
                album.songCount,
                album.artists.joinToString(", ")
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
                }
            }
        } else {
            emptyList()
        }

        val newAlbumIdMap: Map<InsertableAlbum, UUID> = newRows.associate { row ->
            val matchedAlbum = newAlbumsToInsert.first {
                it.name == row[AlbumTable.name] && getISOFromDate(it.releaseDate) == row[AlbumTable.releaseDate]
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
            Quadruple(
                album.name,
                getISOFromDate(album.releaseDate),
                album.songCount,
                album.artists.joinToString(", ")
            ) to id
        }

        val finalCombinedIdMap = finalMatchMap + newAlbumIdLookupMap

        return albums.associateWith { album ->
            val key = Quadruple(
                album.name,
                getISOFromDate(album.releaseDate),
                album.songCount,
                album.artists.joinToString(", ")
            )
            finalCombinedIdMap[key]
        }.filterValueNotNull()
    }
}