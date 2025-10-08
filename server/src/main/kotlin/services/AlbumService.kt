package dev.dertyp.services

import dev.dertyp.data.Album
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.Song
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

        suspend fun mapAlbum(resultRow: ResultRow): Album {
            val id = resultRow[AlbumTable.id].value
            val songs = dbQuery {
                SongTable
                    .select(SongTable.duration)
                    .where {
                        SongTable.albumId eq id
                    }
                    .map { it[SongTable.duration] }
            }

            return Album(
                id = id,
                name = resultRow[AlbumTable.name],
                releaseDate = getDateFromISO(resultRow[AlbumTable.releaseDate]),
                artists = dbQuery {
                    ArtistTable
                        .innerJoin(AlbumArtistTable)
                        .selectAll().where { AlbumArtistTable.albumId eq id }
                        .map { mapArtist(it) }
                },
                songCount = songs.size,
                totalDuration = songs.fold(0) { sum, song -> sum + song },
                coverId = resultRow[AlbumTable.cover]?.value
            )
        }
    }

    suspend fun map(resultRow: ResultRow): Album = mapAlbum(resultRow)

    suspend fun byId(id: UUID): Album? = dbQuery {
        AlbumTable
            .selectAll()
            .where { AlbumTable.id eq id }
            .map { map(it) }.singleOrNull()
    }

    suspend fun byName(name: String): List<Album> = dbQuery {
        AlbumTable
            .selectAll()
            .where { AlbumTable.name eq name }
            .map { map(it) }
    }

    suspend fun searchByName(name: String): List<Album> = dbQuery {
        AlbumTable
            .selectAll()
            .where { AlbumTable.name like "%$name%" }
            .map { map(it) }
    }

    suspend fun byArtist(artistId: UUID): List<Song> = dbQuery {
        listOf()
    }

    suspend fun allAlbums(): List<Album> = dbQuery {
        AlbumTable.selectAll().map { map(it) }
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

