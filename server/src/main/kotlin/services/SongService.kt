package dev.dertyp.services

import dev.dertyp.data.InsertableSong
import dev.dertyp.data.Song
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.SongArtistTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.getDateFromISO
import dev.dertyp.getISOFromDate
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import io.ktor.util.logging.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SongService(database: Database) {
    private val logger = KtorSimpleLogger("getOrCreateSong")

    init {
        transaction(database) {
            SchemaUtils.create(SongTable)
            SchemaUtils.create(SongArtistTable)
        }
    }

    companion object {
        suspend fun mapSong(resultRow: ResultRow): Song {
            val id = resultRow[SongTable.id].value

            return Song(
                id = id,
                title = resultRow[SongTable.title],
                artists = dbQuery {
                    ArtistTable
                        .innerJoin(SongArtistTable)
                        .selectAll()
                        .where { SongArtistTable.songId eq id }
                        .map { mapArtist(it) }
                },
                album = dbQuery {
                    AlbumTable
                        .selectAll()
                        .where { AlbumTable.id eq resultRow[SongTable.albumId] }
                        .map { mapAlbum(it) }.single()
                },
                duration = resultRow[SongTable.duration],
                releaseDate = getDateFromISO(resultRow[SongTable.releaseDate]),
                lyrics = resultRow[SongTable.lyrics],
                path = resultRow[SongTable.filePath],
                originalUrl = resultRow[SongTable.originalUrl],
                trackNumber = resultRow[SongTable.trackNumber],
                discNumber = resultRow[SongTable.discNumber],
                copyright = resultRow[SongTable.copyright],
                sampleRate = resultRow[SongTable.sampleRate],
                bitsPerSample = resultRow[SongTable.bitsPerSample],
                bitRate = resultRow[SongTable.bitRate],
                coverId = resultRow[SongTable.cover]?.value,
            )
        }
    }

    suspend fun map(resultRow: ResultRow): Song = mapSong(resultRow)

    suspend fun byId(id: UUID): Song? = dbQuery {
        SongTable
            .selectAll()
            .where { SongTable.id eq id }
            .map { map(it) }.singleOrNull()
    }

    suspend fun byTitle(title: String): List<Song> = dbQuery {
        SongTable
            .selectAll()
            .where { SongTable.title eq title }
            .map { map(it) }
    }

    suspend fun searchByTitle(title: String): List<Song> = dbQuery {
        SongTable
            .selectAll()
            .where { SongTable.title like "%$title%" }
            .map { map(it) }
    }

    suspend fun byArtist(artistId: UUID): List<Song> = dbQuery {
        SongTable
            .join(
                SongArtistTable,
                JoinType.INNER,
                additionalConstraint = { SongTable.id eq SongArtistTable.songId }
            )
            .select(SongTable.columns)
            .where { SongArtistTable.artistId eq artistId }
            .map { map(it) }
    }

    suspend fun byAlbum(albumId: UUID): List<Song> = dbQuery {
        SongTable
            .selectAll()
            .where { SongTable.albumId eq albumId }
            .map { map(it) }
    }

    suspend fun allSongs(): List<Song> = dbQuery {
        SongTable.selectAll().map { map(it) }
    }

    suspend fun getOrCreate(song: InsertableSong): Song? {
        val songs = dbQuery {
            SongTable
                .join(
                    SongArtistTable,
                    JoinType.INNER,
                    additionalConstraint = { SongArtistTable.songId eq SongTable.id }
                )
                .join(
                    AlbumTable,
                    JoinType.INNER,
                    additionalConstraint = { AlbumTable.id eq SongTable.albumId }
                )
                .join(
                    ArtistTable,
                    JoinType.INNER,
                    additionalConstraint = { ArtistTable.id eq SongArtistTable.artistId }
                )
                .select(SongTable.columns)
                .withDistinct()
                .where { SongTable.title eq song.title }
                .andWhere { SongTable.discNumber eq song.discNumber }
                .andWhere { SongTable.trackNumber eq song.trackNumber }
                .andWhere { AlbumTable.name eq song.album.name }
                .andWhere { ArtistTable.name inList song.artists }
                .map { map(it) }
        }
        if (songs.isNotEmpty()) return songs.singleOrNull()

        val artists = song.artists.mapNotNull { artist ->
            ArtistService.instance?.getOrCreate(artist)
        }

        val albumId = AlbumService.instance?.getOrCreate(song.album)
        if (albumId == null) {
            logger.info("AlbumId is null $song")
            return null
        }

        val imageId = song.coverHash?.let { ImageService.instance?.byHash(it)?.id }

        val songId = dbQuery {
            SongTable.insertAndGetId {
                it[SongTable.title] = song.title
                it[SongTable.albumId] = albumId
                it[SongTable.duration] = song.duration
                it[SongTable.releaseDate] = getISOFromDate(song.releaseDate)
                it[SongTable.lyrics] = song.lyrics
                it[SongTable.filePath] = song.path
                it[SongTable.originalUrl] = song.originalUrl
                it[SongTable.trackNumber] = song.trackNumber
                it[SongTable.discNumber] = song.discNumber
                it[SongTable.copyright] = song.copyright
                it[SongTable.sampleRate] = song.sampleRate
                it[SongTable.bitsPerSample] = song.bitsPerSample
                it[SongTable.bitRate] = song.bitRate
                it[SongTable.cover] = imageId
            }
        }

        dbQuery {
            SongArtistTable.batchInsert(artists) { artist ->
                this[SongArtistTable.songId] = songId
                this[SongArtistTable.artistId] = artist
            }
        }

        return byId(songId.value)
    }
}