package dev.dertyp.services

import dev.dertyp.core.withArtistNames
import dev.dertyp.data.Artist
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableSong
import dev.dertyp.data.Song
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.getDateFromISO
import dev.dertyp.getISOFromDate
import dev.dertyp.services.AlbumService.Companion.calculateAlbumDurations
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import io.ktor.util.logging.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SongService(database: Database) {
    private val logger = KtorSimpleLogger("getOrCreateSong")

    val albumArtistAlias = ArtistTable.alias("album_artist_alias")

    init {
        transaction(database) {
            SchemaUtils.create(SongTable)
            SchemaUtils.create(SongArtistTable)
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

    fun map(resultRow: ResultRow): Song = mapSong(resultRow)

    suspend fun byId(id: UUID): Song? = querySongs {
        where { SongTable.id eq id }
    }.singleOrNull()

    suspend fun byTitle(title: String): List<Song> = querySongs {
        where { SongTable.title eq title }
    }

    suspend fun searchByTitle(title: String): List<Song> = querySongs {
        where { SongTable.title like "%$title%" }
    }

    suspend fun byArtist(artistId: UUID): List<Song> = querySongs {
        where { SongArtistTable.artistId eq artistId }
    }

    suspend fun byAlbum(albumId: UUID): List<Song> = querySongs {
        where { SongTable.albumId eq albumId }
    }

    suspend fun allSongs(): List<Song> = querySongs()

    private suspend fun querySongs(query: Query.() -> Query = { this }) = dbQuery {
        val rows = SongTable
            .leftJoin(AlbumTable, onColumn = { albumId }, otherColumn = { AlbumTable.id })

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

        if (rows.isEmpty()) return@dbQuery listOf()

        val albumIds = rows.mapNotNull { it.getOrNull(AlbumTable.id)?.value }.distinct()

        val durationsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumDurations(albumIds)
        } else {
            emptyMap()
        }

        mapEagerly(rows, albumArtistAlias, durationsByAlbumId)
    }

    private fun mapEagerly(
        rows: List<ResultRow>,
        albumArtistAlias: Alias<ArtistTable>,
        durations: Map<UUID, Long>
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
                totalDuration = durations[song.album.id] ?: -1L,
            )

            song.copy(
                album = albumWithArtists,
                artists = songArtists
            )
        }
    }

    private suspend fun bulkFindExistingSongs(
        songs: List<InsertableSong>,
        albumIdMap: Map<InsertableAlbum, UUID>
    ): Map<InsertableSong, UUID> = dbQuery {
        val rows = SongTable
            .innerJoin(AlbumTable, onColumn = { albumId }, otherColumn = { AlbumTable.id })
            .innerJoin(SongArtistTable)
            .innerJoin(ArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistTable.id })
            .select(SongTable.id, SongTable.title, SongTable.trackNumber, SongTable.discNumber, AlbumTable.name)
            .withDistinct()
            .where {
                (SongTable.title inList songs.map { it.title }) and
                        (SongTable.trackNumber inList songs.map { it.trackNumber }) and
                        (SongTable.discNumber inList songs.map { it.discNumber })
            }
            .toList()

        val existingSongMap = mutableMapOf<InsertableSong, UUID>()

        for (song in songs) {
            rows.firstOrNull { row ->
                val albumName = row[AlbumTable.name]
                val songId = row[SongTable.id].value

                val metadataMatch = row[SongTable.title] == song.title &&
                        row[SongTable.trackNumber] == song.trackNumber &&
                        row[SongTable.discNumber] == song.discNumber &&
                        albumName == song.album.name

                if (metadataMatch) {
                    val inputAlbumId = albumIdMap[song.album]
                    val simpleMatch = row[SongTable.title] == song.title &&
                            row[SongTable.trackNumber] == song.trackNumber &&
                            row[SongTable.discNumber] == song.discNumber &&
                            (inputAlbumId != null && songId == inputAlbumId)

                    if (simpleMatch) {
                        existingSongMap[song] = songId
                        return@firstOrNull true
                    }
                }
                return@firstOrNull false
            }
        }
        return@dbQuery existingSongMap
    }

    suspend fun createBatch(songs: List<InsertableSong>): List<UUID> {
        if (songs.isEmpty()) return emptyList()

        val uniqueArtistNames = songs.flatMap { it.artists }.distinct()
        val uniqueAlbums = songs.map { it.album }.distinctBy { it.name }
        val uniqueCoverHashes = songs.map { it.coverHash }.distinct()

        val artistIdMap: Map<String, UUID> = ArtistService.instance?.getOrBulkCreate(uniqueArtistNames) ?: emptyMap()
        val albumIdMap: Map<InsertableAlbum, UUID> = AlbumService.instance?.getOrBulkCreate(uniqueAlbums) ?: emptyMap()
        val imageIdMap: Map<String, UUID> =
            ImageService.instance?.getCoverHashes(uniqueCoverHashes.filterNotNull()) ?: emptyMap()

        val existingSongMap = bulkFindExistingSongs(songs, albumIdMap)
        val existingSongIds = existingSongMap.values.toList()

        val newSongs = songs.filter { it !in existingSongMap.keys }
        if (newSongs.isEmpty()) return existingSongIds

        val uniqueSongs = songs.distinctBy { song ->
            listOf(
                song.title,
                song.album.name,
                song.trackNumber,
                song.discNumber,
            )
        }

        val filteredSongs = uniqueSongs.filter {
            albumIdMap[it.album] != null
        }

        val songInsertResult: List<ResultRow> = dbQuery {
            SongTable.batchInsert(filteredSongs) { song ->
                val albumId = albumIdMap[song.album]
                val imageId = song.coverHash?.let { imageIdMap[it] }

                this[SongTable.title] = song.title
                this[SongTable.albumId] = albumId!!
                this[SongTable.duration] = song.duration
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
                this[SongTable.cover] = imageId
            }
        }

        val insertedSongs: List<Pair<UUID, InsertableSong>> =
            songInsertResult.map { it[SongTable.id].value to filteredSongs[songInsertResult.indexOf(it)] }

        val insertedSongIds = insertedSongs.map { it.first }

        val songArtistLinks = insertedSongs.flatMap { (songId, songData) ->
            songData.artists.mapNotNull { artistName ->
                if (songData.title == "GNRFT") {
                    logger.warn("GNRFT: $artistName (${artistIdMap[artistName]})")
                    logger.warn("GNRFT: ${songData.path} ($songId)")
                }
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

        return insertedSongIds
    }

    suspend fun getOrCreate(song: InsertableSong): UUID? {
        val songs = dbQuery {
            SongTable
                .innerJoin(
                    AlbumTable,
                    onColumn = { SongTable.albumId },
                    otherColumn = { AlbumTable.id }
                )
                .select(SongTable.id)
                .where {
                    (SongTable.title eq song.title) and
                            (SongTable.discNumber eq song.discNumber) and
                            (SongTable.trackNumber eq song.trackNumber) and
                            (AlbumTable.name eq song.album.name)
                }
                .withArtistNames(song.artists)
                .withDistinct()
                .map { it[SongTable.id].value }
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

        return songId.value
    }
}