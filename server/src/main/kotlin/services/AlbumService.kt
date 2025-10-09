package dev.dertyp.services

import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.InsertableAlbum
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

class AlbumService(database: Database) {

    val albumArtistAlias = ArtistTable.alias("album_artist_alias")

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

    suspend fun byId(id: UUID): Album? = queryAlbums {
        where { AlbumTable.id eq id }
    }.singleOrNull()

    suspend fun byName(name: String): List<Album> = queryAlbums {
        where { AlbumTable.name eq name }
    }

    suspend fun searchByName(name: String): List<Album> = queryAlbums {
        where { AlbumTable.name like "%$name%" }
    }

    suspend fun byArtist(artistId: UUID): List<Album> = queryAlbums {
        where { AlbumArtistTable.artistId eq artistId }
    }

    suspend fun allAlbums(): List<Album> = queryAlbums()

    private suspend fun queryAlbums(query: Query.() -> Query = { this }) = dbQuery {
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

        if (rows.isEmpty()) return@dbQuery listOf()

        val albumIds = rows.map { it[AlbumTable.id].value }.distinct()

        val durationsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumDurations(albumIds)
        } else {
            emptyMap()
        }

        mapEagerly(rows, durationsByAlbumId, albumArtistAlias)
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

    suspend fun getOrCreate(album: InsertableAlbum): UUID? {
        val artists = album.artists.mapNotNull { artist ->
            ArtistService.instance?.getOrCreate(artist)
        }

        val getAlbums = suspend {
            dbQuery {
                AlbumTable
                    .innerJoin(AlbumArtistTable)
                    .innerJoin(ArtistTable)
                    .select(AlbumTable.columns)
                    .withDistinct()
                    .where { AlbumTable.name eq album.name }
                    .andWhere { ArtistTable.name inList album.artists }
                    .andWhere { AlbumTable.releaseDate eq getISOFromDate(album.releaseDate) }
                    .map { map(it) }
            }
        }

        val albums = getAlbums()
        if (albums.isNotEmpty()) return albums.singleOrNull()?.id


        val imageId = album.coverHash?.let { ImageService.instance?.byHash(it)?.id }

        val albumId = dbQuery {
            AlbumTable.insertAndGetId {
                it[name] = album.name
                it[songCount] = album.songCount
                it[releaseDate] = getISOFromDate(album.releaseDate)
                it[cover] = imageId
            }
        }

        dbQuery {
            AlbumArtistTable.batchInsert(artists) { artist ->
                this[AlbumArtistTable.albumId] = albumId.value
                this[AlbumArtistTable.artistId] = artist
            }
        }

        return albumId.value
    }
}

