package dev.dertyp.services

import dev.dertyp.core.date
import dev.dertyp.core.rankedSearchQuery
import dev.dertyp.core.toMap
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.getDateFromISO
import dev.dertyp.getISOFromDate
import dev.dertyp.services.AlbumService.Companion.calculateAlbumStats
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import dev.dertyp.services.metadata.IMetadataService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import java.io.File
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

class SongRpcService(private val user: User, private val songService: SongService) : ISongService {
    override suspend fun setLiked(
        id: UUID,
        liked: Boolean,
        addedAt: Instant?
    ): UserSong? = songService.setLiked(id, user.id, liked, addedAt)

    override suspend fun setLyrics(id: UUID, lyrics: List<String>): UserSong? =
        songService.setLyrics(id, user.id, lyrics)

    override suspend fun byId(id: UUID): UserSong? = songService.byId(id, user.id)

    override suspend fun byIds(ids: Collection<UUID>): PaginatedResponse<UserSong> = songService.byIds(ids, user.id)

    override suspend fun byTitle(
        page: Int,
        pageSize: Int,
        title: String
    ): PaginatedResponse<UserSong> = songService.byTitle(page, pageSize, title, user.id)

    override suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID
    ): PaginatedResponse<UserSong> = songService.byArtist(page, pageSize, artistId, user.id)

    override suspend fun byAlbum(
        page: Int,
        pageSize: Int,
        albumId: UUID
    ): PaginatedResponse<UserSong> = songService.byAlbum(page, pageSize, albumId, user.id)

    override suspend fun byPlaylist(
        page: Int,
        pageSize: Int,
        playlistId: UUID
    ): PaginatedResponse<UserSong> = songService.byPlaylist(page, pageSize, playlistId, user.id)

    override suspend fun byUserPlaylist(
        page: Int,
        pageSize: Int,
        playlistId: UUID
    ): PaginatedResponse<UserSong> = songService.byUserPlaylist(page, pageSize, playlistId, user.id)

    override suspend fun byTidalTrackIds(ids: Collection<String>): List<UserSong> =
        songService.byTidalTrackIds(ids, user.id)

    override suspend fun byTidalTracks(tracks: Collection<IMetadataService.Track>): List<UserSong> =
        songService.byTidalTracks(tracks, user.id)

    override suspend fun likedSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean
    ): PaginatedResponse<UserSong> = songService.likedSongs(page, pageSize, explicit, user.id)

    override suspend fun allSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean
    ): PaginatedResponse<UserSong> = songService.allSongs(page, pageSize, explicit, user.id)

    override suspend fun deleteSongs(ids: Collection<UUID>): Boolean = songService.deleteSongs(ids)

    override suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        liked: Boolean
    ): PaginatedResponse<UserSong> = songService.rankedSearch(page, pageSize, query, explicit, user.id, liked)

    override fun streamSong(id: UUID, offset: Long): Flow<ByteArray>? = songService.streamSong(id, offset)

    override suspend fun getStreamSize(id: UUID): Long = songService.getStreamSize(id)

    override fun likedSongIds(explicit: Boolean): Flow<UUID> = songService.likedSongIds(explicit, user.id)

    override fun songIdsByArtist(artistId: UUID): Flow<UUID> = songService.songIdsByArtist(artistId)

    override fun songIdsByAlbum(albumId: UUID): Flow<UUID> = songService.songIdsByAlbum(albumId)

    override fun songIdsByPlaylist(playlistId: UUID): Flow<UUID> = songService.songIdsByPlaylist(playlistId)

    override fun songIdsByUserPlaylist(playlistId: UUID): Flow<UUID> = songService.songIdsByUserPlaylist(playlistId)
}

class SongService : Service() {
    val albumArtistAlias = ArtistTable.alias("albumArtistAlias")
    val albumArtistAliasAlias = ArtistAliasTable.alias("albumArtistAliasAlias")

    companion object {
        fun mapSong(resultRow: ResultRow): Song {
            val id = resultRow[SongTable.id].value

            return Song(
                id = id,
                title = resultRow[SongTable.title].removeSuffix("\uD83C\uDD74").trimEnd(),
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

        fun mapUserSong(resultRow: ResultRow): UserSong {
            val id = resultRow[SongTable.id].value

            return UserSong(
                id = id,
                title = resultRow[SongTable.title].removeSuffix("\uD83C\uDD74").trimEnd(),
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
                isFavourite = resultRow.getOrNull(UserSongTable.isFavourite) ?: false,
                userSongCreatedAt = resultRow.getOrNull(UserSongTable.createdAt).date,
                userSongUpdatedAt = resultRow.getOrNull(UserSongTable.updatedAt).date,
            )
        }
    }

    inline fun <reified T : BaseSong> map(resultRow: ResultRow): BaseSong =
        if (T::class == UserSong::class) mapUserSong(resultRow) else mapSong(resultRow)

    private fun ColumnSet.userSong(userId: UUID) = leftJoin(
        UserSongTable,
        onColumn = { SongTable.id },
        otherColumn = { UserSongTable.songId },
        additionalConstraint = { UserSongTable.userId eq userId }
    )

    suspend fun setLiked(id: UUID, userId: UUID, liked: Boolean, addedAt: Instant? = Instant.now()): UserSong? {
        dbQuery {
            val inserted = UserSongTable.insertIgnore {
                it[UserSongTable.songId] = id
                it[UserSongTable.userId] = userId
                it[UserSongTable.isFavourite] = liked
                if (addedAt != null) it[UserSongTable.updatedAt] = addedAt.toEpochMilli()
            }.insertedCount == 1

            if (!inserted) {
                UserSongTable.update({
                    UserSongTable.userId eq userId and (UserSongTable.songId eq id)
                }) {
                    it[UserSongTable.isFavourite] = liked
                    it[UserSongTable.updatedAt] = (addedAt ?: Instant.now()).toEpochMilli()
                }
            }
        }

        return byId(id, userId)
    }

    suspend fun setLyrics(id: UUID, userId: UUID, lyrics: List<String>) = dbQuery {
        val lyricsString = lyrics.joinToString("\n")
        SongTable.update({ SongTable.id eq id }) {
            it[SongTable.lyrics] = lyricsString
            it[UserSongTable.updatedAt] = Instant.now().toEpochMilli()
        }

        return@dbQuery byId(id, userId).also {
            it?.let { song ->
                try {
                    val file = AudioFileIO.read(File(song.path))

                    file.tag.setField(FieldKey.LYRICS, lyricsString)

                    file.commit()
                } catch (e: Exception) {
                    logger.error("Failed to set lyrics for $id: ${e.message}", e)
                }
            }
        }
    }

    suspend fun byId(id: UUID): Song? = querySingle({ this }) {
        where { SongTable.id eq id }
    }

    suspend fun byIds(ids: Collection<UUID>, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(0, Int.MAX_VALUE, true, { userSong(userId) }) {
            where { SongTable.id inList ids }
        }

    suspend fun byId(id: UUID, userId: UUID): UserSong? = querySingle({ userSong(userId) }) {
        where { SongTable.id eq id }
    }

    suspend fun byTitle(page: Int, pageSize: Int, title: String, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, { userSong(userId) }) {
            where { SongTable.title eq title }
        }

    suspend fun byArtist(page: Int, pageSize: Int, artistId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, { userSong(userId) }) {
            val songIds = SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId eq artistId }
                .map { it[SongArtistTable.songId].value }

            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId eq artistId }
                .map { it[AlbumArtistTable.albumId].value }

            where { SongTable.id inList songIds }
            orWhere { SongTable.albumId inList albumIds }
            orderBy(SongTable.releaseDate, SortOrder.DESC)
            orderBy(SongTable.trackNumber, SortOrder.ASC)
        }

    suspend fun byAlbum(page: Int, pageSize: Int, albumId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, { userSong(userId) }) {
            where { SongTable.albumId eq albumId }
            orderBy(SongTable.discNumber, SortOrder.ASC)
            orderBy(SongTable.trackNumber, SortOrder.ASC)
        }

    suspend fun byPlaylist(page: Int, pageSize: Int, playlistId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, {
            leftJoin(PlaylistSongTable).userSong(userId)
        }) {
            where { PlaylistSongTable.playlistId eq playlistId }
            orderBy(PlaylistSongTable.position, SortOrder.ASC)
        }

    suspend fun byUserPlaylist(page: Int, pageSize: Int, playlistId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, {
            leftJoin(UserPlaylistSongTable).userSong(userId)
        }) {
            where { UserPlaylistSongTable.playlistId eq playlistId }
            orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
        }

    suspend fun byTidalTrackIds(ids: Collection<String>, userId: UUID): List<UserSong> =
        querySongs<UserSong>(0, Int.MAX_VALUE, true, { userSong(userId) }) {
            where {
                SongTable.originalUrl inList ids.map {
                    "https://tidal.com/browse/track/$it"
                }
            }
            orWhere {
                SongTable.originalUrl inList ids.map {
                    "https://tidal.com/track/$it"
                }
            }
        }.data

    suspend fun byTidalTracks(tracks: Collection<IMetadataService.Track>, userId: UUID): List<UserSong> =
        querySongs<UserSong>(0, Int.MAX_VALUE, true, { userSong(userId) }) {
            where {
                tracks.map { track ->
                    (SongTable.originalUrl eq "https://tidal.com/browse/track/${track.id}") or
                            ((SongTable.title eq track.title) and
                                    (SongTable.duration eq track.duration.inWholeMilliseconds))
                }.reduce { acc, op -> acc or op }
            }
        }.data

    suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID,
        liked: Boolean = false
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, explicit, { userSong(userId) }) {
            rankedSearchQuery(
                query,
                listOf(10, 5, 5, 5, 5),
                listOf(
                    SongTable.title,
                    ArtistTable.name,
                    AlbumTable.name,
                    ArtistAliasTable.name,
                    albumArtistAliasAlias[ArtistAliasTable.name]
                )
            ).let { it ->
                if (liked) it.andWhere { UserSongTable.isFavourite eq true }
                else it
            }
        }

    suspend fun likedSongs(page: Int, pageSize: Int, explicit: Boolean, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, { userSong(userId) },
        ) {
            where { UserSongTable.isFavourite eq true }
            orderBy(UserSongTable.updatedAt to SortOrder.DESC)
        }

    suspend fun allSongs(page: Int, pageSize: Int, explicit: Boolean, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, { userSong(userId) },
            query = {
                orderBy(SongTable.inserted, SortOrder.DESC)
                orderBy(SongTable.id, SortOrder.ASC)
            }
        )

    @Suppress("DuplicatedCode")
    suspend fun deleteSongs(ids: Collection<UUID>): Boolean = dbQuery {
        val paths = SongTable
            .select(SongTable.id, SongTable.filePath)
            .where { SongTable.id inList ids }
            .map { it[SongTable.filePath] }

        logger.info("Found ${paths.size} files to delete.")

        val deletedSongs = SongTable.deleteWhere {
            SongTable.id inList ids
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
                    } (${file.delete()})"
                )
            if (file.exists())
                logger.info("Trying to delete ${file.absolutePath} (${file.delete()})")
            if (file.parentFile.list().isEmpty())
                logger.info("Trying to delete parent ${file.parentFile.absolutePath} (${file.parentFile.delete()})")
        }

        deletedSongs == ids.size
    }

    fun streamSong(id: UUID, offset: Long): Flow<ByteArray>? {
        val song = runBlocking { byId(id) } ?: return null
        val file = File(song.path)
        if (!file.exists()) return null

        return flow {
            val buffer = ByteArray(4096)
            file.inputStream().use { input ->
                input.skip(offset)
                var bytesRead = input.read(buffer)
                while (bytesRead != -1) {
                    emit(buffer.copyOf(bytesRead))
                    bytesRead = input.read(buffer)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getStreamSize(id: UUID): Long {
        val song = byId(id) ?: return 0
        val file = File(song.path)
        if (!file.exists()) return 0
        return file.length()
    }

    fun likedSongIds(explicit: Boolean, userId: UUID): Flow<UUID> = flow {
        dbQuery {
            SongTable
                .leftJoin(UserSongTable, onColumn = { SongTable.id }, otherColumn = { UserSongTable.songId })
                .select(SongTable.id)
                .where { UserSongTable.userId eq userId }
                .andWhere { UserSongTable.isFavourite eq true }
                .let {
                    if (!explicit) it.andWhere { SongTable.explicit eq false }
                    else it
                }
                .orderBy(UserSongTable.updatedAt, SortOrder.DESC)
                .fetchBatchedResults(1000)
                .forEach { batch ->
                    batch.forEach {
                        emit(it[SongTable.id].value)
                    }
                }
        }
    }

    fun songIdsByArtist(artistId: UUID): Flow<UUID> = flow {
        val (songIds, albumIds) = dbQuery {
            val s = SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId eq artistId }
                .map { it[SongArtistTable.songId].value }

            val a = AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId eq artistId }
                .map { it[AlbumArtistTable.albumId].value }
            s to a
        }

        if (songIds.isEmpty() && albumIds.isEmpty()) return@flow

        dbQuery {
            val query = SongTable.select(SongTable.id)
            val op1 = if (songIds.isNotEmpty()) (SongTable.id inList songIds) else null
            val op2 = if (albumIds.isNotEmpty()) (SongTable.albumId inList albumIds) else null

            val op = if (op1 != null && op2 != null) op1 or op2
            else op1 ?: op2

            if (op != null) {
                query.where(op)
                    .orderBy(SongTable.releaseDate, SortOrder.DESC)
                    .orderBy(SongTable.trackNumber, SortOrder.ASC)
                    .fetchBatchedResults(1000)
                    .forEach { batch ->
                        batch.forEach {
                            emit(it[SongTable.id].value)
                        }
                    }
            }
        }
    }

    fun songIdsByAlbum(albumId: UUID): Flow<UUID> = flow {
        dbQuery {
            SongTable
                .select(SongTable.id)
                .where { SongTable.albumId eq albumId }
                .orderBy(SongTable.discNumber, SortOrder.ASC)
                .orderBy(SongTable.trackNumber, SortOrder.ASC)
                .fetchBatchedResults(1000)
                .forEach { batch ->
                    batch.forEach {
                        emit(it[SongTable.id].value)
                    }
                }
        }
    }

    fun songIdsByPlaylist(playlistId: UUID): Flow<UUID> = flow {
        dbQuery {
            PlaylistSongTable
                .select(PlaylistSongTable.songId)
                .where { PlaylistSongTable.playlistId eq playlistId }
                .orderBy(PlaylistSongTable.position, SortOrder.ASC)
                .fetchBatchedResults(1000)
                .forEach { batch ->
                    batch.forEach {
                        emit(it[SongTable.id].value)
                    }
                }
        }
    }

    fun songIdsByUserPlaylist(playlistId: UUID): Flow<UUID> = flow {
        dbQuery {
            UserPlaylistSongTable
                .select(UserPlaylistSongTable.songId)
                .where { UserPlaylistSongTable.playlistId eq playlistId }
                .orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
                .fetchBatchedResults(1000)
                .forEach { batch ->
                    batch.forEach {
                        emit(it[SongTable.id].value)
                    }
                }
        }
    }

    private suspend inline fun <reified T : BaseSong> querySingle(
        crossinline columnSet: suspend ColumnSet.() -> ColumnSet,
        crossinline query: Query.() -> Query
    ) =
        querySongs<T>(0, Int.MAX_VALUE, true, columnSet = columnSet, query = query).data.singleOrNull()

    private suspend inline fun <reified T : BaseSong> querySongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        crossinline columnSet: suspend ColumnSet.() -> ColumnSet,
        crossinline query: suspend Query.() -> Query
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
            .leftJoin(ArtistAliasTable)
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
            .leftJoin(
                albumArtistAliasAlias,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { albumArtistAliasAlias[ArtistAliasTable.artistId] }
            )
            .columnSet()
            .selectAll()
            .query()
            .toList()

        if (rows.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = 0,
            page = page,
            pageSize = pageSize,
        )

        val albumIds = rows.mapNotNull { it.getOrNull(AlbumTable.id)?.value }.distinct()

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val data = mapEagerly<T>(rows, albumArtistAlias, statsByAlbumId, explicit).distinctBy { it.id }

        PaginatedResponse(
            data = data.drop(page * pageSize).take(pageSize),
            total = data.size,
            page = page,
            pageSize = pageSize,
            hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
        )
    }

    private inline fun <reified T : BaseSong> mapEagerly(
        rows: List<ResultRow>,
        albumArtistAlias: Alias<ArtistTable>,
        albumStats: Map<UUID, Pair<Long, Long>>,
        explicit: Boolean = false
    ): List<T> {
        val songMap = mutableMapOf<UUID, BaseSong>()
        val songArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()
        val albumArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()

        for (row in rows) {
            val songId = row[SongTable.id].value
            val albumId = row[SongTable.albumId].value

            songMap.getOrPut(songId) {
                val album = mapAlbum(row)
                val song = map<T>(row)

                @Suppress("UNCHECKED_CAST")
                when (song) {
                    is UserSong -> song.copy(album = album)
                    is Song -> song.copy(album = album)
                    else -> throw Exception("Unknown song type: $row")
                }
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
                totalDuration = albumStats[song.album!!.id]?.first ?: -1L,
                totalSize = albumStats[song.album!!.id]?.second ?: -1L
            )

            when (song) {
                is Song -> song.copy(album = albumWithArtists, artists = songArtists)
                is UserSong -> song.copy(album = albumWithArtists, artists = songArtists)
                else -> throw Exception("Unknown song type: $song")
            }
        }.groupBy {
            listOf(
                it.title.removeSuffix("\uD83C\uDD74").trim(),
                it.releaseDate,
                it.duration,
                it.trackNumber,
                it.discNumber,
                it.album?.name
            )
        }.mapNotNull { (_, songList) ->
            @Suppress("UNCHECKED_CAST")
            if (explicit) songList.find { it.explicit } as? T ?: songList.first() as T
            else songList.find { !it.explicit } as T
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
                SongTable.originalUrl,
                AlbumTable.name
            )
            .withDistinct()
            .where { SongTable.originalUrl inList songs.map { it.originalUrl }.filter { it.isNotBlank() } }
            .orWhere {
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

                val metadataMatch =
                    (song.originalUrl.isNotBlank() && row[SongTable.originalUrl] == song.originalUrl) || (
                            row[SongTable.originalUrl].isBlank() &&
                                    row[SongTable.title] == song.title &&
                                    row[SongTable.trackNumber] == song.trackNumber &&
                                    row[SongTable.discNumber] == song.discNumber &&
                                    row[SongTable.explicit] == song.explicit &&
                                    albumName == song.album.name
                            )

                if (metadataMatch) {
                    existingSongMap[song] = songId
                    return@firstOrNull true
                }
                return@firstOrNull false
            }
        }
        return@dbQuery existingSongMap
    }

    suspend fun createBatch(songs: List<InsertableSong>): Map<UUID, InsertableSong> = coroutineScope {
        if (songs.isEmpty()) return@coroutineScope emptyMap()

        val artistService = get<ArtistService>()
        val albumService = get<AlbumService>()
        val imageService = get<ImageService>()

        val uniqueArtistNames = songs.flatMap { it.artists }.distinct()
        val uniqueAlbums = songs.map { it.album }.distinctBy {
            listOf(
                it.name,
                it.releaseDate,
                it.songCount,
                it.artists.sorted().joinToString(", "),
                it.originalId
            )
        }
        val uniqueCoverHashes = songs.map { it.coverHash }.distinct()

        val artistIdMap: Map<String, UUID> = uniqueArtistNames
            .chunked(maxBatchSize)
            .map { batch ->
                async {
                    artistService.getOrBulkCreate(batch).entries
                }
            }
            .awaitAll()
            .flatten()
            .toMap()

        val albumIdMap: Map<InsertableAlbum, UUID> = uniqueAlbums
            .chunked(maxBatchSize)
            .map { batch ->
                async {
                    albumService.getOrBulkCreate(batch).entries
                }
            }
            .awaitAll()
            .flatten()
            .toMap()

        val imageIdMap: Map<String, UUID> = uniqueCoverHashes
            .filterNotNull()
            .chunked(maxBatchSize)
            .map { batch ->
                async {
                    imageService.getCoverHashes(batch).entries
                }
            }
            .awaitAll()
            .flatten()
            .toMap()

        val existingSongMap = songs
            .chunked(maxBatchSize / 3)
            .map { batch ->
                async {
                    logger.info("Checking ${batch.size} songs")
                    bulkFindExistingSongs(batch).entries
                }
            }
            .awaitAll()
            .flatten()
            .toMap()

        val newSongs = songs.filter { it !in existingSongMap.keys }

        if (newSongs.isEmpty()) return@coroutineScope emptyMap()

        val uniqueSongs = newSongs
            .groupBy { song ->
                listOf(
                    song.title,
                    song.album.name,
                    song.album.originalId,
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
                    Pair(songId, artistId)
                }
            }
        }.distinct()

        dbQuery {
            SongArtistTable.batchInsert(songArtistLinks) { (songId, artistId) ->
                this[SongArtistTable.songId] = songId
                this[SongArtistTable.artistId] = artistId
            }
        }

        dbQuery {
            AlbumTable.deleteWhere {
                notExists(
                    SongTable.select(SongTable.id).where {
                        SongTable.albumId eq AlbumTable.id
                    }
                )
            }
        }

        insertedSongs.toMap()
    }
}