package dev.dertyp.services

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
import io.ktor.util.logging.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class AlbumService(database: Database) {
    private val logger = KtorSimpleLogger("AlbumService")

    val albumArtistAlias = ArtistTable.alias("albumArtistAlias")

    init {
        transaction(database) {
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

        suspend fun calculateAlbumDurations(albumIds: List<UUID>): Map<UUID, Long> = dbQuery {
            SongTable
                .select(SongTable.albumId, SongTable.duration.sum())
                .where { SongTable.albumId inList albumIds }
                .groupBy(SongTable.albumId)
                .associate { row ->
                    row[SongTable.albumId].value to (row[SongTable.duration.sum()] ?: -1L)
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

    suspend fun byArtist(page: Int, pageSize: Int, artistId: UUID): PaginatedResponse<Album> =
        queryAlbums(page, pageSize) {
            where { AlbumArtistTable.artistId eq artistId }
        }

    suspend fun searchByName(page: Int, pageSize: Int, name: String): PaginatedResponse<Album> =
        queryAlbums(page, pageSize) {
            where { AlbumTable.name like "%$name%" }
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

        val durationsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumDurations(albumIds)
        } else {
            emptyMap()
        }

        val data = mapEagerly(rows, durationsByAlbumId, albumArtistAlias)

        PaginatedResponse(
            data = data.take(pageSize),
            page = page,
            pageSize = pageSize,
            hasNextPage = data.size == pageSize + offset,
        )
    }

    private fun mapEagerly(
        rows: List<ResultRow>,
        durations: Map<UUID, Long>,
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
                totalDuration = durations[album.id] ?: -1L
            )
        }
    }

    suspend fun getOrBulkCreate(albums: List<InsertableAlbum>): Map<InsertableAlbum, UUID> {
        if (albums.isEmpty()) return emptyMap()

        val uniqueCoverHashed = albums.distinctBy { it.coverHash }.mapNotNull { it.coverHash }
        val uniqueAlbumMetadata = albums.distinctBy { Pair(it.name, it.releaseDate) }
        val uniqueAlbumNames = uniqueAlbumMetadata.map { it.name }
        val uniqueReleaseDates = uniqueAlbumMetadata.map { getISOFromDate(it.releaseDate) }
        val allRequiredArtistNames = albums.flatMap { it.artists }.distinct()

        val artistIdMap: Map<String, UUID> =
            ArtistService.instance?.getOrBulkCreate(allRequiredArtistNames) ?: emptyMap()
        val imageMap: Map<String, UUID> =
            ImageService.instance?.getCoverHashes(uniqueCoverHashed) ?: emptyMap()

        val potentialAlbumRows = dbQuery {
            AlbumTable
                .select(AlbumTable.id, AlbumTable.name, AlbumTable.releaseDate)
                .where {
                    (AlbumTable.name inList uniqueAlbumNames) and
                            (AlbumTable.releaseDate inList uniqueReleaseDates)
                }
                .toList()
        }

        val potentialAlbumIds = potentialAlbumRows.map { it[AlbumTable.id].value }.toSet()

        val albumArtistLinks = dbQuery {
            AlbumArtistTable
                .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId)
                .where { AlbumArtistTable.albumId inList potentialAlbumIds }
                .toList()
        }

        val artistsByPotentialAlbumId = albumArtistLinks
            .groupBy({ it[AlbumArtistTable.albumId].value }, { it[AlbumArtistTable.artistId].value })
            .mapValues { (_, artistIds) -> artistIds.toSet() }

        val finalMatchMap = mutableMapOf<Pair<String, String?>, UUID>()

        for (row in potentialAlbumRows) {
            val albumId = row[AlbumTable.id].value
            val albumArtists = artistsByPotentialAlbumId[albumId] ?: emptySet()

            val inputAlbum = albums.first {
                it.name == row[AlbumTable.name] && getISOFromDate(it.releaseDate) == row[AlbumTable.releaseDate]
            }

            val requiredArtistIdsForInput = inputAlbum.artists.mapNotNull { artistIdMap[it] }.toSet()

            if (albumArtists == requiredArtistIdsForInput) {
                finalMatchMap[Pair(row[AlbumTable.name], row[AlbumTable.releaseDate])] = albumId
            }
        }

        val newAlbumsToInsert = albums.filter { album ->
            val key = Pair(album.name, getISOFromDate(album.releaseDate))
            !finalMatchMap.containsKey(key)
        }.distinctBy { Pair(it.name, it.releaseDate) }

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
            Pair(album.name, getISOFromDate(album.releaseDate)) to id
        }

        val finalCombinedIdMap = finalMatchMap + newAlbumIdLookupMap

        return albums.associateWith { album ->
            val key = Pair(album.name, getISOFromDate(album.releaseDate))
            finalCombinedIdMap[key]!!
        }
    }
}

