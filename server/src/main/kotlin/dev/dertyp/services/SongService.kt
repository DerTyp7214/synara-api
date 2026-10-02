package dev.dertyp.services

import dev.dertyp.*
import dev.dertyp.audio.AudioProbe
import dev.dertyp.audio.isLossless
import dev.dertyp.audio.LosslessFormat
import dev.dertyp.audio.TranscodedSongRepository
import dev.dertyp.audio.Transcoder
import dev.dertyp.audio.losslessFormat
import dev.dertyp.core.*
import dev.dertyp.core.date.*
import dev.dertyp.core.db.Dialect
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.plugins.SongLibrary
import dev.dertyp.routing.rest.RestFileProvider
import dev.dertyp.services.AlbumService.Companion.calculateAlbumStats
import dev.dertyp.services.AlbumService.Companion.mapAlbum
import dev.dertyp.services.ArtistService.Companion.mapArtist
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.*
import dev.dertyp.utils.LogParam
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.http.*
import io.ktor.server.application.*
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
import java.time.Instant
import java.util.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

class SongRpcService(
    private val user: User,
    private val songService: SongService,
    private val client: ClientInfo = ClientInfo.LEGACY,
) : ISongService,
    RestFileProvider {
    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        if (methodName == "streamSong") {
            val id = args[0] as? UUID ?: return null
            val song = songService.byId(id) ?: return null
            return songService.resolveRawStream(song, client)
        }
        if (methodName == "streamSongAtmos") {
            val id = args[0] as? UUID ?: return null
            val song = songService.byId(id) ?: return null
            return songService.resolveAtmosStream(song, client)
        }
        if (methodName == "downloadSong") {
            val id = args[0] as? UUID ?: return null
            val quality = args[1] as? Int ?: return null
            val force = args.getOrNull(4) as? Boolean ?: true
            val format = args.getOrNull(5) as? AudioFormat ?: AudioFormat.OPUS
            val song = songService.byId(id) ?: return null
            val file = File(song.path)
            if (!file.exists()) return null
            if (quality > 0) {
                return songService.transcodeAndRecord(id, file, quality, force, format)
            }
            return songService.resolveRawStream(song, client)
        }
        return null
    }

    override suspend fun setLiked(
        id: UUID,
        liked: Boolean,
        addedAt: Instant?
    ): UserSong? = songService.setLikedReturning(id, user.id, liked, addedAt)

    override suspend fun setLikeLevel(id: UUID, level: LikeLevel): UserSong? =
        songService.setLikeLevelReturning(id, user.id, level)

    override suspend fun setLyrics(id: UUID, @LogParam("size") lyrics: List<String>): UserSong? =
        songService.setLyrics(id, user.id, lyrics)

    override suspend fun setArtists(id: UUID, artistIds: List<UUID>): UserSong? =
        songService.setArtists(id, artistIds, user.id)

    override suspend fun updateSong(song: Song): UserSong? =
        songService.updateSong(song, user.id)

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
    ): PaginatedResponse<UserSong> =
        songService.likedByArtist(page, pageSize, artistId, explicit, user.id)

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

    override suspend fun superLikedSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean
    ): PaginatedResponse<UserSong> = songService.superLikedSongs(page, pageSize, explicit, user.id)

    override suspend fun exportFavouritesAsCsv(): String =
        songService.exportFavouritesAsCsv(user.id)

    override suspend fun allSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        tags: List<SongTag>,
        excludeTags: List<SongTag>,
        titleTags: List<TitleTagKind>,
        excludeTitleTags: List<TitleTagKind>
    ): PaginatedResponse<UserSong> =
        songService.allSongs(page, pageSize, explicit, user.id, tags, excludeTags, titleTags, excludeTitleTags)

    override suspend fun byColor(
        page: Int,
        pageSize: Int,
        color: Int,
        range: Int,
        explicit: Boolean
    ): PaginatedResponse<UserSong> =
        songService.byColor(page, pageSize, color, range, explicit, user.id)

    override suspend fun deleteSongs(@LogParam("size") ids: List<UUID>): Boolean =
        songService.deleteSongs(ids)

    override suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        liked: Boolean
    ): PaginatedResponse<UserSong> =
        songService.rankedSearch(page, pageSize, query, explicit, user.id, liked)

    override suspend fun searchByLyrics(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean
    ): PaginatedResponse<UserSong> =
        songService.searchByLyrics(page, pageSize, query, explicit, user.id)

    override fun streamSong(id: UUID, offset: Long, chunkSize: Int): Flow<ByteArray>? =
        songService.streamSong(id, offset, chunkSize, client)

    override fun downloadSong(
        id: UUID,
        quality: Int,
        offset: Long,
        chunkSize: Int,
        force: Boolean,
        format: AudioFormat
    ): Flow<ByteArray>? = songService.downloadSong(id, quality, offset, chunkSize, force, format, client)

    override suspend fun getStreamSize(id: UUID): Long = songService.getStreamSize(id, client)

    override fun streamSongAtmos(id: UUID, offset: Long, chunkSize: Int): Flow<ByteArray>? =
        songService.streamSongAtmos(id, offset, chunkSize, client)

    override suspend fun getAtmosStreamSize(id: UUID): Long = songService.getAtmosStreamSize(id, client)

    override suspend fun getDownloadSize(
        id: UUID,
        quality: Int,
        force: Boolean,
        format: AudioFormat
    ): Long = songService.getDownloadSize(id, quality, force, format, client)

    override fun allSongIds(
        explicit: Boolean,
        tags: List<SongTag>,
        excludeTags: List<SongTag>,
        titleTags: List<TitleTagKind>,
        excludeTitleTags: List<TitleTagKind>
    ): Flow<UUID> =
        songService.allSongIds(explicit, tags, excludeTags, titleTags, excludeTitleTags)

    override fun likedSongIds(explicit: Boolean): Flow<UUID> =
        songService.likedSongIds(explicit, user.id)

    override fun superLikedSongIds(explicit: Boolean): Flow<UUID> =
        songService.superLikedSongIds(explicit, user.id)

    override fun songIdsByArtist(artistId: UUID): Flow<UUID> = songService.songIdsByArtist(artistId)

    override fun songIdsByAlbum(albumId: UUID): Flow<UUID> = songService.songIdsByAlbum(albumId)

    override fun songIdsByPlaylist(playlistId: UUID): Flow<UUID> =
        songService.songIdsByPlaylist(playlistId)

    override fun songIdsByUserPlaylist(playlistId: UUID): Flow<UUID> =
        songService.songIdsByUserPlaylist(playlistId)

    override suspend fun moveSongs(
        oldPath: String,
        newPath: String,
        originalIdPrefix: String?
    ): Int {
        if (!user.isAdmin) throw IllegalStateException("Only admins can move songs")
        return songService.moveSongs(oldPath, newPath, originalIdPrefix)
    }

    override suspend fun extendedMetadata(id: UUID): SongExtendedMetadata? =
        songService.extendedMetadata(id)
}

private const val SONG_DETAIL_CHUNK_SIZE = 5000

private val descendingOrders = setOf(SortOrder.DESC, SortOrder.DESC_NULLS_FIRST, SortOrder.DESC_NULLS_LAST)

private fun <T> ExpressionWithColumnType<T>.sortAggregate(descending: Boolean): Expression<T> =
    CustomFunction(if (descending) "MAX" else "MIN", columnType, this)

class SongService(private val searchIndexWorker: SearchIndexWorker? = null) : SongLibrary,
    Service() {
    private val environment by inject<ApplicationEnvironment>()
    private val musicBrainzService by inject<MusicBrainzService>()
    private val cachedMusicBrainzService by inject<CachedMusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val acoustIdService by inject<AcoustIdService>()
    private val artistService by inject<ArtistService>()
    private val genreService by inject<GenreService>()
    private val linkResolverService by inject<LinkResolverService>()
    private val libraryFileDeleter by inject<LibraryFileDeleter>()
    private val redisSearchService by inject<RedisSearchService>()
    private val transcoder by inject<Transcoder>()
    private val transcodedSongRepository by inject<TranscodedSongRepository>()

    val albumArtistAlias = ArtistTable.alias("albumArtistAlias")
    val albumArtistMusicBrainzAlias = ArtistMusicBrainzTable.alias("albumArtistMusicBrainzAlias")
    val albumArtistAliasAlias = ArtistAliasTable.alias("albumArtistAliasAlias")

    val songCreditedAliasAlias = ArtistAliasTable.alias("songCreditedAliasAlias")
    val albumCreditedAliasAlias = ArtistAliasTable.alias("albumCreditedAliasAlias")

    val artistGroupAlias = ArtistTable.alias("artistGroup")
    val artistMemberAlias = ArtistTable.alias("artistMember")
    val albumArtistGroupAlias = ArtistTable.alias("albumArtistGroup")
    val albumArtistMemberAlias = ArtistTable.alias("albumArtistMember")

    val artistGroupJoinAlias = ArtistMemberTable.alias("artistGroupJoin")
    val artistMemberJoinAlias = ArtistMemberTable.alias("artistMemberJoin")
    val albumArtistGroupJoinAlias = ArtistMemberTable.alias("albumArtistGroupJoin")
    val albumArtistMemberJoinAlias = ArtistMemberTable.alias("albumArtistMemberJoin")

    val albumFollowedArtistAlias = FollowedArtistTable.alias("albumFollowedArtist")

    val songImageAlias = ImageTable.alias("songImage")
    val albumImageAlias = ImageTable.alias("albumImage")
    val artistImageAlias = ImageTable.alias("artistImage")
    val albumArtistImageAlias = ImageTable.alias("albumArtistImage")
    val songAnimatedImageAlias = AnimatedImageTable.alias("songAnimatedImage")
    val songAnimatedFrameAlias = ImageTable.alias("songAnimatedFrame")
    val albumAnimatedImageAlias = AnimatedImageTable.alias("albumAnimatedImage")
    val albumAnimatedFrameAlias = ImageTable.alias("albumAnimatedFrame")

    companion object {
        fun mapSong(
            resultRow: ResultRow,
            genres: List<Genre> = listOf(),
            blurHashColumn: Expression<String?>? = null,
            animatedCoverImageIdColumn: Expression<EntityID<UUID>?>? = null,
            animatedCoverBlurHashColumn: Expression<String?>? = null,
        ): Song {
            val id = resultRow[SongTable.id].value

            return Song(
                id = id,
                title = displaySongTitle(resultRow[SongTable.title]),
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
                audio = AudioInfo(
                    codec = resultRow[SongTable.format],
                    sampleRate = resultRow[SongTable.sampleRate],
                    bitsPerSample = resultRow[SongTable.bitsPerSample],
                    bitRate = resultRow[SongTable.bitRate],
                    fileSize = resultRow[SongTable.fileSize],
                    channels = resultRow[SongTable.channels],
                ),
                isrc = resultRow[SongTable.isrc],
                coverId = resultRow[SongTable.cover]?.value,
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash),
                musicBrainzId = resultRow.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value,
                genres = genres,
                animatedCoverId = resultRow[SongTable.animatedCover]?.value,
                animatedCoverImageId = animatedCoverImageIdColumn?.let { resultRow.getOrNull(it) }?.value,
                animatedCoverBlurHash = animatedCoverBlurHashColumn?.let { resultRow.getOrNull(it) },
                audioStartMs = resultRow.getOrNull(SongTable.audioStartMs),
                tags = resultRow.titleTags(),
            )
        }

        fun mapUserSong(
            resultRow: ResultRow,
            genres: List<Genre> = listOf(),
            blurHashColumn: Expression<String?>? = null,
            animatedCoverImageIdColumn: Expression<EntityID<UUID>?>? = null,
            animatedCoverBlurHashColumn: Expression<String?>? = null,
        ): UserSong {
            val song = mapSong(resultRow, genres, blurHashColumn, animatedCoverImageIdColumn, animatedCoverBlurHashColumn)
            val isFavourite = resultRow.getOrNull(UserSongTable.isFavourite) ?: false
            val superLikedAt = resultRow.getOrNull(UserSongTable.superLikedAt)

            return song.toUserSong(
                isFavourite = isFavourite,
                userSongCreatedAt = resultRow.getOrNull(UserSongTable.createdAt).date,
                userSongUpdatedAt = resultRow.getOrNull(UserSongTable.updatedAt).date,
                likeLevel = likeLevel(isFavourite, superLikedAt),
                superLikedAt = superLikedAt.date,
            )
        }

        private fun Song.toUserSong(
            isFavourite: Boolean,
            userSongCreatedAt: PlatformDate?,
            userSongUpdatedAt: PlatformDate?,
            likeLevel: LikeLevel,
            superLikedAt: PlatformDate?,
        ): UserSong = UserSong(
            id = id,
            title = title,
            artists = artists,
            album = album,
            duration = duration,
            explicit = explicit,
            releaseDate = releaseDate,
            lyrics = lyrics,
            path = path,
            originalUrl = originalUrl,
            trackNumber = trackNumber,
            discNumber = discNumber,
            copyright = copyright,
            audio = audio,
            coverId = coverId,
            blurHash = blurHash,
            musicBrainzId = musicBrainzId,
            isrc = isrc,
            genres = genres,
            animatedCoverId = animatedCoverId,
            animatedCoverImageId = animatedCoverImageId,
            animatedCoverBlurHash = animatedCoverBlurHash,
            audioStartMs = audioStartMs,
            tags = tags,
            isFavourite = isFavourite,
            userSongCreatedAt = userSongCreatedAt,
            userSongUpdatedAt = userSongUpdatedAt,
            likeLevel = likeLevel,
            superLikedAt = superLikedAt,
        )

        fun likeLevel(isFavourite: Boolean, superLikedAt: Long?): LikeLevel = when {
            superLikedAt != null -> LikeLevel.SUPER
            isFavourite -> LikeLevel.LIKE
            else -> LikeLevel.NONE
        }
    }

    inline fun <reified T : BaseSong> map(
        resultRow: ResultRow,
        genres: List<Genre> = listOf(),
        blurHashColumn: Expression<String?>? = null,
        animatedCoverImageIdColumn: Expression<EntityID<UUID>?>? = null,
        animatedCoverBlurHashColumn: Expression<String?>? = null,
    ): BaseSong =
        if (T::class == UserSong::class) mapUserSong(resultRow, genres, blurHashColumn, animatedCoverImageIdColumn, animatedCoverBlurHashColumn)
        else mapSong(resultRow, genres, blurHashColumn, animatedCoverImageIdColumn, animatedCoverBlurHashColumn)

    private fun ColumnSet.userSong(userId: UUID?) = if (userId != null) {
        leftJoin(
            UserSongTable,
            onColumn = { SongTable.id },
            otherColumn = { UserSongTable.songId },
            additionalConstraint = { UserSongTable.userId eq userId }
        )
    } else this

    override suspend fun setLiked(songId: UUID, userId: UUID, liked: Boolean, addedAt: Instant?) {
        setLikedReturning(songId, userId, liked, addedAt)
    }

    override suspend fun setLikedReturning(
        songId: UUID,
        userId: UUID,
        liked: Boolean,
        addedAt: Instant?
    ): UserSong? {
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
                    if (!liked) it[UserSongTable.superLikedAt] = null
                    it[UserSongTable.updatedAt] = (addedAt ?: Instant.now()).toEpochMilli()
                }
            }
        }

        return byId(songId, userId)
    }

    suspend fun setLikeLevelReturning(songId: UUID, userId: UUID, level: LikeLevel): UserSong? {
        dbQuery {
            val now = Instant.now().toEpochMilli()
            val liked = level != LikeLevel.NONE
            val condition = UserSongTable.userId eq userId and (UserSongTable.songId eq songId)
            fun current() = UserSongTable
                .select(UserSongTable.isFavourite, UserSongTable.superLikedAt)
                .where { condition }
                .singleOrNull()

            val existing = current()
            val inserted = existing == null && UserSongTable.insertIgnore {
                it[UserSongTable.songId] = songId
                it[UserSongTable.userId] = userId
                it[UserSongTable.isFavourite] = liked
                if (level == LikeLevel.SUPER) it[UserSongTable.superLikedAt] = now
            }.insertedCount == 1

            if (!inserted) {
                val row = existing ?: current() ?: return@dbQuery
                val wasLiked = row[UserSongTable.isFavourite]
                val previousSuperLikedAt = row[UserSongTable.superLikedAt]
                UserSongTable.update({ condition }) {
                    it[UserSongTable.isFavourite] = liked
                    it[UserSongTable.superLikedAt] =
                        if (level == LikeLevel.SUPER) previousSuperLikedAt ?: now else null
                    if (wasLiked != liked) it[UserSongTable.updatedAt] = now
                }
            }
        }

        return byId(songId, userId)
    }

    suspend fun setArtists(id: UUID, artistIds: List<UUID>, userId: UUID): UserSong? = dbQuery {
        SongArtistTable.deleteWhere { SongArtistTable.songId eq id }
        SongArtistTable.batchInsert(artistIds.withIndex().toList()) { (index, artistId) ->
            this[SongArtistTable.songId] = id
            this[SongArtistTable.artistId] = artistId
            this[SongArtistTable.position] = index
        }

        return@dbQuery byId(id, userId).also {
            it?.let { song ->
                if (!File(song.path).isLossless) return@let
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
                if (!File(song.path).isLossless) return@let
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
        val mbRecording = if (musicBrainzId != null) {
            cachedMusicBrainzService.getRecording(musicBrainzId, HttpClientPriority.HIGH)
        } else null

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

            val mbIsrc = mbRecording?.isrcs?.firstOrNull()
            val mbDate = mbRecording?.releases?.mapNotNull { it.date }?.minOrNull()

            if (mbIsrc != null || mbDate != null) {
                SongTable.update({ SongTable.id eq id }) {
                    if (mbIsrc != null) it[isrc] = mbIsrc
                    if (mbDate != null) it[releaseDate] = mbDate
                }
            }
        }

        return byId(id, userId).also { song ->
            if (song == null || !File(song.path).isLossless) return@also
            try {
                val file = AudioFileIO.read(File(song.path))

                if (musicBrainzId != null) {
                    file.tag.setField(FieldKey.MUSICBRAINZ_TRACK_ID, musicBrainzId.toString())
                } else {
                    file.tag.deleteField(FieldKey.MUSICBRAINZ_TRACK_ID)
                }

                if (mbRecording?.isrcs?.isNotEmpty() == true) {
                    file.tag.setField(FieldKey.ISRC, mbRecording.isrcs!!.first())
                }

                file.commit()
            } catch (e: Exception) {
                logger.error("Failed to set musicBrainzId for $id: ${e.message}", e)
            }
        }
    }

    suspend fun updateSong(song: Song, userId: UUID): UserSong? {
        val normalized = song.withSplitTitleTags()
        setMusicBrainzId(normalized.id, normalized.musicBrainzId, userId)

        dbQuery {
            SongTable.update({ SongTable.id eq normalized.id }) {
                it[title] = normalized.title
                it[titleTags] = encodeTitleTags(normalized.tags)
                normalized.album?.id?.let { albumUuid -> it[albumId] = EntityID(albumUuid, AlbumTable) }
                it[releaseDate] = getISOFromDate(normalized.releaseDate)
                it[lyrics] = normalized.lyrics
                it[trackNumber] = normalized.trackNumber
                it[discNumber] = normalized.discNumber
            }
            syncSongTitleTags(normalized.id, normalized.tags)

            SongArtistTable.deleteWhere { SongArtistTable.songId eq normalized.id }
            val creditedAliasIds = normalized.artists.associate { artist ->
                artist.id to artist.creditedName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { artistService.getOrCreateAliasTx(artist.id, it) }
            }
            SongArtistTable.batchInsert(normalized.artists.withIndex().toList()) { (index, artist) ->
                this[SongArtistTable.songId] = normalized.id
                this[SongArtistTable.artistId] = artist.id
                this[SongArtistTable.creditedAliasId] = creditedAliasIds[artist.id]
                this[SongArtistTable.position] = index
                this[SongArtistTable.joinPhrase] = artist.joinPhrase
            }
        }

        return byId(normalized.id, userId).also { updated ->
            updated?.let { s ->
                if (!File(s.path).isLossless) return@let
                try {
                    val file = AudioFileIO.read(File(s.path))
                    file.tag.apply {
                        setField(FieldKey.TITLE, s.fullTitle)
                        deleteField(FieldKey.ARTIST)
                        for (name in s.artists.map { artist -> artist.name }.sorted()) {
                            addField(FieldKey.ARTIST, name)
                        }
                        setField(FieldKey.LYRICS, s.lyrics)
                    }
                    file.commit()
                } catch (e: Exception) {
                    logger.error("Failed to update song ${normalized.id}: ${e.message}", e)
                }
            }
        }
    }

    suspend fun fetchMusicBrainzId(
        id: UUID,
        userId: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): UserSong? {
        val song = byId(id, userId) ?: return null

        val mbRecording = if (song.musicBrainzId != null) {
            cachedMusicBrainzService.getRecording(song.musicBrainzId!!, priority)
        } else {
            acoustIdService.matchRecording(song, priority)?.let { cachedMusicBrainzService.getRecording(it, priority) }
                ?: musicBrainzService.searchMb(song, priority)
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

            val resolvedArtists = mutableListOf<ResolvedCredit>()
            val namesToResolve = artistCredits
                .filter { it.artist?.id == null || !existingArtistsByMbId.containsKey(it.artist?.id) }
                .mapNotNull { it.name ?: it.artist?.name }
                .distinct()

            val artistsByName = if (namesToResolve.isNotEmpty()) {
                artistService.getOrBulkCreateWithResult(namesToResolve)
            } else null

            val allCandidateIds =
                artistsByName?.nameToIds?.values?.flatten()?.distinct() ?: emptyList()
            val candidatesById = if (allCandidateIds.isNotEmpty()) {
                artistService.byIds(allCandidateIds, userId).associateBy { it.id }
            } else emptyMap()

            val candidatesWithEvidence =
                if (allCandidateIds.isNotEmpty() && mbArtistIds.isNotEmpty()) {
                    dbQuery {
                        val fromSongs = SongArtistTable
                            .innerJoin(
                                SongMusicBrainzTable,
                                onColumn = { SongArtistTable.songId },
                                otherColumn = { SongMusicBrainzTable.songId })
                            .innerJoin(
                                MBRecordingArtistCreditTable,
                                onColumn = { SongMusicBrainzTable.musicBrainzId },
                                otherColumn = { MBRecordingArtistCreditTable.recordingId })
                            .innerJoin(
                                SongTable,
                                onColumn = { SongArtistTable.songId },
                                otherColumn = { SongTable.id })
                            .select(SongArtistTable.artistId, MBRecordingArtistCreditTable.artistId)
                            .where { (SongArtistTable.artistId inList allCandidateIds) and (MBRecordingArtistCreditTable.artistId inList mbArtistIds) and (SongTable.id neq id) }
                            .map { it[SongArtistTable.artistId].value to it[MBRecordingArtistCreditTable.artistId].value }

                        val fromAlbums = AlbumArtistTable
                            .innerJoin(
                                AlbumMusicBrainzTable,
                                onColumn = { AlbumArtistTable.albumId },
                                otherColumn = { AlbumMusicBrainzTable.albumId })
                            .innerJoin(
                                MBReleaseArtistCreditTable,
                                onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                                otherColumn = { MBReleaseArtistCreditTable.releaseId })
                            .select(AlbumArtistTable.artistId, MBReleaseArtistCreditTable.artistId)
                            .where { (AlbumArtistTable.artistId inList allCandidateIds) and (MBReleaseArtistCreditTable.artistId inList mbArtistIds) }
                            .map { it[AlbumArtistTable.artistId].value to it[MBReleaseArtistCreditTable.artistId].value }

                        (fromSongs + fromAlbums).toSet()
                    }
                } else emptySet()

            artistCredits.forEach { credit ->
                val mbId = credit.artist?.id
                val name = credit.name ?: credit.artist?.name ?: return@forEach
                val canonicalName = credit.artist?.name ?: name

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
                        artist = artistService.createArtist(
                            name = canonicalName,
                            musicBrainzId = mbId,
                            userId = userId
                        )
                    } else {
                        artist = candidates.firstOrNull()
                    }
                }

                if (artist != null) {
                    val creditedName = credit.name?.takeIf { it.isNotBlank() && it != artist.name }
                    resolvedArtists.add(ResolvedCredit(artist, creditedName, credit.joinphrase))
                }
            }

            val finalArtists = resolvedArtists.distinctBy { it.artist.id }

            if (finalArtists.isNotEmpty()) {
                dbQuery {
                    SongArtistTable.deleteWhere { SongArtistTable.songId eq id }
                    val creditedAliasIds = finalArtists.associate { (artist, creditedName) ->
                        artist.id to creditedName?.let { artistService.getOrCreateAliasTx(artist.id, it) }
                    }
                    SongArtistTable.batchInsert(finalArtists.withIndex().toList()) { (index, credit) ->
                        this[SongArtistTable.songId] = id
                        this[SongArtistTable.artistId] = credit.artist.id
                        this[SongArtistTable.creditedAliasId] = creditedAliasIds[credit.artist.id]
                        this[SongArtistTable.position] = index
                        this[SongArtistTable.joinPhrase] = credit.joinPhrase
                    }
                }
            }

            val genres = (mbRecording.genres?.map { it.name }
                ?: emptyList()) + (mbRecording.releases?.flatMap {
                it.genres?.map { g -> g.name } ?: emptyList()
            } ?: emptyList()) + (mbRecording.releases?.flatMap {
                it.releaseGroup?.genres?.map { g -> g.name } ?: emptyList()
            } ?: emptyList())
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
        explicit: Boolean,
        tags: List<TitleTag> = emptyList()
    ): UUID? = dbQuery {
        SongTable.select(SongTable.id)
            .andWhere { SongTable.title eq title }
            .andWhere { SongTable.albumId eq albumId }
            .andWhere { SongTable.trackNumber eq trackNumber }
            .andWhere { SongTable.discNumber eq discNumber }
            .andWhere { SongTable.explicit eq explicit }
            .andWhere { SongTable.titleTags eq encodeTitleTags(tags) }
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

    fun songIdsForProviderEnrichment(): Flow<UUID> = flow {
        val oneWeekAgo = Clock.System.now() - 30.days

        SongTable
            .select(SongTable.id)
            .where {
                SongTable.lastProviderEnrichment.isNull() or
                        (SongTable.lastProviderEnrichment less oneWeekAgo.toEpochMilliseconds())
            }
            .orderBy(SongTable.lastProviderEnrichment, SortOrder.ASC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    fun songIdsForIsrcEnrichment(provider: String): Flow<UUID> = flow {
        val now = Clock.System.now().toEpochMilliseconds()
        val threshold = now - 30.days.inWholeMilliseconds

        SongTable
            .leftJoin(
                ProviderEnrichmentCheckTable,
                onColumn = { SongTable.id },
                otherColumn = { ProviderEnrichmentCheckTable.entityId },
                additionalConstraint = {
                    (ProviderEnrichmentCheckTable.provider eq provider) and
                            (ProviderEnrichmentCheckTable.type eq ProviderEnrichmentType.SONG)
                }
            )
            .select(SongTable.id)
            .where { (SongTable.isrc.isNotNull()) and (SongTable.isrc neq "") }
            .andWhere {
                notExists(SongProviderTable.select(SongProviderTable.songId).where {
                    (SongProviderTable.songId eq SongTable.id) and (SongProviderTable.provider eq provider)
                })
            }
            .andWhere {
                ProviderEnrichmentCheckTable.lastCheck.isNull() or
                        (ProviderEnrichmentCheckTable.lastCheck less threshold)
            }
            .fetchBatchedResultsByIdKeyset(SongTable.id, 1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    suspend fun updateProviderEnrichmentCheck(
        id: UUID,
        provider: String,
        type: ProviderEnrichmentType
    ) = dbQuery {
        ProviderEnrichmentCheckTable.upsert(
            ProviderEnrichmentCheckTable.entityId,
            ProviderEnrichmentCheckTable.provider,
            ProviderEnrichmentCheckTable.type
        ) {
            it[entityId] = id
            it[ProviderEnrichmentCheckTable.provider] = provider
            it[ProviderEnrichmentCheckTable.type] = type
            it[lastCheck] =
                Clock.System.now().toEpochMilliseconds() + (1..5).random().days.inWholeMilliseconds
        }
    }

    suspend fun byId(id: UUID, userId: UUID): UserSong? = querySingle(userId) {
        where { SongTable.id eq id }
    }

    suspend fun byMusicBrainzId(musicBrainzId: UUID, userId: UUID): List<UserSong> =
        querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
            where { SongMusicBrainzTable.musicBrainzId eq musicBrainzId }
        }.data

    suspend fun byTitle(
        page: Int,
        pageSize: Int,
        title: String,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId) {
            where { SongTable.title eq title }
        }

    suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId, { withAlbum() }) {
            val songIds = SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId eq artistId }

            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId eq artistId }

            where { SongTable.id inSubQuery songIds }
            orWhere { SongTable.albumId inSubQuery albumIds }
            orderByReleaseThenAlbum()
        }

    suspend fun likedByArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, explicit, userId, { withAlbum() }) {
            val songIds = SongArtistTable
                .select(SongArtistTable.songId)
                .where { SongArtistTable.artistId eq artistId }

            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.albumId)
                .where { AlbumArtistTable.artistId eq artistId }

            where { SongTable.id inSubQuery songIds }
            orWhere { SongTable.albumId inSubQuery albumIds }
            andWhere { UserSongTable.isFavourite eq true }
            orderByReleaseThenAlbum()
        }

    suspend fun byAlbum(
        page: Int,
        pageSize: Int,
        albumId: UUID,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId) {
            where { SongTable.albumId eq albumId }
            orderBy(SongTable.discNumber, SortOrder.ASC)
            orderBy(SongTable.trackNumber, SortOrder.ASC)
            orderBy(utf8SortKey(SongTable.title), SortOrder.ASC)
        }

    suspend fun byPlaylist(
        page: Int,
        pageSize: Int,
        playlistId: UUID,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId, {
            leftJoin(PlaylistSongTable)
        }) {
            where { PlaylistSongTable.playlistId eq playlistId }
            orderBy(PlaylistSongTable.position, SortOrder.ASC)
            orderBy(SongTable.id, SortOrder.ASC)
        }

    suspend fun byUserPlaylist(
        page: Int,
        pageSize: Int,
        playlistId: UUID,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(page, pageSize, true, userId, {
            leftJoin(UserPlaylistSongTable)
        }) {
            where { UserPlaylistSongTable.playlistId eq playlistId }
            orderBy(UserPlaylistSongTable.addedAt, SortOrder.ASC)
            orderBy(SongTable.id, SortOrder.ASC)
        }

    override suspend fun byOriginalIds(ids: Collection<PrefixedId>, userId: UUID): List<UserSong> =
        ids.chunked(PROVIDER_LOOKUP_CHUNK_SIZE).flatMap { idChunk ->
            val parsedLookups = idChunk.mapNotNull { providerLookup(it, Type.SONG) }

            querySongs<UserSong>(0, Int.MAX_VALUE, true, userId) {
                val songIdsFromProviders = SongProviderTable
                    .select(SongProviderTable.songId)
                    .where {
                        (SongProviderTable.type eq Type.SONG.value) and (
                                (SongProviderTable.rawUrl inList idChunk) or
                                        (SongProviderTable.externalId inList idChunk) or
                                        SongProviderTable.matchesAny(parsedLookups)
                                )
                    }

                where {
                    SongTable.originalUrl inList idChunk
                }
                orWhere {
                    SongTable.id inSubQuery songIdsFromProviders
                }
            }.data
        }

    override suspend fun byOriginalUrls(
        urls: Collection<String>,
        userId: UUID
    ): Map<String, UserSong?> {
        if (urls.isEmpty()) return mutableMapOf()

        val distinctUrls = urls.distinct()
        val parsedLookups = distinctUrls.mapNotNull { url -> providerLookup(url, Type.SONG)?.let { url to it } }.toMap()

        return dbQuery {
            val urlChunks = distinctUrls.chunked(PROVIDER_LOOKUP_CHUNK_SIZE)
            val providerRows = urlChunks.flatMap { urlChunk ->
                SongProviderTable
                    .select(
                        SongProviderTable.songId,
                        SongProviderTable.rawUrl,
                        SongProviderTable.provider,
                        SongProviderTable.externalId
                    )
                    .where {
                        (SongProviderTable.type eq Type.SONG.value) and (
                                (SongProviderTable.rawUrl inList urlChunk) or
                                        SongProviderTable.matchesAny(urlChunk.mapNotNull { parsedLookups[it] })
                                )
                    }
                    .map { row ->
                        ProviderUrlRow(
                            row[SongProviderTable.songId].value,
                            row[SongProviderTable.rawUrl],
                            row[SongProviderTable.provider],
                            row[SongProviderTable.externalId]
                        )
                    }
            }
            val exactMatches = urlChunks.flatMap { urlChunk ->
                SongTable
                    .select(SongTable.id, SongTable.originalUrl)
                    .where { SongTable.originalUrl inList urlChunk }
                    .map { row -> row[SongTable.id].value to row[SongTable.originalUrl] }
            }

            val candidateIds = (exactMatches.map { it.first } + providerRows.map { it.entityId }).distinct()
            val insertedBySong = candidateIds.chunked(PROVIDER_LOOKUP_CHUNK_SIZE).flatMap { chunk ->
                SongTable
                    .select(SongTable.id, SongTable.inserted)
                    .where { SongTable.id inList chunk }
                    .map { row -> row[SongTable.id].value to row[SongTable.inserted] }
            }.toMap()

            val winners = resolveUrlWinners(
                urls = distinctUrls,
                lookups = parsedLookups,
                exactMatches = exactMatches,
                providerRows = providerRows,
                order = compareBy<UUID> { insertedBySong[it] }.then(uuidOrder),
            )
            val songsById = loadSongs<UserSong>(winners.values.filterNotNull().distinct(), userId, true).associateBy { it.id }

            winners.mapValuesTo(mutableMapOf()) { (_, id) -> id?.let { songsById[it] } }
        }
    }

    suspend fun enrichProviders(
        id: UUID,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ) {
        val song = byId(id) ?: return
        val urls = mutableSetOf<String>()
        var isrc: String? = null

        song.musicBrainzId?.let { mbId ->
            cachedMusicBrainzService.getRecording(mbId, priority)?.let { recording ->
                val mbUrls = (recording.relations ?: emptyList())
                    .mapNotNull { it.url?.resource }
                urls.addAll(mbUrls)
                isrc = recording.isrcs?.firstOrNull()
            }
        }

        val seedUrls = dbQuery {
            SongProviderTable.selectAll()
                .where { SongProviderTable.songId eq id }
                .mapNotNull { row ->
                    val provider = row[SongProviderTable.provider]
                    val externalId = row[SongProviderTable.externalId]
                    val typeValue = row[SongProviderTable.type]
                    val type = typeValue?.let { Type.fromValue(it) } ?: Type.SONG

                    ParserFactory.toUrl(provider, externalId, type) ?: row[SongProviderTable.rawUrl]
                }
        }.toMutableSet()

        seedUrls.addAll(urls)

        val resolvedLinks = linkResolverService.batchResolve(seedUrls, isrc = isrc, priority = priority)
        val allUrls = (urls + resolvedLinks).distinct()

        dbQuery {
            allUrls.forEach { url ->
                val parser = ParserFactory.getParser(url)
                val parsed = parser?.parse(url)
                val provider = parser?.name ?: "unknown"
                val externalId = parsed?.first ?: url

                SongProviderTable.upsert(
                    SongProviderTable.songId,
                    SongProviderTable.provider,
                    SongProviderTable.externalId
                ) {
                    it[SongProviderTable.songId] = id
                    it[SongProviderTable.provider] = provider
                    it[SongProviderTable.externalId] = externalId
                    it[SongProviderTable.type] = parsed?.second?.value ?: Type.SONG.value
                    it[SongProviderTable.rawUrl] = url
                }
            }

            SongTable.update({ SongTable.id eq id }) {
                it[lastProviderEnrichment] = Clock.System.now()
                    .toEpochMilliseconds() + (1.days..5.days).random().inWholeMilliseconds
            }
        }
    }

    fun insertVariants(kind: SongVariantKind, variants: List<Triple<UUID, String, AudioInfo?>>) {
        variants.forEach { (songId, path, given) ->
            val info = given ?: AudioProbe.probe(File(path))
            SongVariantTable.insertIgnore {
                it[SongVariantTable.songId] = songId
                it[SongVariantTable.kind] = kind
                it[SongVariantTable.path] = path
                it[codec] = info?.codec ?: ""
                it[sampleRate] = info?.sampleRate ?: 0
                it[bitsPerSample] = info?.bitsPerSample ?: 0
                it[channels] = info?.channels ?: 0
                it[bitRate] = info?.bitRate ?: 0
                it[fileSize] = info?.fileSize ?: 0
            }
        }
    }

    fun mapVariant(row: ResultRow) = AudioInfo(
        codec = row[SongVariantTable.codec],
        sampleRate = row[SongVariantTable.sampleRate],
        bitsPerSample = row[SongVariantTable.bitsPerSample],
        bitRate = row[SongVariantTable.bitRate],
        fileSize = row[SongVariantTable.fileSize],
        channels = row[SongVariantTable.channels],
    )

    suspend fun addProviderUrl(songId: UUID, url: String) = dbQuery {
        val parser = ParserFactory.getParser(url)
        val parsed = parser?.parse(url)

        val provider = parser?.name ?: "unknown"
        val externalId = parsed?.first ?: url

        SongProviderTable.upsert(
            SongProviderTable.songId,
            SongProviderTable.provider,
            SongProviderTable.externalId
        ) {
            it[SongProviderTable.songId] = songId
            it[SongProviderTable.provider] = provider
            it[SongProviderTable.externalId] = externalId
            it[SongProviderTable.type] = parsed?.second?.value ?: Type.SONG.value
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
                            (if (track.isrc?.isNotBlank() == true) SongTable.isrc eq track.isrc else Op.FALSE) or
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
        rankedSongSearch(page, pageSize, query, explicit, userId) {
            if (liked) andWhere { UserSongTable.isFavourite eq true }
            else this
        }

    suspend fun rankedSearchInCollection(
        collectionId: UUID,
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        rankedSongSearch(page, pageSize, query, explicit, userId) {
            andWhere {
                (SongTable.id inSubQuery CollectionSongTable
                    .select(CollectionSongTable.songId)
                    .where { CollectionSongTable.collectionId eq collectionId }
                ) or (SongTable.albumId inSubQuery CollectionAlbumTable
                    .select(CollectionAlbumTable.albumId)
                    .where { CollectionAlbumTable.collectionId eq collectionId }
                ) or (SongTable.id inSubQuery SongArtistTable
                    .innerJoin(CollectionArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { CollectionArtistTable.artistId })
                    .select(SongArtistTable.songId)
                    .where { CollectionArtistTable.collectionId eq collectionId }
                ) or (SongTable.albumId inSubQuery AlbumArtistTable
                    .innerJoin(CollectionArtistTable, onColumn = { AlbumArtistTable.artistId }, otherColumn = { CollectionArtistTable.artistId })
                    .select(AlbumArtistTable.albumId)
                    .where { CollectionArtistTable.collectionId eq collectionId }
                ) or (SongTable.id inSubQuery UserPlaylistSongTable
                    .innerJoin(CollectionPlaylistTable, onColumn = { UserPlaylistSongTable.playlistId }, otherColumn = { CollectionPlaylistTable.playlistId })
                    .select(UserPlaylistSongTable.songId)
                    .where { CollectionPlaylistTable.collectionId eq collectionId }
                )
            }
        }

    suspend fun rankedSearchInRadioChannel(
        channelId: UUID,
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        rankedSongSearch(page, pageSize, query, explicit, userId) {
            andWhere {
                (SongTable.id inSubQuery RadioChannelSongTable
                    .select(RadioChannelSongTable.songId)
                    .where { RadioChannelSongTable.channelId eq channelId }
                ) or (SongTable.albumId inSubQuery RadioChannelAlbumTable
                    .select(RadioChannelAlbumTable.albumId)
                    .where { RadioChannelAlbumTable.channelId eq channelId }
                ) or (SongTable.id inSubQuery SongArtistTable
                    .innerJoin(RadioChannelArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { RadioChannelArtistTable.artistId })
                    .select(SongArtistTable.songId)
                    .where { RadioChannelArtistTable.channelId eq channelId }
                ) or (SongTable.albumId inSubQuery AlbumArtistTable
                    .innerJoin(RadioChannelArtistTable, onColumn = { AlbumArtistTable.artistId }, otherColumn = { RadioChannelArtistTable.artistId })
                    .select(AlbumArtistTable.albumId)
                    .where { RadioChannelArtistTable.channelId eq channelId }
                )
            }
        }

    private suspend fun rankedSongSearch(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID,
        scope: Query.() -> Query = { this }
    ): PaginatedResponse<UserSong> =
        querySongsRanked(page, pageSize, explicit, userId, columnSet = {
            if (filtersBySearchVector()) this else songSearchColumns()
        }, sortColumnSet = {
            if (filtersBySearchVector()) songSearchColumns() else this
        }) {
            rankedSearchQuery(
                redisSearchService,
                query,
                listOf(20, 10, 5, 5, 5, 5, 3, 3, 3, 3, 5, 5, 3, 5, 5, 3, 8),
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
                    mbArtistSearchTable[MBArtistTable.disambiguation],
                    SongTable.titleTags
                ),
                SongTable.id,
                searchVectorColumn = if (searchIndexWorker != null) SongTable.searchVector else null
            ).let { it.copy(query = it.query.scope()) }
        }

    suspend fun searchByLyrics(
        page: Int,
        pageSize: Int,
        query: String,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongsRanked(page, pageSize, explicit, userId, columnSet = {
            leftJoin(
                SyncedLyricsTable,
                onColumn = { SongTable.id },
                otherColumn = { SyncedLyricsTable.songId })
        }) {
            rankedSearchQuery(
                redisSearchService,
                query,
                listOf(10, 8),
                listOf(
                    SyncedLyricsTable.rawLyrics,
                    SongTable.lyrics
                ),
                SongTable.id
            )
        }

    suspend fun likedSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, userId
        ) {
            where { UserSongTable.isFavourite eq true }
            orderBy(UserSongTable.updatedAt to SortOrder.DESC)
        }

    suspend fun superLikedSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID
    ): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, userId
        ) {
            where { UserSongTable.superLikedAt.isNotNull() }
            orderBy(UserSongTable.superLikedAt to SortOrder.DESC)
        }

    suspend fun exportFavouritesAsCsv(userId: UUID): String = dbQuery {
        fun csvEscape(value: String): String =
            if (value.contains(',') || value.contains('"') || value.contains('\n'))
                "\"${value.replace("\"", "\"\"")}\""
            else value

        val rows = SongTable
            .leftJoin(AlbumTable, onColumn = { SongTable.albumId }, otherColumn = { AlbumTable.id })
            .leftJoin(SongArtistTable)
            .leftJoin(ArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistTable.id })
            .leftJoin(SongMusicBrainzTable)
            .leftJoin(
                UserSongTable,
                onColumn = { SongTable.id },
                otherColumn = { UserSongTable.songId },
                additionalConstraint = { UserSongTable.userId eq userId }
            )
            .selectAll()
            .where { UserSongTable.isFavourite eq true }
            .toList()

        data class CsvRow(
            val title: String,
            val artists: MutableList<String> = mutableListOf(),
            val albumName: String?,
            val isrc: String?,
            val mbid: UUID?,
            val favouritedAt: Long
        )

        val songs = linkedMapOf<UUID, CsvRow>()
        for (row in rows) {
            val songId = row[SongTable.id].value
            val entry = songs.getOrPut(songId) {
                CsvRow(
                    title = row.fullSongTitle(),
                    albumName = row.getOrNull(AlbumTable.name),
                    isrc = row.getOrNull(SongTable.isrc),
                    mbid = row.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value,
                    favouritedAt = row[UserSongTable.updatedAt]
                )
            }
            row.getOrNull(ArtistTable.name)?.let { if (it !in entry.artists) entry.artists.add(it) }
        }

        buildString {
            appendLine("title,artists,album,isrc,mbid,favourited_at")
            for ((_, song) in songs) {
                append(csvEscape(song.title)); append(',')
                append(csvEscape(song.artists.joinToString("; "))); append(',')
                append(song.albumName?.let { csvEscape(it) } ?: ""); append(',')
                append(song.isrc ?: ""); append(',')
                append(song.mbid?.toString() ?: ""); append(',')
                append(song.favouritedAt).appendLine()
            }
        }
    }

    suspend fun allSongs(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID,
        tags: List<SongTag> = emptyList(),
        excludeTags: List<SongTag> = emptyList(),
        titleTags: List<TitleTagKind> = emptyList(),
        excludeTitleTags: List<TitleTagKind> = emptyList()
    ): PaginatedResponse<UserSong> =
        querySongs(
            page, pageSize, explicit, userId,
            query = {
                applyTags(tags, excludeTags)
                applyTitleTags(titleTags, excludeTitleTags)
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
        val match = ColorMatch(color, range)
        return querySongs(
            page, pageSize, explicit, userId,
            columnSet = { match.join(this, SongTable.cover) },
            query = { match.filterAndOrder(this) }
        )
    }

    suspend fun deleteSongs(ids: List<UUID>): Boolean {
        val deletion = dbQuery { libraryFileDeleter.deleteSongRows(ids) }
        return deletion.deletedSongs == ids.size
    }

    fun formatOf(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return LosslessFormat.fromExtension(ext)?.extension ?: ext.take(8)
    }

    suspend fun transcodeAndRecord(
        id: UUID,
        file: File,
        quality: Int,
        force: Boolean,
        format: AudioFormat,
    ): StreamInfo = transcoder.transcodeAudio(environment, file, quality, force, format).also {
        transcodedSongRepository.insertTranscodedSong(id, it.file, quality, format)
    }

    suspend fun resolveRawStream(song: Song, client: ClientInfo): StreamInfo? {
        val file = File(song.path)
        if (!file.exists()) return null
        val format = file.losslessFormat
        if ((format == LosslessFormat.WAV || format == LosslessFormat.AIFF) && !client.supports(ClientFeature.LOSSLESS_WAV_AIFF)) {
            return transcoder.losslessFlacFallback(environment, file)
        }
        return StreamInfo(file, contentTypeFor(file, songContentTypes), file.length(), file.name)
    }

    fun streamSong(id: UUID, offset: Long, chunkSize: Int = 4096, client: ClientInfo = ClientInfo.LEGACY): Flow<ByteArray>? {
        val song = runBlocking { byId(id) } ?: return null
        if (!File(song.path).exists()) return null

        return flow {
            val file = resolveRawStream(song, client)?.file ?: return@flow
            emitAll(file.chunkFlow(offset, chunkSize))
        }.flowOn(Dispatchers.IO)
    }

    fun downloadSong(
        id: UUID,
        quality: Int,
        offset: Long = 0,
        chunkSize: Int = 4096,
        force: Boolean = true,
        format: AudioFormat = AudioFormat.OPUS,
        client: ClientInfo = ClientInfo.LEGACY,
    ): Flow<ByteArray>? {
        val song = runBlocking { byId(id) } ?: return null
        val file = File(song.path)
        if (!file.exists()) return null

        return flow {
            val streamInfo =
                if (quality <= 0) resolveRawStream(song, client) ?: return@flow
                else transcodeAndRecord(id, file, quality, force, format)

            try {
                emitAll(streamInfo.file.chunkFlow(offset, chunkSize))
            } catch (e: IOException) {
                if (streamInfo.file.exists()) {
                    streamInfo.file.delete()
                }
                throw e
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun getStreamSize(id: UUID, client: ClientInfo = ClientInfo.LEGACY): Long {
        val song = byId(id) ?: return 0
        return resolveRawStream(song, client)?.contentLength ?: 0
    }

    fun resolveAtmosStream(song: Song, client: ClientInfo): StreamInfo? {
        if (!client.supports(ClientFeature.DOLBY_ATMOS)) return null
        val file = song.atmosVariantPath?.let(::File) ?: return null
        if (!file.exists()) return null
        return StreamInfo(file, ContentType.Audio.MP4, file.length(), file.name)
    }

    fun streamSongAtmos(id: UUID, offset: Long, chunkSize: Int = 4096, client: ClientInfo = ClientInfo.LEGACY): Flow<ByteArray>? {
        val song = runBlocking { byId(id) } ?: return null
        val file = resolveAtmosStream(song, client)?.file ?: return null

        return file.chunkFlow(offset, chunkSize).flowOn(Dispatchers.IO)
    }

    suspend fun getAtmosStreamSize(id: UUID, client: ClientInfo = ClientInfo.LEGACY): Long {
        val song = byId(id) ?: return 0
        return resolveAtmosStream(song, client)?.contentLength ?: 0
    }

    suspend fun getDownloadSize(
        id: UUID,
        quality: Int,
        force: Boolean = true,
        format: AudioFormat = AudioFormat.OPUS,
        client: ClientInfo = ClientInfo.LEGACY,
    ): Long {
        val song = byId(id) ?: return 0
        val file = File(song.path)
        if (!file.exists()) return 0
        if (quality <= 0) return resolveRawStream(song, client)?.contentLength ?: 0
        val streamInfo = transcodeAndRecord(id, file, quality, force, format)
        return streamInfo.file.length()
    }

    fun allSongIds(
        explicit: Boolean,
        tags: List<SongTag> = emptyList(),
        excludeTags: List<SongTag> = emptyList(),
        titleTags: List<TitleTagKind> = emptyList(),
        excludeTitleTags: List<TitleTagKind> = emptyList()
    ): Flow<UUID> = flow {
        SongTable
            .leftJoin(SongMusicBrainzTable)
            .select(SongTable.id)
            .let {
                if (!explicit) it.where { SongTable.explicit eq false }
                else it
            }
            .applyTags(tags, excludeTags)
            .applyTitleTags(titleTags, excludeTitleTags)
            .orderBy(SongTable.inserted, SortOrder.DESC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    private fun Query.applyTags(tags: List<SongTag>, excludeTags: List<SongTag>): Query {
        anyTagCondition(tags)?.let { condition -> andWhere { condition } }
        anyTagCondition(excludeTags)?.let { condition -> andWhere { not(condition) } }
        return this
    }

    private fun anyTagCondition(tags: List<SongTag>): Op<Boolean>? {
        if (tags.isEmpty()) return null
        val customAudioPath = get<StorageService>().customAudioPath

        return tags.distinct().map { tag ->
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
        }.reduce { acc, op -> acc or op }
    }

    private fun Query.applyTitleTags(titleTags: List<TitleTagKind>, excludeTitleTags: List<TitleTagKind>): Query {
        if (titleTags.isNotEmpty()) andWhere { SongTable.id inSubQuery songIdsWithTitleTags(titleTags) }
        if (excludeTitleTags.isNotEmpty()) andWhere { SongTable.id notInSubQuery songIdsWithTitleTags(excludeTitleTags) }
        return this
    }

    private fun songIdsWithTitleTags(kinds: List<TitleTagKind>): Query =
        SongTitleTagTable
            .select(SongTitleTagTable.songId)
            .where { SongTitleTagTable.kind inList kinds.distinct() }

    fun likedSongIds(explicit: Boolean, userId: UUID): Flow<UUID> = flow {
        SongTable
            .leftJoin(
                UserSongTable,
                onColumn = { SongTable.id },
                otherColumn = { UserSongTable.songId })
            .select(SongTable.id)
            .where { UserSongTable.userId eq userId }
            .andWhere { UserSongTable.isFavourite eq true }
            .let {
                if (!explicit) it.andWhere { SongTable.explicit eq false }
                else it
            }
            .orderBy(UserSongTable.updatedAt, SortOrder.DESC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    fun superLikedSongIds(explicit: Boolean, userId: UUID): Flow<UUID> = flow {
        SongTable
            .leftJoin(
                UserSongTable,
                onColumn = { SongTable.id },
                otherColumn = { UserSongTable.songId })
            .select(SongTable.id)
            .where { UserSongTable.userId eq userId }
            .andWhere { UserSongTable.superLikedAt.isNotNull() }
            .let {
                if (!explicit) it.andWhere { SongTable.explicit eq false }
                else it
            }
            .orderBy(UserSongTable.superLikedAt, SortOrder.DESC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    fun songIdsByArtist(artistId: UUID): Flow<UUID> = flow {
        val artistSongIds = SongArtistTable
            .select(SongArtistTable.songId)
            .where { SongArtistTable.artistId eq artistId }
        val artistAlbumIds = AlbumArtistTable
            .select(AlbumArtistTable.albumId)
            .where { AlbumArtistTable.artistId eq artistId }

        SongTable
            .select(SongTable.id)
            .where { (SongTable.id inSubQuery artistSongIds) or (SongTable.albumId inSubQuery artistAlbumIds) }
            .orderBy(SongTable.releaseDate, SortOrder.DESC)
            .orderBy(SongTable.trackNumber, SortOrder.ASC)
            .orderBy(SongTable.id, SortOrder.ASC)
            .fetchBatchedResults(1000) { batch ->
                batch.forEach {
                    emit(it[SongTable.id].value)
                }
            }
    }

    fun songIdsByAlbum(albumId: UUID): Flow<UUID> = flow {
        SongTable
            .select(SongTable.id)
            .where { SongTable.albumId eq albumId }
            .orderBy(SongTable.discNumber, SortOrder.ASC)
            .orderBy(SongTable.trackNumber, SortOrder.ASC)
            .orderBy(SongTable.id, SortOrder.ASC)
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
            .orderBy(SongTable.id, SortOrder.ASC)
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
    ) = querySongsRanked<T>(page, pageSize, explicit, userId, columnSet) { RankedSearch(query()) }

    private suspend inline fun <reified T : BaseSong> querySongsRanked(
        page: Int,
        pageSize: Int,
        explicit: Boolean,
        userId: UUID? = null,
        crossinline columnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline sortColumnSet: ColumnSet.() -> ColumnSet = { this },
        crossinline search: Query.() -> RankedSearch
    ) = dbQuery {
        val filtered = SongTable
            .leftJoin(
                SongMusicBrainzTable,
                onColumn = { SongTable.id },
                otherColumn = { SongMusicBrainzTable.songId }
            )
            .userSong(userId)
            .columnSet()

        val ranked = filtered.sortColumnSet().selectAll().search()
        val q = ranked.query
        val paged = pageSize != Int.MAX_VALUE

        if (paged && ranked.redisTotal == null) {
            val anyMatch = Query(Slice(filtered, listOf(SongTable.id)), q.where)
                .apply { if (!explicit) andWhere { SongTable.explicit eq false } }
                .limit(1)
                .any()
            if (!anyMatch) return@dbQuery PaginatedResponse(
                data = listOf(),
                total = 0,
                page = page,
                pageSize = pageSize,
            )
        }

        val sortValues = q.orderByExpressions.mapIndexed { index, (expression, order) ->
            val value = when {
                expression is Column<*> && expression.table == SongTable -> expression
                expression is ExpressionWithColumnType<*> -> expression.sortAggregate(order in descendingOrders)
                else -> expression
            }
            value.alias("sort_$index") to order
        }
        val kept = rankedSongs(q, q.set.source, sortValues, explicit)
        val keptTotal = kept.id.count().over()
        val idQuery = kept.set.select(kept.id, keptTotal).where { kept.isKept }
        kept.sorts.forEach { (sort, order) -> idQuery.orderBy(sort, order) }
        idQuery.orderBy(kept.id, SortOrder.ASC)

        if (paged) {
            idQuery.limit(pageSize)
            idQuery.offset((page * pageSize).toLong())
        }

        val rows = idQuery.toList()
        val ids = rows.map { it[kept.id].value }
        val total = ranked.redisTotal ?: when {
            !paged -> ids.size.toLong()
            rows.isNotEmpty() -> rows.first()[keptTotal]
            page == 0 -> 0L
            else -> {
                val counted = rankedSongs(q, filtered, emptyList(), explicit)
                val countExpression = counted.id.count()
                counted.set.select(countExpression).where { counted.isKept }.first()[countExpression]
            }
        }

        if (ids.isEmpty()) return@dbQuery PaginatedResponse(
            data = listOf(),
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
        )

        idOrderedPage(ids, loadSongs<T>(ids, userId, explicit), total, page, pageSize) { it.id }
    }

    private class RankedSongs(
        val set: QueryAlias,
        val id: Column<EntityID<UUID>>,
        val isKept: Op<Boolean>,
        val sorts: List<Pair<Expression<*>, SortOrder>>,
    )

    private fun rankedSongs(
        query: Query,
        source: ColumnSet,
        sortValues: List<Pair<ExpressionAlias<*>, SortOrder>>,
        explicit: Boolean,
    ): RankedSongs {
        val withAlbum = if (AlbumTable.id in source.columns) source else source.withAlbum()
        val duplicateRank = rowNumber().over()
            .partitionBy(
                duplicateSongTitleKey(SongTable.title),
                SongTable.titleTags,
                isoDateKey(SongTable.releaseDate),
                SongTable.duration,
                SongTable.trackNumber,
                SongTable.discNumber,
                AlbumTable.name,
            )
            .let { window -> if (explicit) window.orderBy(SongTable.explicit, SortOrder.DESC) else window }
            .orderBy(SongTable.inserted to SortOrder.ASC, SongTable.id to SortOrder.ASC)
            .alias("duplicate_rank")

        val matched = Query(Slice(withAlbum, listOf(SongTable.id, duplicateRank) + sortValues.map { it.first }), query.where)
            .apply { if (!explicit) andWhere { SongTable.explicit eq false } }
            .groupBy(SongTable.id, AlbumTable.id)
        query.having?.let { having -> matched.having { having } }
        val songs = matched.alias("ranked")

        return RankedSongs(
            set = songs,
            id = songs[SongTable.id],
            isKept = songs[duplicateRank] eq longLiteral(1),
            sorts = sortValues.map { (value, order) -> songs[value] to order },
        )
    }

    private fun ColumnSet.withAlbum(): ColumnSet =
        leftJoin(AlbumTable, onColumn = { SongTable.albumId }, otherColumn = { AlbumTable.id })

    private fun Query.orderByReleaseThenAlbum(): Query =
        orderBy(SongTable.releaseDate, SortOrder.DESC)
            .orderBy(utf8SortKey(AlbumTable.name), SortOrder.ASC)
            .orderBy(SongTable.albumId, SortOrder.ASC)
            .orderBy(SongTable.discNumber, SortOrder.ASC)
            .orderBy(SongTable.trackNumber, SortOrder.ASC)

    private fun filtersBySearchVector(): Boolean = searchIndexWorker != null && Dialect.current() == Dialect.POSTGRES

    private fun ColumnSet.songSearchColumns(): ColumnSet = songSearchJoins()
        .withMBRecordingSearch()
        .withMBReleaseSearch()
        .withMBArtistSearch()

    private fun ColumnSet.songSearchJoins(): ColumnSet = withAlbum()
        .leftJoin(AlbumMusicBrainzTable, onColumn = { AlbumTable.id }, otherColumn = { AlbumMusicBrainzTable.albumId })
        .leftJoin(SongArtistTable, onColumn = { SongTable.id }, otherColumn = { SongArtistTable.songId })
        .leftJoin(ArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistTable.id })
        .leftJoin(ArtistMusicBrainzTable, onColumn = { ArtistTable.id }, otherColumn = { ArtistMusicBrainzTable.artistId })
        .leftJoin(artistGroupJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistGroupJoinAlias[ArtistMemberTable.artistId] })
        .leftJoin(artistGroupAlias, onColumn = { artistGroupJoinAlias[ArtistMemberTable.groupId] }, otherColumn = { artistGroupAlias[ArtistTable.id] })
        .leftJoin(artistMemberJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistMemberJoinAlias[ArtistMemberTable.groupId] })
        .leftJoin(artistMemberAlias, onColumn = { artistMemberJoinAlias[ArtistMemberTable.artistId] }, otherColumn = { artistMemberAlias[ArtistTable.id] })
        .leftJoin(ArtistAliasTable, onColumn = { ArtistTable.id }, otherColumn = { ArtistAliasTable.artistId })
        .leftJoin(AlbumArtistTable, onColumn = { AlbumTable.id }, otherColumn = { AlbumArtistTable.albumId })
        .leftJoin(albumArtistAlias, onColumn = { AlbumArtistTable.artistId }, otherColumn = { albumArtistAlias[ArtistTable.id] })
        .leftJoin(albumArtistAliasAlias, onColumn = { AlbumArtistTable.artistId }, otherColumn = { albumArtistAliasAlias[ArtistAliasTable.artistId] })
        .leftJoin(albumArtistGroupJoinAlias, onColumn = { albumArtistAlias[ArtistTable.id] }, otherColumn = { albumArtistGroupJoinAlias[ArtistMemberTable.artistId] })
        .leftJoin(albumArtistGroupAlias, onColumn = { albumArtistGroupJoinAlias[ArtistMemberTable.groupId] }, otherColumn = { albumArtistGroupAlias[ArtistTable.id] })
        .leftJoin(albumArtistMemberJoinAlias, onColumn = { albumArtistAlias[ArtistTable.id] }, otherColumn = { albumArtistMemberJoinAlias[ArtistMemberTable.groupId] })
        .leftJoin(albumArtistMemberAlias, onColumn = { albumArtistMemberJoinAlias[ArtistMemberTable.artistId] }, otherColumn = { albumArtistMemberAlias[ArtistTable.id] })

    private suspend inline fun <reified T : BaseSong> loadSongs(
        ids: List<UUID>,
        userId: UUID?,
        explicit: Boolean,
    ): List<T> {
        val songIdChunks = ids.chunked(SONG_DETAIL_CHUNK_SIZE)

        val songRows = songIdChunks.flatMap { chunk ->
            SongTable
                .leftJoin(AlbumTable, onColumn = { SongTable.albumId }, otherColumn = { AlbumTable.id })
                .leftJoin(AlbumMusicBrainzTable, onColumn = { AlbumTable.id }, otherColumn = { AlbumMusicBrainzTable.albumId })
                .leftJoin(songImageAlias, onColumn = { SongTable.cover }, otherColumn = { songImageAlias[ImageTable.id] })
                .leftJoin(albumImageAlias, onColumn = { AlbumTable.cover }, otherColumn = { albumImageAlias[ImageTable.id] })
                .leftJoin(songAnimatedImageAlias, onColumn = { SongTable.animatedCover }, otherColumn = { songAnimatedImageAlias[AnimatedImageTable.id] })
                .leftJoin(songAnimatedFrameAlias, onColumn = { songAnimatedImageAlias[AnimatedImageTable.imageId] }, otherColumn = { songAnimatedFrameAlias[ImageTable.id] })
                .leftJoin(albumAnimatedImageAlias, onColumn = { AlbumTable.animatedCover }, otherColumn = { albumAnimatedImageAlias[AnimatedImageTable.id] })
                .leftJoin(albumAnimatedFrameAlias, onColumn = { albumAnimatedImageAlias[AnimatedImageTable.imageId] }, otherColumn = { albumAnimatedFrameAlias[ImageTable.id] })
                .leftJoin(SongMusicBrainzTable, onColumn = { SongTable.id }, otherColumn = { SongMusicBrainzTable.songId })
                .userSong(userId)
                .selectAll()
                .where { SongTable.id inList chunk }
                .toList()
        }

        val artistsBySong = songIdChunks.flatMap { chunk ->
            SongArtistTable
                .innerJoin(ArtistTable, onColumn = { SongArtistTable.artistId }, otherColumn = { ArtistTable.id })
                .leftJoin(ArtistMusicBrainzTable, onColumn = { ArtistTable.id }, otherColumn = { ArtistMusicBrainzTable.artistId })
                .leftJoin(songCreditedAliasAlias, onColumn = { SongArtistTable.creditedAliasId }, otherColumn = { songCreditedAliasAlias[ArtistAliasTable.id] })
                .leftJoin(artistImageAlias, onColumn = { ArtistTable.image }, otherColumn = { artistImageAlias[ImageTable.id] })
                .followedArtist(userId)
                .selectAll()
                .where { SongArtistTable.songId inList chunk }
                .toList()
        }.groupBy { it[SongArtistTable.songId].value }.mapValues { (_, rows) ->
            val positions = rows.associate { it[ArtistTable.id].value to it[SongArtistTable.position] }
            rows.map { row ->
                mapArtist(
                    row,
                    ArtistTable,
                    followedTable = followedArtistAlias,
                    blurHashColumn = artistImageAlias[ImageTable.blurHash]
                ).copy(
                    creditedName = row.getOrNull(songCreditedAliasAlias[ArtistAliasTable.name]),
                    joinPhrase = row[SongArtistTable.joinPhrase],
                )
            }.inCreditOrder { positions.getValue(it.id) }
        }

        val albumIds = songRows.map { it[SongTable.albumId].value }.distinct()

        val albumArtistsByAlbum = albumIds.chunked(SONG_DETAIL_CHUNK_SIZE).flatMap { chunk ->
            AlbumArtistTable
                .innerJoin(albumArtistAlias, onColumn = { AlbumArtistTable.artistId }, otherColumn = { albumArtistAlias[ArtistTable.id] })
                .leftJoin(albumArtistMusicBrainzAlias, onColumn = { albumArtistAlias[ArtistTable.id] }, otherColumn = { albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.artistId] })
                .leftJoin(albumCreditedAliasAlias, onColumn = { AlbumArtistTable.creditedAliasId }, otherColumn = { albumCreditedAliasAlias[ArtistAliasTable.id] })
                .leftJoin(albumArtistImageAlias, onColumn = { albumArtistAlias[ArtistTable.image] }, otherColumn = { albumArtistImageAlias[ImageTable.id] })
                .followedArtist(userId, albumFollowedArtistAlias, albumArtistAlias[ArtistTable.id])
                .selectAll()
                .where { AlbumArtistTable.albumId inList chunk }
                .toList()
        }.groupBy { it[AlbumArtistTable.albumId].value }.mapValues { (_, rows) ->
            val positions = rows.associate { it[albumArtistAlias[ArtistTable.id]].value to it[AlbumArtistTable.position] }
            rows.map { row ->
                mapArtist(
                    row,
                    albumArtistAlias,
                    row.getOrNull(albumArtistMusicBrainzAlias[ArtistMusicBrainzTable.musicBrainzId])?.value,
                    followedTable = albumFollowedArtistAlias,
                    blurHashColumn = albumArtistImageAlias[ImageTable.blurHash]
                ).copy(
                    creditedName = row.getOrNull(albumCreditedAliasAlias[ArtistAliasTable.name]),
                    joinPhrase = row[AlbumArtistTable.joinPhrase],
                )
            }.inCreditOrder { positions.getValue(it.id) }
        }

        val genresBySong = songIdChunks.flatMap { chunk ->
            SongGenreTable
                .innerJoin(GenreTable, onColumn = { SongGenreTable.genreId }, otherColumn = { GenreTable.id })
                .select(SongGenreTable.songId, GenreTable.id, GenreTable.name)
                .where { SongGenreTable.songId inList chunk }
                .toList()
        }.groupBy({ it[SongGenreTable.songId].value }) { row ->
            Genre(row[GenreTable.id].value, row[GenreTable.name])
        }.mapValues { (_, genres) -> genres.inNameOrder() }

        val providersBySong = songIdChunks.flatMap { chunk ->
            SongProviderTable
                .select(SongProviderTable.songId, SongProviderTable.provider, SongProviderTable.externalId, SongProviderTable.rawUrl)
                .where { SongProviderTable.songId inList chunk }
                .toList()
        }.groupBy({ it[SongProviderTable.songId].value }) { row ->
            Triple(row[SongProviderTable.provider], row[SongProviderTable.externalId], row[SongProviderTable.rawUrl])
        }

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val loadedIds = songRows.map { it[SongTable.id].value }
        val insertedBySong = songRows.associate { it[SongTable.id].value to it[SongTable.inserted] }

        val variants = loadedIds.chunked(1000).flatMap { chunk ->
            SongVariantTable
                .selectAll()
                .where { SongVariantTable.songId inList chunk }
                .andWhere { SongVariantTable.kind eq SongVariantKind.ATMOS }
                .toList()
        }.associateBy { it[SongVariantTable.songId].value }

        val playbackTags = if (T::class == UserSong::class && userId != null) {
            TimecodeTagService.playbackTags(userId, loadedIds)
        } else {
            emptyMap()
        }

        return songRows.map { row ->
            val songId = row[SongTable.id].value
            val genres = genresBySong[songId].orEmpty()
            val album = mapAlbum(
                row,
                blurHashColumn = albumImageAlias[ImageTable.blurHash],
                animatedCoverImageIdColumn = albumAnimatedImageAlias[AnimatedImageTable.imageId],
                animatedCoverBlurHashColumn = albumAnimatedFrameAlias[ImageTable.blurHash],
            ).let { album ->
                album.copy(
                    artists = albumArtistsByAlbum[album.id].orEmpty(),
                    totalDuration = statsByAlbumId[album.id]?.first ?: -1L,
                    totalSize = statsByAlbumId[album.id]?.second ?: -1L
                )
            }
            val variant = variants[songId]
            val song = map<T>(
                row, genres, songImageAlias[ImageTable.blurHash],
                animatedCoverImageIdColumn = songAnimatedImageAlias[AnimatedImageTable.imageId],
                animatedCoverBlurHashColumn = songAnimatedFrameAlias[ImageTable.blurHash],
            )
            val providers = providersBySong[songId].orEmpty()
            val originalUrl = providers.firstOrNull { it.third == song.originalUrl }?.third
                ?: providers.minWithOrNull(compareBy<Triple<String, String, String>> { it.first }.thenBy { it.second })?.third
                ?: song.originalUrl
            val artists = artistsBySong[songId].orEmpty()

            when (song) {
                is Song -> song.copy(
                    album = album,
                    artists = artists,
                    genres = genres,
                    originalUrl = originalUrl,
                    atmos = variant?.let(::mapVariant),
                    atmosVariantPath = variant?.get(SongVariantTable.path),
                ) as T

                is UserSong -> song.copy(
                    album = album,
                    artists = artists,
                    genres = genres,
                    originalUrl = originalUrl,
                    atmos = variant?.let(::mapVariant),
                    atmosVariantPath = variant?.get(SongVariantTable.path),
                    playbackTags = playbackTags[songId] ?: emptyList(),
                ) as T

                else -> throw Exception("Unknown song type: $song")
            }
        }.groupBy {
            listOf(
                duplicateSongTitle(it.title),
                it.tags,
                it.releaseDate,
                it.duration,
                it.trackNumber,
                it.discNumber,
                it.album?.name
            )
        }.mapNotNull { (_, candidates) ->
            val songList = candidates.sortedWith(compareBy<T> { insertedBySong[it.id] }.thenBy(uuidOrder) { it.id })
            if (explicit) songList.find { it.explicit } ?: songList.first()
            else songList.find { !it.explicit }
        }
    }

    private data class ExistingSong(
        val id: UUID,
        val title: String,
        val explicit: Boolean,
        val tags: List<TitleTag>
    )

    private suspend fun bulkFindExistingSongs(songs: List<InsertableSong>): Map<InsertableSong, ExistingSong> =
        songs.chunked(PROVIDER_LOOKUP_CHUNK_SIZE)
            .flatMap { chunk -> bulkFindExistingSongChunk(chunk).entries }
            .associate { it.key to it.value }

    private suspend fun bulkFindExistingSongChunk(songs: List<InsertableSong>): Map<InsertableSong, ExistingSong> =
        dbQuery {
            val parsedLookups = songs.mapNotNull { song ->
                if (song.originalUrl.isBlank()) return@mapNotNull null
                providerLookup(song.originalUrl)?.let { song.originalUrl to it }
            }.toMap()

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
                    SongTable.titleTags,
                    SongTable.trackNumber,
                    SongTable.discNumber,
                    SongTable.explicit,
                    SongTable.filePath,
                    SongTable.originalUrl,
                    SongTable.isrc,
                    AlbumTable.name,
                    SongProviderTable.rawUrl,
                    SongProviderTable.provider,
                    SongProviderTable.externalId
                )
                .withDistinct()
                .where { SongTable.filePath inList songs.map { it.path } }
                .orWhere {
                    val urls = songs.map { it.originalUrl }.filter { it.isNotBlank() }
                    val isrcs = songs.mapNotNull { it.isrc }
                        .filter { it.isNotBlank() && it.length >= 10 && it.uppercase() != "ISRC" }

                    (SongTable.originalUrl inList urls) or
                            (SongProviderTable.rawUrl inList urls) or
                            (if (isrcs.isNotEmpty()) SongTable.isrc inList isrcs else Op.FALSE) or
                            SongProviderTable.matchesAny(parsedLookups.values)
                }
                .orWhere {
                    (SongTable.title inList songs.map { it.title }) and
                            (SongTable.trackNumber inList songs.map { it.trackNumber }) and
                            (SongTable.discNumber inList songs.map { it.discNumber })
                }
                .toList()

            val existingSongMap = mutableMapOf<InsertableSong, ExistingSong>()

            for (song in songs) {
                rows.firstOrNull { row ->
                    val albumName = row[AlbumTable.name]
                    val songId = row[SongTable.id].value
                    val dbFilePath = row[SongTable.filePath]

                    val pathMatch = dbFilePath == song.path

                    val lookup = parsedLookups[song.originalUrl]
                    val rowRawUrl = row.getOrNull(SongProviderTable.rawUrl)
                    val rowProvider = row.getOrNull(SongProviderTable.provider)
                    val rowExternalId = row.getOrNull(SongProviderTable.externalId)

                    val providerMatch = song.originalUrl.isNotBlank() && (
                            rowRawUrl == song.originalUrl || (
                                    lookup != null && rowProvider == lookup.provider && rowExternalId == lookup.externalId
                                    )
                            )

                    val legacyMatch =
                        song.originalUrl.isNotBlank() && row[SongTable.originalUrl] == song.originalUrl
                    val isrcMatch =
                        song.isrc?.isNotBlank() == true && song.isrc!!.length >= 10 && song.isrc!!.uppercase() != "ISRC" && row[SongTable.isrc] == song.isrc && albumName == song.album.name

                    val metadataMatch = legacyMatch || providerMatch || isrcMatch || (
                            song.originalUrl.isBlank() &&
                                    row[SongTable.title] == song.title &&
                                    row.titleTags() == song.tags &&
                                    row[SongTable.trackNumber] == song.trackNumber &&
                                    row[SongTable.discNumber] == song.discNumber &&
                                    row[SongTable.explicit] == song.explicit &&
                                    albumName == song.album.name
                            )

                    if (pathMatch || metadataMatch) {
                        existingSongMap[song] = ExistingSong(
                            id = songId,
                            title = row[SongTable.title],
                            explicit = row[SongTable.explicit],
                            tags = row.titleTags()
                        )
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

            val songs = songs.map { it.withSplitTitleTags() }

            val debug = songs.size >= 5000
            val overallStart = System.currentTimeMillis()
            var lastTime = overallStart

            fun logBlock(name: String) {
                if (!debug) return
                val now = System.currentTimeMillis()
                logger.info("Batch indexing ($name) took ${(now - lastTime).milliseconds}")
                lastTime = now
            }

            val artistService = get<ArtistService>()
            val albumService = get<AlbumService>()
            val imageService = get<ImageService>()

            val uniqueArtistNames = songs.flatMap { it.artists }.distinct()
            val uniqueAlbums = songs.map { it.album }.distinct()
            val uniqueCoverHashes = songs.map { it.coverHash }.distinct()
            logBlock("Metadata aggregation")

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
            logBlock("Artist creation")

            val albumIdMap: Map<InsertableAlbum, UUID> = uniqueAlbums
                .groupBy { AlbumService.identityKey(it) }
                .values
                .chunked(maxBatchSize)
                .map { groups ->
                    async {
                        albumService.getOrBulkCreate(groups.flatten()).entries
                    }
                }
                .awaitAll()
                .flatten()
                .toMap()
            logBlock("Album creation")

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
            logBlock("Image metadata fetch")

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
            logBlock("Existing song lookup")

            val dirtySongs = existingSongMap.filter { (song, existing) ->
                existing.title != song.title || existing.explicit != song.explicit || existing.tags != song.tags
            }

            if (dirtySongs.isNotEmpty()) {
                dbQuery {
                    dirtySongs.forEach { (song, existing) ->
                        SongTable.update({ SongTable.id eq existing.id }) {
                            it[title] = song.title
                            it[explicit] = song.explicit
                            it[titleTags] = encodeTitleTags(song.tags)
                        }
                    }
                    syncSongTitleTags(dirtySongs.map { (song, existing) -> existing.id to song.tags })
                }
                logBlock("Dirty song updates")
            }

            val atmosSongs = existingSongMap.filter { (song, _) -> song.atmosPath != null }
            if (atmosSongs.isNotEmpty()) {
                val variants = atmosSongs.map { (song, existing) -> Triple(existing.id, song.atmosPath!!, song.atmos) }
                dbQuery { insertVariants(SongVariantKind.ATMOS, variants) }
                logBlock("Atmos variant updates")
            }

            val newSongs = songs.filter { it !in existingSongMap.keys }

            if (newSongs.isEmpty()) {
                if (debug) logger.info("No new songs to insert, total time: ${(System.currentTimeMillis() - overallStart).milliseconds}")
                return@coroutineScope emptyMap()
            }

            val uniqueSongs = newSongs
                .groupBy { song ->
                    listOf(
                        song.title,
                        song.tags,
                        song.album.name,
                        song.album.originalId,
                        song.trackNumber,
                        song.discNumber,
                        song.duration,
                        song.explicit,
                    )
                }
                .map { (_, songs) ->
                    songs.maxByOrNull { it.audio.bitRate }
                }
                .filterNotNull()

            val filteredSongs = uniqueSongs.filter {
                if (albumIdMap[it.album] == null) logger.info("${it.title} (${it.album.name}) has no album.")
                albumIdMap[it.album] != null
            }
            logBlock("Uniqueness filtering")

            val songInsertResult: List<ResultRow> = dbQuery {
                SongTable.batchInsert(filteredSongs) { song ->
                    val albumId = albumIdMap[song.album]
                    val imageId = song.coverHash?.let { imageIdMap[it] }

                    this[SongTable.title] = song.title
                    this[SongTable.titleTags] = encodeTitleTags(song.tags)
                    this[SongTable.albumId] = albumId!!
                    this[SongTable.duration] = song.duration
                    this[SongTable.explicit] = song.explicit
                    this[SongTable.releaseDate] = getISOFromDate(song.releaseDate)
                    this[SongTable.lyrics] = song.lyrics
                    this[SongTable.filePath] = song.path
                    this[SongTable.format] = formatOf(song.path)
                    this[SongTable.originalUrl] = song.originalUrl
                    this[SongTable.trackNumber] = song.trackNumber
                    this[SongTable.discNumber] = song.discNumber
                    this[SongTable.copyright] = song.copyright
                    this[SongTable.sampleRate] = song.audio.sampleRate
                    this[SongTable.bitsPerSample] = song.audio.bitsPerSample
                    this[SongTable.bitRate] = song.audio.bitRate
                    this[SongTable.fileSize] = song.audio.fileSize
                    this[SongTable.channels] = song.audio.channels
                    this[SongTable.cover] = imageId
                    this[SongTable.isrc] = song.isrc
                }.also { rows ->
                    syncSongTitleTags(rows.mapIndexed { index, row -> row[SongTable.id].value to filteredSongs[index].tags })
                }
            }
            logBlock("Main song insertion")

            val insertedSongs: List<Pair<UUID, InsertableSong>> =
                songInsertResult.map {
                    it[SongTable.id].value to filteredSongs[songInsertResult.indexOf(
                        it
                    )]
                }

            val atmosVariants = insertedSongs.mapNotNull { (songId, songData) ->
                songData.atmosPath?.let { Triple(songId, it, songData.atmos) }
            }
            if (atmosVariants.isNotEmpty()) {
                dbQuery { insertVariants(SongVariantKind.ATMOS, atmosVariants) }
                logBlock("Atmos variant insertion")
            }

            val musicBrainzBatch = insertedSongs.mapNotNull { (songId, songData) ->
                songData.musicBrainzId?.let { mbId -> songId to mbId }
            }

            if (musicBrainzBatch.isNotEmpty()) {
                val uniqueMbIds = musicBrainzBatch.map { it.second }.distinct()
                uniqueMbIds.forEach { mbId ->
                    cachedMusicBrainzService.getRecording(mbId, HttpClientPriority.NORMAL)
                }

                dbQuery {
                    SongMusicBrainzTable.batchInsert(musicBrainzBatch) { (songId, mbId) ->
                        this[SongMusicBrainzTable.songId] = songId
                        this[SongMusicBrainzTable.musicBrainzId] = mbId
                    }
                }
                logBlock("MusicBrainz data")
            }

            insertedSongs.forEach { (songId, songData) ->
                if (songData.originalUrl.isNotBlank()) {
                    addProviderUrl(songId, songData.originalUrl)
                }
            }
            logBlock("Provider links")

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
                logBlock("Audio data insertion")
            }

            val songArtistLinks = insertedSongs.flatMap { (songId, songData) ->
                songData.artists
                    .flatMap { artistName -> artistIdMap[artistName] ?: emptyList() }
                    .distinct()
                    .mapIndexed { index, artistId -> Triple(songId, artistId, index) }
            }

            dbQuery {
                SongArtistTable.batchInsert(songArtistLinks) { (songId, artistId, index) ->
                    this[SongArtistTable.songId] = songId
                    this[SongArtistTable.artistId] = artistId
                    this[SongArtistTable.position] = index
                }
            }
            if (musicBrainzBatch.isNotEmpty()) {
                dbQuery { applyCachedSongCreditOrder(musicBrainzBatch.map { it.first }) }
            }
            logBlock("Song-Artist links")

            dbQuery {
                AlbumTable.deleteWhere {
                    notExists(
                        SongTable.select(SongTable.id).where {
                            SongTable.albumId eq AlbumTable.id
                        }
                    )
                }
            }
            logBlock("Album cleanup")

            val finalResult = insertedSongs
                .map { it.first }
                .chunked(maxBatchSize / 3)
                .map {
                    async {
                        byIds(it)
                    }
                }
                .awaitAll()
                .flatten()
                .associateBy { it.id }
            
            if (debug) {
                logger.info("Batch indexing (${filteredSongs.size} songs) complete, total time: ${(System.currentTimeMillis() - overallStart).milliseconds}")
            }
            
            finalResult
        }

    suspend fun upsertSong(remoteSong: Song) = dbQuery {
        val song = remoteSong.withSplitTitleTags()

        SongTable.upsert(SongTable.id) {
            it[id] = song.id
            it[title] = song.title
            it[titleTags] = encodeTitleTags(song.tags)
            it[isrc] = song.isrc
            it[albumId] = song.album?.id?.let { albumId -> EntityID(albumId, AlbumTable) }!!
            it[duration] = song.duration
            it[explicit] = song.explicit
            it[releaseDate] = getISOFromDate(song.releaseDate)
            it[lyrics] = song.lyrics
            it[filePath] = song.path
            it[format] = formatOf(song.path)
            it[originalUrl] = song.originalUrl
            it[trackNumber] = song.trackNumber
            it[discNumber] = song.discNumber
            it[copyright] = song.copyright
            song.effectiveAudio?.let { audio ->
                it[sampleRate] = audio.sampleRate
                it[bitsPerSample] = audio.bitsPerSample
                it[bitRate] = audio.bitRate
                it[fileSize] = audio.fileSize
                it[channels] = audio.channels
            }
            it[cover] = song.coverId?.let { coverId -> EntityID(coverId, ImageTable) }
            it[audioStartMs] = song.audioStartMs
        }
        syncSongTitleTags(song.id, song.tags)

        if (song.originalUrl.isNotBlank()) {
            addProviderUrl(song.id, song.originalUrl)
        }

        SongArtistTable.deleteWhere { SongArtistTable.songId eq song.id }
        SongArtistTable.batchInsert(song.artists.withIndex().toList()) { (index, artist) ->
            this[SongArtistTable.songId] = song.id
            this[SongArtistTable.artistId] = artist.id
            this[SongArtistTable.position] = index
            this[SongArtistTable.joinPhrase] = artist.joinPhrase
        }

        if (song.musicBrainzId != null) {
            val mbId = song.musicBrainzId!!
            cachedMusicBrainzService.getRecording(mbId, HttpClientPriority.NORMAL)

            dbQuery {
                SongMusicBrainzTable.upsert(SongMusicBrainzTable.songId) {
                    it[songId] = song.id
                    it[musicBrainzId] = mbId
                    it[lastCheck] = System.currentTimeMillis()
                }
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
