package dev.dertyp.services

import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.getDateFromISO
import dev.dertyp.getISOFromDate
import dev.dertyp.services.AlbumService.Companion.calculateAlbumStats
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SongService(database: Database) : Service() {
    val albumArtistAlias = ArtistTable.alias("album_artist_alias")

    init {
        transaction(database) {
            execInBatch(listOf("PRAGMA foreign_keys = ON"))
            SchemaUtils.create(SongTable)
            SchemaUtils.create(SongArtistTable)
            SchemaUtils.create(TranscodedSongTable)
        }

        instance = this
    }

    companion object {
        var instance: SongService? = null
            private set

        fun mapSong(resultRow: ResultRow): Song {
            val id = resultRow[SongTable.id].value

            return Song(
                id = id,
                title = resultRow[SongTable.title],
                artists = listOf(),
                album = null,
                duration = resultRow[SongTable.duration],
                explicit = resultRow[SongTable.explicit],
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
                fileSize = resultRow[SongTable.fileSize],
                coverId = resultRow[SongTable.cover]?.value,
            )
        }
    }

    fun map(resultRow: ResultRow): Song = mapSong(resultRow)

    suspend fun byId(id: UUID): Song? = querySingle() {
        where { SongTable.id eq id }
    }

    suspend fun byTitle(page: Int, pageSize: Int, title: String): PaginatedResponse<Song> =
        querySongs(page, pageSize, true) {
            where { SongTable.title eq title }
        }

    suspend fun byArtist(page: Int, pageSize: Int, artistId: UUID): PaginatedResponse<Song> =
        querySongs(page, pageSize, true) {
            where { SongArtistTable.artistId eq artistId }
        }

    suspend fun byAlbum(page: Int, pageSize: Int, albumId: UUID): PaginatedResponse<Song> =
        querySongs(page, pageSize, true) {
            where { SongTable.albumId eq albumId }
            orderBy(SongTable.trackNumber, SortOrder.ASC)
        }

    suspend fun rankedSearch(page: Int, pageSize: Int, query: String, explicit: Boolean): PaginatedResponse<Song> =
        querySongs(page, pageSize, explicit) {
            rankedSearchQuery(
                query,
                listOf(10, 5, 5),
                listOf(SongTable.title, ArtistTable.name, AlbumTable.name)
            )
        }

    suspend fun allSongs(page: Int, pageSize: Int, explicit: Boolean): PaginatedResponse<Song> = querySongs(page, pageSize, explicit)

    private suspend fun querySingle(query: Query.() -> Query) =
        querySongs(0, Int.MAX_VALUE, true, query).data.singleOrNull()

    private suspend fun querySongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        query: Query.() -> Query = { this }
    ) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1

        val rows = SongTable
            .leftJoin(
                AlbumTable,
                onColumn = { SongTable.albumId },
                otherColumn = { AlbumTable.id }
            )
            .leftJoin(SongArtistTable)
            .leftJoin(
                ArtistTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .leftJoin(
                AlbumArtistTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumArtistTable.albumId }
            )
            .leftJoin(
                albumArtistAlias,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { albumArtistAlias[ArtistTable.id] }
            )
            .select(
                SongTable.columns +
                        AlbumTable.columns +
                        ArtistTable.columns +
                        albumArtistAlias.columns
            )
            .query()
            .toList()

        if (rows.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            page = page,
            pageSize = pageSize,
        )

        val albumIds = rows.mapNotNull { it.getOrNull(AlbumTable.id)?.value }.distinct()

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val data = mapEagerly(rows, albumArtistAlias, statsByAlbumId, explicit)

        PaginatedResponse(
            data = data.drop(page * pageSize).take(pageSize),
            page = page,
            pageSize = pageSize,
            hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
        )
    }

    private fun mapEagerly(
        rows: List<ResultRow>,
        albumArtistAlias: Alias<ArtistTable>,
        albumStats: Map<UUID, Pair<Long, Long>>,
        explicit: Boolean = false
    ): List<Song> {
        val songMap = mutableMapOf<UUID, Song>()
        val songArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()
        val albumArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()

        for (row in rows) {
            val songId = row[SongTable.id].value
            val albumId = row[SongTable.albumId].value

            songMap.getOrPut(songId) {
                val album = mapAlbum(row)
                map(row).copy(album = album)
            }

            if (row.getOrNull(ArtistTable.id) != null) {
                val artist = mapArtist(row, ArtistTable)
                if (artist !in songArtistsMap.getOrDefault(songId, emptyList())) {
                    songArtistsMap.getOrPut(songId) { mutableListOf() }.add(artist)
                }
            }

            if (row.getOrNull(albumArtistAlias[ArtistTable.id]) != null) {
                val artist = mapArtist(row, albumArtistAlias)
                if (artist !in albumArtistsMap.getOrDefault(albumId, emptyList())) {
                    albumArtistsMap.getOrPut(albumId) { mutableListOf() }.add(artist)
                }
            }
        }

        return songMap.values.map { song ->
            val albumArtists = albumArtistsMap[song.album?.id] ?: listOf()
            val songArtists = songArtistsMap[song.id]?.distinctBy { it.id } ?: listOf()

            val albumWithArtists = song.album?.copy(
                artists = albumArtists,
                totalDuration = albumStats[song.album.id]?.first ?: -1L,
                totalSize = albumStats[song.album.id]?.second ?: -1L
            )

            song.copy(
                album = albumWithArtists,
                artists = songArtists
            )
        }.groupBy {
            listOf(it.title, it.releaseDate, it.duration, it.trackNumber, it.discNumber, it.album?.id)
        }.mapNotNull { (_, songList) ->
            if (explicit) songList.find { it.explicit } ?: songList.first()
            else songList.find { !it.explicit }
        }
    }

    private suspend fun bulkFindExistingSongs(songs: List<InsertableSong>): Map<InsertableSong, UUID> = dbQuery {
        val rows = SongTable
            .innerJoin(
                AlbumTable,
                onColumn = { SongTable.albumId },
                otherColumn = { AlbumTable.id }
            )
            .innerJoin(SongArtistTable)
            .innerJoin(
                ArtistTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .select(
                SongTable.id,
                SongTable.title,
                SongTable.trackNumber,
                SongTable.discNumber,
                SongTable.explicit,
                AlbumTable.name
            )
            .withDistinct()
            .where { SongTable.title inList songs.map { it.title } }
            .andWhere { SongTable.trackNumber inList songs.map { it.trackNumber } }
            .andWhere { SongTable.discNumber inList songs.map { it.discNumber } }
            .toList()

        val existingSongMap = mutableMapOf<InsertableSong, UUID>()

        for (song in songs) {
            rows.firstOrNull { row ->
                val albumName = row[AlbumTable.name]
                val songId = row[SongTable.id].value

                val metadataMatch = row[SongTable.title] == song.title &&
                        row[SongTable.trackNumber] == song.trackNumber &&
                        row[SongTable.discNumber] == song.discNumber &&
                        row[SongTable.explicit] == song.explicit &&
                        albumName == song.album.name

                if (metadataMatch) {
                    existingSongMap[song] = songId
                    return@firstOrNull true
                }
                return@firstOrNull false
            }
        }
        return@dbQuery existingSongMap
    }

    suspend fun createBatch(songs: List<InsertableSong>): Map<UUID, InsertableSong> {
        if (songs.isEmpty()) return emptyMap()

        val uniqueArtistNames = songs.flatMap { it.artists }.distinct()
        val uniqueAlbums = songs.map { it.album }.distinctBy {
            listOf(
                it.name,
                it.releaseDate,
                it.songCount,
                it.artists.sorted().joinToString(", ")
            )
        }
        val uniqueCoverHashes = songs.map { it.coverHash }.distinct()

        val artistIdMap: Map<String, UUID> = ArtistService.instance?.getOrBulkCreate(uniqueArtistNames) ?: emptyMap()
        val albumIdMap: Map<InsertableAlbum, UUID> = AlbumService.instance?.getOrBulkCreate(uniqueAlbums) ?: emptyMap()
        val imageIdMap: Map<String, UUID> =
            ImageService.instance?.getCoverHashes(uniqueCoverHashes.filterNotNull()) ?: emptyMap()

        val existingSongMap = bulkFindExistingSongs(songs)

        val newSongs = songs.filter { it !in existingSongMap.keys }

        if (newSongs.isEmpty()) return emptyMap()

        val uniqueSongs = newSongs
            .groupBy { song ->
                listOf(
                    song.title,
                    song.album.name,
                    song.trackNumber,
                    song.discNumber,
                    song.duration,
                    song.explicit,
                )
            }
            .map { (_, songs) ->
                songs.maxByOrNull { it.bitRate }
            }
            .filterNotNull()

        val filteredSongs = uniqueSongs.filter {
            if (albumIdMap[it.album] == null) logger.info("${it.title} (${it.album.name}) has no album.")
            albumIdMap[it.album] != null
        }

        val songInsertResult: List<ResultRow> = dbQuery {
            SongTable.batchInsert(filteredSongs) { song ->
                val albumId = albumIdMap[song.album]
                val imageId = song.coverHash?.let { imageIdMap[it] }

                this[SongTable.title] = song.title
                this[SongTable.albumId] = albumId!!
                this[SongTable.duration] = song.duration
                this[SongTable.explicit] = song.explicit
                this[SongTable.releaseDate] = getISOFromDate(song.releaseDate)
                this[SongTable.lyrics] = song.lyrics
                this[SongTable.filePath] = song.path
                this[SongTable.originalUrl] = song.originalUrl
                this[SongTable.trackNumber] = song.trackNumber
                this[SongTable.discNumber] = song.discNumber
                this[SongTable.copyright] = song.copyright
                this[SongTable.sampleRate] = song.sampleRate
                this[SongTable.bitsPerSample] = song.bitsPerSample
                this[SongTable.bitRate] = song.bitRate
                this[SongTable.fileSize] = song.fileSize
                this[SongTable.cover] = imageId
            }
        }

        val insertedSongs: List<Pair<UUID, InsertableSong>> =
            songInsertResult.map { it[SongTable.id].value to filteredSongs[songInsertResult.indexOf(it)] }

        val songArtistLinks = insertedSongs.flatMap { (songId, songData) ->
            songData.artists.mapNotNull { artistName ->
                artistIdMap[artistName]?.let { artistId ->
                    Triple(songId, artistId, artistName)
                }
            }
        }.distinct()

        dbQuery {
            SongArtistTable.batchInsert(songArtistLinks) { (songId, artistId, _) ->
                this[SongArtistTable.songId] = songId
                this[SongArtistTable.artistId] = artistId
            }
        }

        return insertedSongs.toMap()
    }
}