package dev.dertyp.services

import dev.dertyp.*
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.plugins.SongLibrary
import dev.dertyp.routing.RestFileProvider
import dev.dertyp.services.AlbumService.Companion.calculateAlbumStats
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.utils.ColorUtils
import dev.dertyp.utils.LogParam
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.IOException
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class SongRpcService(private val user: User, private val songService: SongService) : ISongService, RestFileProvider {
    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        if (methodName == "streamSong") {
            val id = args[0] as? UUID ?: return null
            val song = songService.byId(id) ?: return null
            val file = File(song.path)
            if (!file.exists()) return null
            return StreamInfo(
                file = file,
                contentType = withContext(Dispatchers.IO) {
                    ContentType.parse(Files.probeContentType(file.toPath()) ?: "application/octet-stream")
                },
                contentLength = file.length(),
                fileName = file.name
            )
        }
        if (methodName == "downloadSong") {
            val id = args[0] as? UUID ?: return null
            val quality = args[1] as? Int ?: return null
            val force = args.getOrNull(4) as? Boolean ?: true
            val song = songService.byId(id) ?: return null
            val file = File(song.path)
            if (!file.exists()) return null
            if (quality > 0) {
                val environment = songService.get<ApplicationEnvironment>()
                val transcodeInfo = AudioUtils.transcodeFlacToOpus(environment, file, quality, force)
                AudioUtils.insertTranscodedSong(id, transcodeInfo.file, quality)
                return transcodeInfo
            }
            return StreamInfo(
                file = file,
                contentType = withContext(Dispatchers.IO) {
                    ContentType.parse(Files.probeContentType(file.toPath()) ?: "application/octet-stream")
                },
                contentLength = file.length(),
                fileName = file.name
            )
        }
        return null
    }

    override suspend fun setLiked(
        id: UUID,
        liked: Boolean,
        addedAt: Instant?
    ): UserSong? = songService.setLikedReturning(id, user.id, liked, addedAt)

    override suspend fun setLyrics(id: UUID, @LogParam("size") lyrics: List<String>): UserSong? =
        songService.setLyrics(id, user.id, lyrics)

    override suspend fun setArtists(id: UUID, artistIds: List<UUID>): UserSong? =
        songService.setArtists(id, artistIds, user.id)

    override suspend fun setMusicBrainzId(id: UUID, musicBrainzId: UUID?): UserSong? =
        songService.setMusicBrainzId(id, musicBrainzId, user.id)

    override suspend fun fetchMusicBrainzId(id: UUID): UserSong? =
        songService.fetchMusicBrainzId(id, user.id, HttpClientPriority.HIGH)

    override suspend fun byId(id: UUID): UserSong? = songService.byId(id, user.id)

    override suspend fun byMusicBrainzId(musicBrainzId: UUID): List<UserSong> =
        songService.byMusicBrainzId(musicBrainzId, user.id)

    override suspend fun byIds(@LogParam("size") ids: List<UUID>): List<UserSong> =
        songService.byIds(ids, user.id)

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

    override suspend fun likedByArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        explicit: Boolean
    ): PaginatedResponse<UserSong> = songService.likedByArtist(page, pageSize, artistId, explicit, user.id)

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

    override suspend fun byOriginalIds(@LogParam("size") ids: Collection<PrefixedId>): List<UserSong> =
        songService.byOriginalIds(ids, user.id)

    override suspend fun byOriginalUrls(@LogParam("size") urls: Collection<String>): Map<String, UserSong?> =
        songService.byOriginalUrls(urls, user.id)

    override suspend fun byOriginalTracks(@LogParam("size") tracks: Collection<IMetadataService.Track>): List<UserSong> =
        songService.byOriginalTracks(tracks, user.id)

    override suspend fun likedSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean
    ): PaginatedResponse<UserSong> = songService.likedSongs(page, pageSize, explicit, user.id)

    override suspend fun allSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        tags: List<SongTag>,
        invertTags: Boolean
    ): PaginatedResponse<UserSong> = songService.allSongs(page, pageSize, explicit, user.id, tags, invertTags)

    override suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int,
        explicit: Boolean
    ): PaginatedResponse<UserSong> = songService.byColor(page, pageSize, color, range, explicit, user.id)

    override suspend fun deleteSongs(@LogParam("size") ids: List<UUID>): Boolean = songService.deleteSongs(ids)

    override suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        liked: Boolean
    ): PaginatedResponse<UserSong> = songService.rankedSearch(page, pageSize, query, explicit, user.id, liked)

    override fun streamSong(id: UUID, offset: Long, chunkSize: Int): Flow<ByteArray>? = songService.streamSong(id, offset, chunkSize)

    override fun downloadSong(id: UUID, quality: Int, offset: Long, chunkSize: Int, force: Boolean): Flow<ByteArray>? = songService.downloadSong(id, quality, offset, chunkSize, force)

    override suspend fun getStreamSize(id: UUID): Long = songService.getStreamSize(id)

    override suspend fun getDownloadSize(id: UUID, quality: Int, force: Boolean): Long = songService.getDownloadSize(id, quality, force)

    override fun allSongIds(explicit: Boolean, tags: List<SongTag>, invertTags: Boolean): Flow<UUID> =
        songService.allSongIds(explicit, tags, invertTags)

    override fun likedSongIds(explicit: Boolean): Flow<UUID> = songService.likedSongIds(explicit, user.id)

    override fun songIdsByArtist(artistId: UUID): Flow<UUID> = songService.songIdsByArtist(artistId)

    override fun songIdsByAlbum(albumId: UUID): Flow<UUID> = songService.songIdsByAlbum(albumId)

    override fun songIdsByPlaylist(playlistId: UUID): Flow<UUID> = songService.songIdsByPlaylist(playlistId)

    override fun songIdsByUserPlaylist(playlistId: UUID): Flow<UUID> = songService.songIdsByUserPlaylist(playlistId)

    override suspend fun moveSongs(oldPath: String, newPath: String, originalIdPrefix: String?): Int {
        if (!user.isAdmin) throw IllegalStateException("Only admins can move songs")
        return songService.moveSongs(oldPath, newPath, originalIdPrefix)
    }

    override suspend fun extendedMetadata(id: UUID): SongExtendedMetadata? =
        songService.extendedMetadata(id)
}

class SongService : SongLibrary, Service() {
    private val environment by inject<ApplicationEnvironment>()
    private val musicBrainzService by inject<MusicBrainzService>()
    private val cachedMusicBrainzService by inject<CachedMusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val artistService by inject<ArtistService>()
    private val genreService by inject<GenreService>()

    val albumArtistAlias = ArtistTable.alias("albumArtistAlias")
    val albumArtistMusicBrainzAlias = ArtistMusicBrainzTable.alias("albumArtistMusicBrainzAlias")
    val albumArtistAliasAlias = ArtistAliasTable.alias("albumArtistAliasAlias")

    val artistGroupAlias = ArtistTable.alias("artistGroup")
    val artistMemberAlias = ArtistTable.alias("artistMember")
    val albumArtistGroupAlias = ArtistTable.alias("albumArtistGroup")
    val albumArtistMemberAlias = ArtistTable.alias("albumArtistMember")

    val artistGroupJoinAlias = ArtistMemberTable.alias("artistGroupJoin")
    val artistMemberJoinAlias = ArtistMemberTable.alias("artistMemberJoin")
    val albumArtistGroupJoinAlias = ArtistMemberTable.alias("albumArtistGroupJoin")
    val albumArtistMemberJoinAlias = ArtistMemberTable.alias("albumArtistMemberJoin")

    val followedArtistAlias = FollowedArtistTable.alias("followedArtist")
    val albumFollowedArtistAlias = FollowedArtistTable.alias("albumFollowedArtist")

    val songImageAlias = ImageTable.alias("songImage")
    val albumImageAlias = ImageTable.alias("albumImage")
    val artistImageAlias = ImageTable.alias("artistImage")
    val albumArtistImageAlias = ImageTable.alias("albumArtistImage")

    companion object {
        fun mapSong(
            resultRow: ResultRow,
            genres: List<Genre> = listOf(),
            blurHashColumn: Expression<String?>? = null
        ): Song {
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
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash),
                musicBrainzId = resultRow.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value,
                genres = genres,
            )
        }

        fun mapUserSong(
            resultRow: ResultRow,
            genres: List<Genre> = listOf(),
            blurHashColumn: Expression<String?>? = null
        ): UserSong {
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
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash),
                musicBrainzId = resultRow.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value,
                genres = genres,
                isFavourite = resultRow.getOrNull(UserSongTable.isFavourite) ?: false,
                userSongCreatedAt = resultRow.getOrNull(UserSongTable.createdAt).date,
                userSongUpdatedAt = resultRow.getOrNull(UserSongTable.updatedAt).date,
            )
        }
    }

    inline fun <reified T : BaseSong> map(
        resultRow: ResultRow,
        genres: List<Genre> = listOf(),
        blurHashColumn: Expression<String?>? = null
    ): BaseSong =
        if (T::class == UserSong::class) mapUserSong(resultRow, genres, blurHashColumn)
        else mapSong(resultRow, genres, blurHashColumn)

    private fun ColumnSet.userSong(userId: UUID?) = if (userId != null) {
        leftJoin(
            UserSongTable,
            onColumn = { SongTable.id },
            otherColumn = { UserSongTable.songId },
            additionalConstraint = { UserSongTable.userId eq userId }
        )
    } else this

    private fun ColumnSet.followedArtist(userId: UUID?) = if (userId != null) {
        leftJoin(
            followedArtistAlias,
            onColumn = { ArtistTable.id },
            otherColumn = { followedArtistAlias[FollowedArtistTable.artistId] },
            additionalConstraint = { followedArtistAlias[FollowedArtistTable.userId] eq userId }
        )
    } else this

    private fun ColumnSet.albumFollowedArtist(userId: UUID?) = if (userId != null) {
        leftJoin(
            albumFollowedArtistAlias,
            onColumn = { albumArtistAlias[ArtistTable.id] },
            otherColumn = { albumFollowedArtistAlias[FollowedArtistTable.artistId] },
            additionalConstraint = { albumFollowedArtistAlias[FollowedArtistTable.userId] eq userId }
        )
    } else this

    override suspend fun setLiked(songId: UUID, userId: UUID, liked: Boolean, addedAt: Instant?) {
        setLikedReturning(songId, userId, liked, addedAt)
    }

    override suspend fun setLikedReturning(songId: UUID, userId: UUID, liked: Boolean, addedAt: Instant?): UserSong? {
        dbQuery {
            val inserted = UserSongTable.insertIgnore {
                it[UserSongTable.songId] = songId
                it[UserSongTable.userId] = userId
                it[UserSongTable.isFavourite] = liked
                if (addedAt != null) it[UserSongTable.updatedAt] = addedAt.toEpochMilli()
            }.insertedCount == 1

            if (!inserted) {
                UserSongTable.update({
                    UserSongTable.userId eq userId and (UserSongTable.songId eq songId)
                }) {
                    it[UserSongTable.isFavourite] = liked
                    it[UserSongTable.updatedAt] = (addedAt ?: Instant.now()).toEpochMilli()
                }
            }
        }

        return byId(songId, userId)
    }

    suspend fun setArtists(id: UUID, artistIds: List<UUID>, userId: UUID): UserSong? = dbQuery {
        SongArtistTable.deleteWhere { SongArtistTable.songId eq id }
        SongArtistTable.batchInsert(artistIds) { artistId ->
            this[SongArtistTable.songId] = id
            this[SongArtistTable.artistId] = artistId
        }

        return@dbQuery byId(id, userId).also {
            it?.let { song ->
                if (!song.path.endsWith(".flac", true)) return@let
                try {
                    val file = AudioFileIO.read(File(song.path))

                    file.tag.apply {
                        deleteField(FieldKey.ARTIST)
                        for (name in song.artists.map { artist -> artist.name }.sorted()) {
                            addField(FieldKey.ARTIST, name)
                        }
                    }

                    file.commit()
                } catch (e: Exception) {
                    logger.error("Failed to set artists for $id: ${e.message}", e)
                }
            }
        }
    }

    suspend fun setLyrics(id: UUID, userId: UUID, lyrics: List<String>) = dbQuery {
        val lyricsString = lyrics.joinToString("\n")
        SongTable.update({ SongTable.id eq id }) {
            it[SongTable.lyrics] = lyricsString
        }

        return@dbQuery byId(id, userId).also {
            it?.let { song ->
                if (!song.path.endsWith(".flac", true)) return@let
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

    suspend fun setMusicBrainzId(id: UUID, musicBrainzId: UUID?, userId: UUID): UserSong? {
        if (musicBrainzId != null) {
            val recordingExists = dbQuery {
                MBRecordingTable.select(MBRecordingTable.id)
                    .where { MBRecordingTable.id eq musicBrainzId }
                    .any()
            }

            if (!recordingExists) {
                musicBrainzService.fetchRecordingById(musicBrainzId, HttpClientPriority.HIGH)?.let {
                    musicBrainzCacheService.updateRecordingCache(it)
                }
            }
        }

        dbQuery {
            val exists = SongMusicBrainzTable.select(SongMusicBrainzTable.songId)
                .where { SongMusicBrainzTable.songId eq id }
                .any()

            if (exists) {
                SongMusicBrainzTable.update({ SongMusicBrainzTable.songId eq id }) {
                    it[SongMusicBrainzTable.musicBrainzId] = musicBrainzId
                    it[SongMusicBrainzTable.lastCheck] = System.currentTimeMillis()
                }
            } else {
                SongMusicBrainzTable.insert {
                    it[SongMusicBrainzTable.songId] = id
                    it[SongMusicBrainzTable.musicBrainzId] = musicBrainzId
                    it[SongMusicBrainzTable.lastCheck] = System.currentTimeMillis()
                }
            }
        }

        return byId(id, userId).also { song ->
            if (song == null || !song.path.endsWith(".flac", true)) return@also
            try {
                val file = AudioFileIO.read(File(song.path))

                if (musicBrainzId != null) {
                    file.tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, musicBrainzId.toString())
                } else {
                    file.tag.deleteField(FieldKey.MUSICBRAINZ_TRACK_ID)
                }

                file.commit()
            } catch (e: Exception) {
                logger.error("Failed to set musicBrainzId for $id: ${e.message}", e)
            }
        }
    }

    suspend fun fetchMusicBrainzId(id: UUID, userId: UUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): UserSong? {
        val song = byId(id, userId) ?: return null

        val mbRecording = if (song.musicBrainzId != null) {
            cachedMusicBrainzService.getRecording(song.musicBrainzId!!, priority)
        } else {
            musicBrainzService.searchMb(song, priority)
        }

        if (mbRecording != null) {
            if (song.musicBrainzId == null) {
                musicBrainzCacheService.updateRecordingCache(mbRecording)
            }

            val artistCredits = mbRecording.artistCredit ?: emptyList()

            val mbArtistIds = artistCredits.mapNotNull { it.artist?.id }.distinct()
            val existingArtistsByMbId = if (mbArtistIds.isNotEmpty()) {
                artistService.byMusicBrainzIds(mbArtistIds, userId).associateBy { it.musicbrainzId }
            } else emptyMap()

            val resolvedArtists = mutableListOf<Artist>()
            val namesToResolve = artistCredits
                .filter { it.artist?.id == null || !existingArtistsByMbId.containsKey(it.artist?.id) }
                .mapNotNull { it.name ?: it.artist?.name }
                .distinct()

            val artistsByName = if (namesToResolve.isNotEmpty()) {
                artistService.getOrBulkCreateWithResult(namesToResolve)
            } else null

            val allCandidateIds = artistsByName?.nameToIds?.values?.flatten()?.distinct() ?: emptyList()
            val candidatesById = if (allCandidateIds.isNotEmpty()) {
                artistService.byIds(allCandidateIds, userId).associateBy { it.id }
            } else emptyMap()

            val candidatesWithEvidence = if (allCandidateIds.isNotEmpty() && mbArtistIds.isNotEmpty()) {
                dbQuery {
                    val fromSongs = SongArtistTable
                        .innerJoin(SongMusicBrainzTable, onColumn = { SongArtistTable.songId }, otherColumn = { SongMusicBrainzTable.songId })
                        .innerJoin(MBRecordingArtistCreditTable, onColumn = { SongMusicBrainzTable.musicBrainzId }, otherColumn = { MBRecordingArtistCreditTable.recordingId })
                        .innerJoin(SongTable, onColumn = { SongArtistTable.songId }, otherColumn = { SongTable.id })
                        .select(SongArtistTable.artistId, MBRecordingArtistCreditTable.artistId)
                        .where { (SongArtistTable.artistId inList allCandidateIds) and (MBRecordingArtistCreditTable.artistId inList mbArtistIds) and (SongTable.id neq id) }
                        .map { it[SongArtistTable.artistId].value to it[MBRecordingArtistCreditTable.artistId].value }

                    val fromAlbums = AlbumArtistTable
                        .innerJoin(AlbumMusicBrainzTable, onColumn = { AlbumArtistTable.albumId }, otherColumn = { AlbumMusicBrainzTable.albumId })
                        .innerJoin(MBReleaseArtistCreditTable, onColumn = { AlbumMusicBrainzTable.musicBrainzId }, otherColumn = { MBReleaseArtistCreditTable.releaseId })
                        .select(AlbumArtistTable.artistId, MBReleaseArtistCreditTable.artistId)
                        .where { (AlbumArtistTable.artistId inList allCandidateIds) and (MBReleaseArtistCreditTable.artistId inList mbArtistIds) }
                        .map { it[AlbumArtistTable.artistId].value to it[MBReleaseArtistCreditTable.artistId].value }

                    (fromSongs + fromAlbums).toSet()
                }
            } else emptySet()

            artistCredits.forEach { credit ->
                val mbId = credit.artist?.id
                val name = credit.name ?: credit.artist?.name ?: return@forEach

                var artist = existingArtistsByMbId[mbId]
                if (artist == null && artistsByName != null) {
                    val ids = artistsByName.nameToIds[name] ?: emptyList()
                    val candidates = ids.mapNotNull { candidatesById[it] }

                    artist = candidates.find { candidate ->
                        mbId != null && candidatesWithEvidence.contains(candidate.id to mbId)
                    }

                    if (artist != null) {
                        if (mbId != null) {
                            artistService.setMusicBrainzId(artist.id, mbId, userId)
                            artist = artist.copy(musicbrainzId = mbId)
                        }
                    } else if (mbId != null) {
                        artist = artistService.createArtist(name = name, musicBrainzId = mbId, userId = userId)
                    } else {
                        artist = candidates.firstOrNull()
                    }
                }

                if (artist != null) {
                    resolvedArtists.add(artist)
                }
            }

            val finalArtists = resolvedArtists.distinctBy { it.id }

            if (finalArtists.isNotEmpty()) {
                dbQuery {
                    SongArtistTable.deleteWhere { SongArtistTable.songId eq id }
                    SongArtistTable.batchInsert(finalArtists) { artist ->
                        this[SongArtistTable.songId] = id
                        this[SongArtistTable.artistId] = artist.id
                    }
                }
            }

            val genres = (mbRecording.genres?.map { it.name } ?: emptyList()) + (mbRecording.releases?.flatMap { it.genres?.map { g -> g.name } ?: emptyList() } ?: emptyList()) + (mbRecording.releases?.flatMap { it.releaseGroup?.genres?.map { g -> g.name } ?: emptyList() } ?: emptyList())
            if (genres.isNotEmpty()) {
                val genreIds = genreService.getOrCreateGenres(genres)
                dbQuery {
                    SongGenreTable.deleteWhere { SongGenreTable.songId eq id }
                    SongGenreTable.batchInsert(genreIds) { genreId ->
                        this[SongGenreTable.songId] = id
                        this[SongGenreTable.genreId] = genreId
                    }
                }
            }
        }

        return setMusicBrainzId(id, mbRecording?.id, userId)
    }

    suspend fun findSongIdByMetadata(
        title: String,
        albumId: UUID,
        trackNumber: Int,
        discNumber: Int,
        explicit: Boolean
    ): UUID? = dbQuery {
        SongTable.select(SongTable.id)
            .where {
                (SongTable.title eq title) and
                        (SongTable.albumId eq albumId) and
                        (SongTable.trackNumber eq trackNumber) and
                        (SongTable.discNumber eq discNumber) and
                        (SongTable.explicit eq explicit)
            }
            .singleOrNull()?.get(SongTable.id)?.value
    }

    suspend fun byId(id: UUID): Song? = querySingle {
        where { SongTable.id eq id }
    }

    suspend fun extendedMetadata(id: UUID): SongExtendedMetadata? = dbQuery {
        val songRow = SongTable.selectAll().where { SongTable.id eq id }.singleOrNull()
            ?: return@dbQuery null

        val providers = SongProviderTable.selectAll()
            .where { SongProviderTable.songId eq id }
            .map {
                ProviderEntry(
                    provider = it[SongProviderTable.provider],
                    externalId = it[SongProviderTable.externalId],
                    type = it[SongProviderTable.type],
                    rawUrl = it[SongProviderTable.rawUrl],
                    addedAt = it[SongProviderTable.addedAt]
                )
            }

        val audioData = SongAudioDataTable.selectAll()
            .where { SongAudioDataTable.songId eq id }
            .singleOrNull()
            ?.let {
                SongAudioData(
                    bpm = it[SongAudioDataTable.bpm],
                    key = it[SongAudioDataTable.key],
                    scale = AudioScale.fromString(it[SongAudioDataTable.scale]),
                    loudness = it[SongAudioDataTable.loudness],
                    energy = it[SongAudioDataTable.energy],
                    valence = it[SongAudioDataTable.valence],
                    danceability = it[SongAudioDataTable.danceability],
                    acousticness = it[SongAudioDataTable.acousticness],
                    instrumentalness = it[SongAudioDataTable.instrumentalness],
                    speechiness = it[SongAudioDataTable.speechiness]
                )
            }

        SongExtendedMetadata(
            providers = providers,
            audioData = audioData,
            insertedAt = songRow[SongTable.inserted]
        )
    }

    suspend fun byIds(ids: List<UUID>, userId: UUID): List<UserSong> =
        querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
            where { SongTable.id inList ids }
        }.let { response ->
            val songMap = response.data.associateBy { it.id }
            ids.mapNotNull { songMap[it] }
        }

    suspend fun byIds(ids: List<UUID>): List<Song> =
        querySongs<Song>(0, Int.MAX_VALUE, true) {
            where { SongTable.id inList ids }
        }.let { response ->
            val songMap = response.data.associateBy { it.id }
            ids.mapNotNull { songMap[it] }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun allSongsFlow(explicit: Boolean = true): Flow<Song> =
        allSongIds(explicit).chunked(100).flatMapConcat { ids ->
            byIds(ids).asFlow()
        }

    suspend fun byId(id: UUID, userId: UUID): UserSong? = querySingle(userId) {
        where { SongTable.id eq id }
    }

    suspend fun byMusicBrainzId(musicBrainzId: UUID, userId: UUID): List<UserSong> =
        querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
            where { SongMusicBrainzTable.musicBrainzId eq musicBrainzId }
        }.data

    suspend fun byTitle(page: Int, pageSize: Int, title: String, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId) {
            where { SongTable.title eq title }
        }

    suspend fun byArtist(page: Int, pageSize: Int, artistId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId) {
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

    suspend fun likedByArtist(page: Int, pageSize: Int, artistId: UUID, explicit: Boolean, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, explicit, userId) {
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
            andWhere { UserSongTable.isFavourite eq true }
            orderBy(SongTable.releaseDate, SortOrder.DESC)
            orderBy(SongTable.trackNumber, SortOrder.ASC)
        }

    suspend fun byAlbum(page: Int, pageSize: Int, albumId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId) {
            where { SongTable.albumId eq albumId }
            orderBy(SongTable.discNumber, SortOrder.ASC)
            orderBy(SongTable.trackNumber, SortOrder.ASC)
        }

    suspend fun byPlaylist(page: Int, pageSize: Int, playlistId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId, {
            leftJoin(PlaylistSongTable)
        }) {
            where { PlaylistSongTable.playlistId eq playlistId }
            orderBy(PlaylistSongTable.position, SortOrder.ASC)
            orderBy(SongTable.id, SortOrder.ASC)
        }

    suspend fun byUserPlaylist(page: Int, pageSize: Int, playlistId: UUID, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId, {
            leftJoin(UserPlaylistSongTable)
        }) {
            where { UserPlaylistSongTable.playlistId eq playlistId }
            orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
            orderBy(SongTable.id, SortOrder.ASC)
        }

    override suspend fun byOriginalIds(ids: Collection<PrefixedId>, userId: UUID): List<UserSong> =
        ids.chunked(5000).flatMap { idChunk ->
            val parsedLookups = idChunk.mapNotNull { id ->
                val parser = ParserFactory.getParser(id)
                val parsed = parser?.parse(id)
                if (parser != null && parsed != null) {
                    parser.name to parsed.first
                } else null
            }

            querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
                val songIdsFromProviders = SongProviderTable
                    .select(SongProviderTable.songId)
                    .where {
                        (SongProviderTable.rawUrl inList ids) or
                                (SongProviderTable.externalId inList ids) or
                                (if (parsedLookups.isNotEmpty()) {
                                    parsedLookups.map { (p, eid) ->
                                        (SongProviderTable.provider eq p) and (SongProviderTable.externalId eq eid)
                                    }.reduce { acc, op -> acc or op }
                                } else Op.FALSE)
                    }
                    .map { it[SongProviderTable.songId].value }

                where {
                    SongTable.originalUrl inList ids
                }
                orWhere {
                    SongTable.id inList songIdsFromProviders
                }
            }.data
        }

    override suspend fun byOriginalUrls(urls: Collection<String>, userId: UUID): Map<String, UserSong?> {
        val results = mutableMapOf<String, UserSong?>()
        if (urls.isEmpty()) return results

        val parsedLookups = mutableListOf<Triple<String, String, String>>()
        for (url in urls) {
            val parser = ParserFactory.getParser(url)
            val parsed = parser?.parse(url)
            if (parser != null && parsed != null) {
                parsedLookups.add(Triple(url, parser.name, parsed.first))
            }
        }

        val allSongs = querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
            val songIdsFromProviders = SongProviderTable
                .select(SongProviderTable.songId)
                .where {
                    (SongProviderTable.rawUrl inList urls) or
                            (if (parsedLookups.isNotEmpty()) {
                                parsedLookups.map { (_, p, eid) ->
                                    (SongProviderTable.provider eq p) and (SongProviderTable.externalId eq eid)
                                }.reduce { acc, op -> acc or op }
                            } else Op.FALSE)
                }
                .map { it[SongProviderTable.songId].value }

            where {
                (SongTable.originalUrl inList urls) or
                        (SongTable.id inList songIdsFromProviders)
            }
        }.data

        val mappings = dbQuery {
            SongProviderTable
                .select(SongProviderTable.songId, SongProviderTable.rawUrl, SongProviderTable.provider, SongProviderTable.externalId)
                .where {
                    (SongProviderTable.rawUrl inList urls) or
                            (if (parsedLookups.isNotEmpty()) {
                                parsedLookups.map { (_, p, eid) ->
                                    (SongProviderTable.provider eq p) and (SongProviderTable.externalId eq eid)
                                }.reduce { acc, op -> acc or op }
                            } else Op.FALSE)
                }
                .map { row ->
                    row[SongProviderTable.songId].value to Triple(
                        row[SongProviderTable.rawUrl],
                        row[SongProviderTable.provider],
                        row[SongProviderTable.externalId]
                    )
                }
        }

        for (url in urls) {
            val lookup = parsedLookups.find { it.first == url }

            val song = allSongs.find { song ->
                song.originalUrl == url ||
                        mappings.any { (songId, triple) ->
                            songId == song.id && (triple.first == url || (lookup != null && triple.second == lookup.second && triple.third == lookup.third))
                        }
            }
            results[url] = song
        }

        return results
    }

    suspend fun addProviderUrl(songId: UUID, url: String) = dbQuery {
        val parser = ParserFactory.getParser(url)
        val parsed = parser?.parse(url)

        val provider = parser?.name ?: "unknown"
        val externalId = parsed?.first ?: url

        SongProviderTable.upsert(SongProviderTable.songId, SongProviderTable.provider, SongProviderTable.externalId) {
            it[SongProviderTable.songId] = songId
            it[SongProviderTable.provider] = provider
            it[SongProviderTable.externalId] = externalId
            it[SongProviderTable.type] = parsed?.second?.value
            it[SongProviderTable.rawUrl] = url
        }
    }

    suspend fun byOriginalTracks(
        tracks: Collection<IMetadataService.Track>,
        userId: UUID
    ): List<UserSong> =
        querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
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
        querySongs(page, pageSize, explicit, userId, columnSet = {
            withMBRecordingSearch()
                .withMBReleaseSearch()
                .withMBArtistSearch()
        }) {
            rankedSearchQuery(
                query,
                listOf(20, 10, 5, 5, 5, 5, 3, 3, 3, 3, 5, 5, 3, 5, 5, 3),
                listOf(
                    SongMusicBrainzTable.musicBrainzId.castTo<String?>(VarCharColumnType(36)),
                    SongTable.title,
                    ArtistTable.name,
                    AlbumTable.name,
                    ArtistAliasTable.name,
                    albumArtistAliasAlias[ArtistAliasTable.name],
                    artistGroupAlias[ArtistTable.name],
                    artistMemberAlias[ArtistTable.name],
                    albumArtistGroupAlias[ArtistTable.name],
                    albumArtistMemberAlias[ArtistTable.name],
                    mbRecordingSearchTable[MBRecordingTable.title],
                    mbReleaseSearchTable[MBReleaseTable.title],
                    mbReleaseSearchTable[MBReleaseTable.disambiguation],
                    mbArtistSearchTable[MBArtistTable.name],
                    mbArtistAliasSearchTable[MBArtistAliasTable.name],
                    mbArtistSearchTable[MBArtistTable.disambiguation]
                ),
                SongTable.id
            ).let { it ->
                if (liked) it.andWhere { UserSongTable.isFavourite eq true }
                else it
            }
        }

    suspend fun likedSongs(page: Int, pageSize: Int, explicit: Boolean, userId: UUID): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, userId
        ) {
            where { UserSongTable.isFavourite eq true }
            orderBy(UserSongTable.updatedAt to SortOrder.DESC)
        }

    suspend fun allSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID,
        tags: List<SongTag> = emptyList(),
        invertTags: Boolean = false
    ): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, userId,
            query = {
                applyTags(tags, invertTags)
                orderBy(SongTable.inserted, SortOrder.DESC)
                orderBy(SongTable.id, SortOrder.ASC)
            }
        )

    suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> {
        val (l, a, b) = ColorUtils.rgbToLab((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
        return querySongs(
            page, pageSize, explicit, userId,
            columnSet = {
                leftJoin(ImageMetadataTable, onColumn = { SongTable.cover }, otherColumn = { ImageMetadataTable.imageId })
            },
            query = {
                filterByColor(l, a, b, range)
                orderByColorDistance(l, a, b)
            }
        )
    }

    @Suppress("DuplicatedCode")
    suspend fun deleteSongs(ids: List<UUID>): Boolean = dbQuery {
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
            if (file.parentFile.exists() && file.parentFile.list().isNullOrEmpty())
                logger.info("Trying to delete parent ${file.parentFile.absolutePath} (${file.parentFile.delete()})")
        }

        deletedSongs == ids.size
    }

    fun streamSong(id: UUID, offset: Long, chunkSize: Int = 4096): Flow<ByteArray>? {
        val song = runBlocking { byId(id) } ?: return null
        val file = File(song.path)
        if (!file.exists()) return null

        return flow {
            val buffer = ByteArray(chunkSize)
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

    fun downloadSong(id: UUID, quality: Int, offset: Long = 0, chunkSize: Int = 4096, force: Boolean = true): Flow<ByteArray>? {
        val song = runBlocking { byId(id) } ?: return null
        val file = File(song.path)
        if (!file.exists()) return null

        return flow {
            val streamInfo = AudioUtils.transcodeFlacToOpus(environment, file, quality, force).also {
                AudioUtils.insertTranscodedSong(id, it.file, quality)
            }

            try {
                val buffer = ByteArray(chunkSize)
                streamInfo.file.inputStream().use { input ->
                    input.skip(offset)
                    var bytesRead = input.read(buffer)
                    while (bytesRead != -1) {
                        emit(buffer.copyOf(bytesRead))
                        bytesRead = input.read(buffer)
                    }
                }
            } catch (e: IOException) {
                if (streamInfo.file.exists()) {
                    streamInfo.file.delete()
                }
                throw e
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getStreamSize(id: UUID): Long {
        val song = byId(id) ?: return 0
        val file = File(song.path)
        if (!file.exists()) return 0
        return file.length()
    }

    suspend fun getDownloadSize(id: UUID, quality: Int, force: Boolean = true): Long {
        val song = byId(id) ?: return 0
        val file = File(song.path)
        if (!file.exists()) return 0
        val streamInfo = AudioUtils.transcodeFlacToOpus(environment, file, quality, force).also {
            AudioUtils.insertTranscodedSong(id, it.file, quality)
        }
        return streamInfo.file.length()
    }

    fun allSongIds(
        explicit: Boolean,
        tags: List<SongTag> = emptyList(),
        invertTags: Boolean = false
    ): Flow<UUID> = flow {
        SongTable
            .leftJoin(SongMusicBrainzTable)
            .select(SongTable.id)
            .let {
                if (!explicit) it.where { SongTable.explicit eq false }
                else it
            }
            .applyTags(tags, invertTags)
            .orderBy(SongTable.inserted, SortOrder.DESC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    private fun Query.applyTags(tags: List<SongTag>, invert: Boolean): Query {
        if (tags.isNotEmpty()) {
            val customAudioPath = get<StorageService>().customAudioPath

            val conditions = tags.map { tag ->
                when (tag) {
                    SongTag.Q_44_48 -> (SongTable.sampleRate eq 44100) or (SongTable.sampleRate eq 48000)
                    SongTag.Q_96 -> (SongTable.sampleRate eq 96000)
                    SongTag.Q_192 -> (SongTable.sampleRate eq 192000)
                    SongTag.B_16 -> (SongTable.bitsPerSample eq 16)
                    SongTag.B_24 -> (SongTable.bitsPerSample eq 24)
                    SongTag.HAS_LYRICS -> (SongTable.lyrics neq "")
                    SongTag.CUSTOM_UPLOAD -> (SongTable.filePath like "$customAudioPath%")
                    SongTag.HAS_MUSICBRAINZ_ID -> (SongMusicBrainzTable.musicBrainzId.isNotNull())
                }
            }

            val combinedCondition = conditions.reduce { acc, op -> acc or op }
            if (invert) andWhere { not(combinedCondition) }
            else andWhere { combinedCondition }
        }
        return this
    }

    fun likedSongIds(explicit: Boolean, userId: UUID): Flow<UUID> = flow {
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
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
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

        val query = SongTable.select(SongTable.id)
        val op1 = if (songIds.isNotEmpty()) (SongTable.id inList songIds) else null
        val op2 = if (albumIds.isNotEmpty()) (SongTable.albumId inList albumIds) else null

        val op = if (op1 != null && op2 != null) op1 or op2
        else op1 ?: op2

        if (op != null) {
            query.where(op)
                .orderBy(SongTable.releaseDate, SortOrder.DESC)
                .orderBy(SongTable.trackNumber, SortOrder.ASC)
                .fetchBatchedResults(1000) { batch ->
                    batch.forEach {
                        emit(it[SongTable.id].value)
                    }
                }
        }
    }

    fun songIdsByAlbum(albumId: UUID): Flow<UUID> = flow {
        SongTable
            .select(SongTable.id)
            .where { SongTable.albumId eq albumId }
            .orderBy(SongTable.discNumber, SortOrder.ASC)
            .orderBy(SongTable.trackNumber, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    fun songIdsByPlaylist(playlistId: UUID): Flow<UUID> = flow {
        PlaylistSongTable
            .select(PlaylistSongTable.songId)
            .where { PlaylistSongTable.playlistId eq playlistId }
            .orderBy(PlaylistSongTable.position, SortOrder.ASC)
            .orderBy(PlaylistSongTable.songId, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[PlaylistSongTable.songId].value)
                }
            }
    }

    fun songIdsByUserPlaylist(playlistId: UUID): Flow<UUID> = flow {
        UserPlaylistSongTable
            .select(UserPlaylistSongTable.songId)
            .where { UserPlaylistSongTable.playlistId eq playlistId }
            .orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
            .orderBy(UserPlaylistSongTable.songId, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[UserPlaylistSongTable.songId].value)
                }
            }
    }

    fun songIdsWithoutMusicBrainzId(): Flow<UUID> = flow {
        val oneWeekAgo = Clock.System.now() - 7.days

        SongTable
            .leftJoin(SongMusicBrainzTable)
            .select(SongTable.id)
            .where {
                SongMusicBrainzTable.songId.isNull() or
                        (SongMusicBrainzTable.lastCheck eq 0L) or
                        (SongMusicBrainzTable.musicBrainzId.isNull() and (SongMusicBrainzTable.lastCheck less oneWeekAgo.toEpochMilliseconds()))
            }
            .orderBy(SongTable.inserted, SortOrder.DESC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    private suspend inline fun <reified T : BaseSong> querySingle(
        userId: UUID? = null,
        crossinline columnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline query: Query.() -> Query = { this }
    ) =
        querySongs<T>(0, Int.MAX_VALUE, true, userId, columnSet, query).data.singleOrNull()

    private suspend inline fun <reified T : BaseSong> querySongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID? = null,
        crossinline columnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline query: Query.() -> Query = { this }
    ) = dbQuery {
        val base = SongTable
            .leftJoin(
                AlbumTable,
                onColumn = { SongTable.albumId },
                otherColumn = { AlbumTable.id }
            )
            .leftJoin(
                AlbumMusicBrainzTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumMusicBrainzTable.albumId }
            )
            .leftJoin(SongArtistTable)
            .leftJoin(
                ArtistTable,
                onColumn = { SongArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .leftJoin(
                ArtistMusicBrainzTable,
                onColumn = { ArtistTable.id },
                otherColumn = { ArtistMusicBrainzTable.artistId }
            )
            .leftJoin(artistGroupJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistGroupJoinAlias[ArtistMemberTable.artistId] })
            .leftJoin(artistGroupAlias, onColumn = { artistGroupJoinAlias[ArtistMemberTable.groupId] }, otherColumn = { artistGroupAlias[ArtistTable.id] })
            .leftJoin(artistMemberJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistMemberJoinAlias[ArtistMemberTable.groupId] })
            .leftJoin(artistMemberAlias, onColumn = { artistMemberJoinAlias[ArtistMemberTable.artistId] }, otherColumn = { artistMemberAlias[ArtistTable.id] })
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
                albumArtistMusicBrainzAlias,
                onColumn = { albumArtistAlias[ArtistTable.id] },
                otherColumn = { albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.artistId] }
            )
            .leftJoin(
                albumArtistAliasAlias,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { albumArtistAliasAlias[ArtistAliasTable.artistId] }
            )
            .leftJoin(albumArtistGroupJoinAlias, onColumn = { albumArtistAlias[ArtistTable.id] }, otherColumn = { albumArtistGroupJoinAlias[ArtistMemberTable.artistId] })
            .leftJoin(albumArtistGroupAlias, onColumn = { albumArtistGroupJoinAlias[ArtistMemberTable.groupId] }, otherColumn = { albumArtistGroupAlias[ArtistTable.id] })
            .leftJoin(albumArtistMemberJoinAlias, onColumn = { albumArtistAlias[ArtistTable.id] }, otherColumn = { albumArtistMemberJoinAlias[ArtistMemberTable.groupId] })
            .leftJoin(albumArtistMemberAlias, onColumn = { albumArtistMemberJoinAlias[ArtistMemberTable.artistId] }, otherColumn = { albumArtistMemberAlias[ArtistTable.id] })
            .leftJoin(SongGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(SongProviderTable)
            .leftJoin(songImageAlias, onColumn = { SongTable.cover }, otherColumn = { songImageAlias[ImageTable.id] })
            .leftJoin(albumImageAlias, onColumn = { AlbumTable.cover }, otherColumn = { albumImageAlias[ImageTable.id] })
            .leftJoin(artistImageAlias, onColumn = { ArtistTable.image }, otherColumn = { artistImageAlias[ImageTable.id] })
            .leftJoin(albumArtistImageAlias, onColumn = { albumArtistAlias[ArtistTable.image] }, otherColumn = { albumArtistImageAlias[ImageTable.id] })
            .leftJoin(SongMusicBrainzTable)
            .userSong(userId)
            .followedArtist(userId)
            .albumFollowedArtist(userId)
            .columnSet()

        val q = base.selectAll().query()

        val countExpression = SongTable.id.countDistinct()
        val countQuery = Query(Slice(q.set.source, listOf(countExpression)), q.where)
        q.having?.let { h -> countQuery.having { h } }

        val total = countQuery.first()[countExpression]

        if (total == 0L) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = 0,
            page = page,
            pageSize = pageSize,
        )

        val sortAliases = q.orderByExpressions.mapIndexed { index, (expr, _) ->
            expr.alias("sort_$index")
        }
        val idQuery = Query(Slice(q.set.source, listOf(SongTable.id) + sortAliases), q.where)
        q.having?.let { h -> idQuery.having { h } }
        q.orderByExpressions.forEachIndexed { index, (_, order) ->
            idQuery.orderBy(sortAliases[index], order)
        }
        idQuery.withDistinct(true)

        if (pageSize != Int.MAX_VALUE) {
            idQuery.limit(pageSize)
            idQuery.offset((page * pageSize).toLong())
        }

        val ids = idQuery.map { it[SongTable.id].value }.distinct()

        if (ids.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
        )

        val rows = base.selectAll()
            .where { SongTable.id inList ids }
            .toList()

        val albumIds = rows.mapNotNull { it.getOrNull(AlbumTable.id)?.value }.distinct()

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val unsortedData = mapEagerly<T>(
            rows = rows,
            albumArtistAlias = albumArtistAlias,
            albumStats = statsByAlbumId,
            explicit = explicit,
            songBlurHashColumn = songImageAlias[ImageTable.blurHash],
            albumBlurHashColumn = albumImageAlias[ImageTable.blurHash],
            artistBlurHashColumn = artistImageAlias[ImageTable.blurHash],
            albumArtistBlurHashColumn = albumArtistImageAlias[ImageTable.blurHash]
        )

        val data = ids.mapNotNull { id -> unsortedData.find { it.id == id } }

        PaginatedResponse(
            data = data,
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
            hasNextPage = (page + 1).toLong() * pageSize < total,
        )
    }

    private inline fun <reified T : BaseSong> mapEagerly(
        rows: List<ResultRow>,
        albumArtistAlias: Alias<ArtistTable>,
        albumStats: Map<UUID, Pair<Long, Long>>,
        explicit: Boolean = false,
        songBlurHashColumn: Expression<String?>? = null,
        albumBlurHashColumn: Expression<String?>? = null,
        artistBlurHashColumn: Expression<String?>? = null,
        albumArtistBlurHashColumn: Expression<String?>? = null
    ): List<T> {
        val songMap = mutableMapOf<UUID, BaseSong>()
        val songArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()
        val albumArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()
        val songGenresMap = mutableMapOf<UUID, MutableList<Genre>>()
        val songUrlsMap = mutableMapOf<UUID, MutableSet<String>>()

        for (row in rows) {
            val songId = row[SongTable.id].value
            val albumId = row[SongTable.albumId].value

            songMap.getOrPut(songId) {
                val album = mapAlbum(row, blurHashColumn = albumBlurHashColumn)
                val genres = rows.filter { it[SongTable.id].value == songId }
                    .mapNotNull { r ->
                        val gid = r.getOrNull(GenreTable.id)?.value ?: return@mapNotNull null
                        val gname = r.getOrNull(GenreTable.name) ?: return@mapNotNull null
                        Genre(gid, gname)
                    }.distinctBy { it.id }
                val song = map<T>(row, genres, songBlurHashColumn)

                @Suppress("UNCHECKED_CAST")
                when (song) {
                    is UserSong -> song.copy(album = album)
                    is Song -> song.copy(album = album)
                    else -> throw Exception("Unknown song type: $row")
                }
            }

            row.getOrNull(SongProviderTable.rawUrl)?.let { url ->
                songUrlsMap.getOrPut(songId) { mutableSetOf() }.add(url)
            }

            if (row.getOrNull(ArtistTable.id) != null) {
                val artist = mapArtist(
                    row,
                    ArtistTable,
                    followedTable = followedArtistAlias,
                    blurHashColumn = artistBlurHashColumn
                )
                if (artist !in songArtistsMap.getOrDefault(songId, emptyList())) {
                    songArtistsMap.getOrPut(songId) { mutableListOf() }.add(artist)
                }
            }

            if (row.getOrNull(albumArtistAlias[ArtistTable.id]) != null) {
                val artist = mapArtist(
                    row,
                    albumArtistAlias,
                    row.getOrNull(albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.musicBrainzId])?.value,
                    followedTable = albumFollowedArtistAlias,
                    blurHashColumn = albumArtistBlurHashColumn
                )
                if (artist !in albumArtistsMap.getOrDefault(albumId, emptyList())) {
                    albumArtistsMap.getOrPut(albumId) { mutableListOf() }.add(artist)
                }
            }

            if (row.getOrNull(GenreTable.id) != null) {
                val genre = Genre(row[GenreTable.id].value, row[GenreTable.name])
                if (genre !in songGenresMap.getOrDefault(songId, emptyList())) {
                    songGenresMap.getOrPut(songId) { mutableListOf() }.add(genre)
                }
            }
        }

        return songMap.values.map { song ->
            val albumArtists = albumArtistsMap[song.album?.id] ?: listOf()
            val songArtists = songArtistsMap[song.id]?.distinctBy { it.id } ?: listOf()
            val songGenres = songGenresMap[song.id]?.distinctBy { it.id } ?: listOf()
            val urls = songUrlsMap[song.id] ?: emptySet()

            val albumWithArtists = song.album?.copy(
                artists = albumArtists,
                totalDuration = albumStats[song.album!!.id]?.first ?: -1L,
                totalSize = albumStats[song.album!!.id]?.second ?: -1L
            )

            val originalUrl = urls.firstOrNull() ?: song.originalUrl

            @Suppress("UNCHECKED_CAST")
            when (song) {
                is Song -> song.copy(
                    album = albumWithArtists,
                    artists = songArtists,
                    genres = songGenres,
                    originalUrl = originalUrl
                ) as T

                is UserSong -> song.copy(
                    album = albumWithArtists,
                    artists = songArtists,
                    genres = songGenres,
                    originalUrl = originalUrl
                ) as T

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
            if (explicit) songList.find { it.explicit } ?: songList.first()
            else songList.find { !it.explicit }
        }
    }

    private suspend fun bulkFindExistingSongs(songs: List<InsertableSong>): Map<InsertableSong, Pair<UUID, Pair<String, Boolean>>> =
        dbQuery {
            val parsedLookups = songs.mapNotNull { song ->
                if (song.originalUrl.isBlank()) return@mapNotNull null
                val parser = ParserFactory.getParser(song.originalUrl)
                val parsed = parser?.parse(song.originalUrl)
                if (parser != null && parsed != null) {
                    Triple(song.originalUrl, parser.name, parsed.first)
                } else null
            }

            val rows = SongTable
                .leftJoin(SongProviderTable)
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
                    SongTable.filePath,
                    SongTable.originalUrl,
                    AlbumTable.name,
                    SongProviderTable.rawUrl,
                    SongProviderTable.provider,
                    SongProviderTable.externalId
                )
                .withDistinct()
                .where { SongTable.filePath inList songs.map { it.path } }
                .orWhere {
                    val urls = songs.map { it.originalUrl }.filter { it.isNotBlank() }
                    (SongTable.originalUrl inList urls) or
                            (SongProviderTable.rawUrl inList urls) or
                            (if (parsedLookups.isNotEmpty()) {
                                parsedLookups.map { (_, p, eid) ->
                                    (SongProviderTable.provider eq p) and (SongProviderTable.externalId eq eid)
                                }.reduce { acc, op -> acc or op }
                            } else Op.FALSE)
                }
                .orWhere {
                    (SongTable.title inList songs.map { it.title }) and
                            (SongTable.trackNumber inList songs.map { it.trackNumber }) and
                            (SongTable.discNumber inList songs.map { it.discNumber })
                }
                .toList()

            val existingSongMap = mutableMapOf<InsertableSong, Pair<UUID, Pair<String, Boolean>>>()

            for (song in songs) {
                rows.firstOrNull { row ->
                    val albumName = row[AlbumTable.name]
                    val songId = row[SongTable.id].value
                    val dbFilePath = row[SongTable.filePath]

                    val pathMatch = dbFilePath == song.path

                    val lookup = parsedLookups.find { it.first == song.originalUrl }
                    val rowRawUrl = row.getOrNull(SongProviderTable.rawUrl)
                    val rowProvider = row.getOrNull(SongProviderTable.provider)
                    val rowExternalId = row.getOrNull(SongProviderTable.externalId)

                    val providerMatch = song.originalUrl.isNotBlank() && (
                            rowRawUrl == song.originalUrl || (
                                    lookup != null && rowProvider == lookup.second && rowExternalId == lookup.third
                                    )
                            )

                    val legacyMatch = song.originalUrl.isNotBlank() && row[SongTable.originalUrl] == song.originalUrl

                    val metadataMatch = legacyMatch || providerMatch || (
                            song.originalUrl.isBlank() &&
                                    row[SongTable.title] == song.title &&
                                    row[SongTable.trackNumber] == song.trackNumber &&
                                    row[SongTable.discNumber] == song.discNumber &&
                                    row[SongTable.explicit] == song.explicit &&
                                    albumName == song.album.name
                            )

                    if (pathMatch || metadataMatch) {
                        existingSongMap[song] =
                            songId to (row[SongTable.title] to row[SongTable.explicit])
                        return@firstOrNull true
                    }
                    return@firstOrNull false
                }
            }
            return@dbQuery existingSongMap
        }

    override suspend fun createBatch(songs: List<InsertableSong>): Map<UUID, Song> =
        coroutineScope {
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

            val artistIdMap: Map<String, List<UUID>> = uniqueArtistNames
                .chunked(maxBatchSize)
                .map { batch ->
                    async {
                        artistService.getOrBulkCreate(batch).entries
                    }
                }
                .awaitAll()
                .flatten()
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, values) -> values.flatten() }

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

            val dirtySongs = existingSongMap.filter { (song, data) ->
                val (_, meta) = data
                val (dbTitle, dbExplicit) = meta
                dbTitle != song.title || dbExplicit != song.explicit
            }

            if (dirtySongs.isNotEmpty()) {
                dbQuery {
                    dirtySongs.forEach { (song, data) ->
                        val (songId, _) = data
                        SongTable.update({ SongTable.id eq songId }) {
                            it[title] = song.title
                            it[explicit] = song.explicit
                        }
                    }
                }
            }

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
                songInsertResult.map {
                    it[SongTable.id].value to filteredSongs[songInsertResult.indexOf(
                        it
                    )]
                }

            val musicBrainzBatch = insertedSongs.mapNotNull { (songId, songData) ->
                songData.musicBrainzId?.let { mbId -> songId to mbId }
            }

            if (musicBrainzBatch.isNotEmpty()) {
                dbQuery {
                    val uniqueMbIds = musicBrainzBatch.map { it.second }.distinct()
                    val existingMbIds = MBRecordingTable
                        .select(MBRecordingTable.id)
                        .where { MBRecordingTable.id inList uniqueMbIds }
                        .map { it[MBRecordingTable.id].value }
                        .toSet()

                    val missingMbIds = uniqueMbIds.filter { it !in existingMbIds }

                    if (missingMbIds.isNotEmpty()) {
                        MBRecordingTable.batchInsert(missingMbIds) { mbId ->
                            this[MBRecordingTable.id] = EntityID(mbId, MBRecordingTable)
                            this[MBRecordingTable.title] = ""
                        }
                    }

                    SongMusicBrainzTable.batchInsert(musicBrainzBatch) { (songId, mbId) ->
                        this[SongMusicBrainzTable.songId] = songId
                        this[SongMusicBrainzTable.musicBrainzId] = mbId
                    }
                }
            }

            insertedSongs.forEach { (songId, songData) ->
                if (songData.originalUrl.isNotBlank()) {
                    addProviderUrl(songId, songData.originalUrl)
                }
            }

            val audioDataBatch = insertedSongs.mapNotNull { (songId, songData) ->
                songData.audioData?.bpm?.let { bpm -> songId to bpm }
            }

            if (audioDataBatch.isNotEmpty()) {
                dbQuery {
                    SongAudioDataTable.batchInsert(audioDataBatch) { (songId, bpmValue) ->
                        this[SongAudioDataTable.songId] = songId
                        this[SongAudioDataTable.bpm] = bpmValue
                    }
                }
            }

            val songArtistLinks = insertedSongs.flatMap { (songId, songData) ->
                songData.artists.flatMap { artistName ->
                    artistIdMap[artistName]?.map { artistId ->
                        Pair(songId, artistId)
                    } ?: emptyList()
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

            byIds(insertedSongs.map { it.first }).associateBy { it.id }
        }

    suspend fun upsertSong(song: Song) = dbQuery {
        SongTable.upsert(SongTable.id) {
            it[id] = song.id
            it[title] = song.title
            it[albumId] = song.album?.id?.let { albumId -> EntityID(albumId, AlbumTable) }!!
            it[duration] = song.duration
            it[explicit] = song.explicit
            it[releaseDate] = getISOFromDate(song.releaseDate)
            it[lyrics] = song.lyrics
            it[filePath] = song.path
            it[originalUrl] = song.originalUrl
            it[trackNumber] = song.trackNumber
            it[discNumber] = song.discNumber
            it[copyright] = song.copyright
            it[sampleRate] = song.sampleRate
            it[bitsPerSample] = song.bitsPerSample
            it[bitRate] = song.bitRate
            it[fileSize] = song.fileSize
            it[cover] = song.coverId?.let { coverId -> EntityID(coverId, ImageTable) }
        }

        if (song.originalUrl.isNotBlank()) {
            addProviderUrl(song.id, song.originalUrl)
        }

        SongArtistTable.deleteWhere { SongArtistTable.songId eq song.id }
        SongArtistTable.batchInsert(song.artists) { artist ->
            this[SongArtistTable.songId] = song.id
            this[SongArtistTable.artistId] = artist.id
        }

        if (song.musicBrainzId != null) {
            val mbId = song.musicBrainzId!!
            if (MBRecordingTable.selectAll().where { MBRecordingTable.id eq mbId }.empty()) {
                MBRecordingTable.insert {
                    it[id] = EntityID(mbId, MBRecordingTable)
                    it[title] = song.title
                }
            }

            SongMusicBrainzTable.upsert(SongMusicBrainzTable.songId) {
                it[songId] = song.id
                it[musicBrainzId] = mbId
                it[lastCheck] = System.currentTimeMillis()
            }
        }
    }

    suspend fun moveSongs(oldPath: String, newPath: String, originalIdPrefix: String? = null): Int =
        dbQuery {
            val affectedSongs = if (originalIdPrefix != null) {
                SongTable.innerJoin(AlbumTable)
                    .select(SongTable.id)
                    .where {
                        (SongTable.filePath like "$oldPath%") and
                                (AlbumTable.originalId like "$originalIdPrefix%")
                    }
                    .map { it[SongTable.id].value }
            } else {
                SongTable.select(SongTable.id)
                    .where { SongTable.filePath like "$oldPath%" }
                    .map { it[SongTable.id].value }
            }

            if (affectedSongs.isEmpty()) return@dbQuery 0

            val songs = affectedSongs.chunked(20000).flatMap {
                SongTable.select(SongTable.id, SongTable.filePath)
                    .where { SongTable.id inList it }
                    .toList()
            }

            songs.forEach { row ->
                val id = row[SongTable.id].value
                val currentPath = row[SongTable.filePath]
                val newFilePath = currentPath.replaceFirst(oldPath, newPath)

                SongTable.update({ SongTable.id eq id }) {
                    it[filePath] = newFilePath
                }
            }

            songs.size
        }
}
