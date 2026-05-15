package dev.dertyp.services

import dev.dertyp.PlatformLocalDate
import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.getDateFromISO
import dev.dertyp.getISOFromDate
import dev.dertyp.plugins.AlbumLibrary
import dev.dertyp.services.ArtistService.Companion.mapArtist
import dev.dertyp.services.metadata.CachedMusicBrainzService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class AlbumRpcService(private val user: User, private val albumService: AlbumService) :
    IAlbumService {
    override suspend fun byId(id: UUID): Album? = albumService.byId(id, user.id)
    override suspend fun byMusicBrainzId(mbId: UUID): List<Album> = albumService.byMusicBrainzId(mbId, user.id)
    override suspend fun byIds(ids: List<UUID>): List<Album> = albumService.byIds(ids, user.id)
    override suspend fun versions(id: UUID): List<Album> = albumService.versions(id, user.id)
    override suspend fun byName(page: Int, pageSize: Int, name: String): PaginatedResponse<Album> =
        albumService.byName(page, pageSize, name, user.id)

    override suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String
    ): PaginatedResponse<Album> =
        albumService.rankedSearch(page, pageSize, query, user.id)

    override suspend fun allAlbums(page: Int, pageSize: Int): PaginatedResponse<Album> =
        albumService.allAlbums(page, pageSize, user.id)

    override suspend fun updateAlbum(album: Album): Album? =
        albumService.updateAlbum(album, user.id)

    override suspend fun deleteAlbums(ids: List<UUID>): Boolean = albumService.deleteAlbums(ids)

    override suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        singles: Boolean
    ): PaginatedResponse<Album> = albumService.byArtist(page, pageSize, artistId, singles, user.id)

    override suspend fun fetchMusicBrainzId(id: UUID): Album? =
        albumService.fetchMusicBrainzId(id, user.id, HttpClientPriority.HIGH)

    override suspend fun setMusicBrainzId(id: UUID, musicBrainzId: UUID?): Album? =
        albumService.setMusicBrainzId(id, musicBrainzId, user.id)
}

class AlbumService : AlbumLibrary, Service() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val cachedMusicBrainzService by inject<CachedMusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val artistService by inject<ArtistService>()
    private val genreService by inject<GenreService>()
    private val libraryMergeService by inject<LibraryMergeService>()
    val artistGroupAlias = ArtistTable.alias("artistGroup")
    val artistMemberAlias = ArtistTable.alias("artistMember")
    val artistGroupJoinAlias = ArtistMemberTable.alias("artistGroupJoin")
    val artistMemberJoinAlias = ArtistMemberTable.alias("artistMemberJoin")
    val followedArtistAlias = FollowedArtistTable.alias("followedArtist")

    companion object {
        fun mapAlbum(
            resultRow: ResultRow,
            genres: List<Genre> = listOf(),
            blurHashColumn: Expression<String?>? = null
        ): Album {
            val id = resultRow[AlbumTable.id].value

            return Album(
                id = id,
                name = resultRow[AlbumTable.name],
                releaseDate = getDateFromISO(resultRow[AlbumTable.releaseDate]),
                artists = listOf(),
                songCount = resultRow[AlbumTable.songCount],
                totalDuration = -1,
                coverId = resultRow[AlbumTable.cover]?.value,
                blurHash = resultRow.getOrNull(blurHashColumn ?: ImageTable.blurHash),
                genres = genres,
                originalId = resultRow[AlbumTable.originalId],
                musicbrainzId = resultRow.getOrNull(AlbumMusicBrainzTable.musicBrainzId)?.value,
            )
        }

        suspend fun calculateAlbumStats(albumIds: List<UUID>): Map<UUID, Pair<Long, Long>> =
            dbQuery {
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

    private fun ColumnSet.followedArtist(userId: UUID?) = if (userId != null) {
        leftJoin(
            followedArtistAlias,
            onColumn = { ArtistTable.id },
            otherColumn = { followedArtistAlias[FollowedArtistTable.artistId] },
            additionalConstraint = { followedArtistAlias[FollowedArtistTable.userId] eq userId }
        )
    } else this

    suspend fun fetchMusicBrainzId(
        id: UUID,
        userId: UUID? = null,
        priority: HttpClientPriority = HttpClientPriority.NORMAL,
        triggerMerge: Boolean = true
    ): Album? {
        val album = byId(id, userId) ?: return null

        val mbId = album.musicbrainzId ?: musicBrainzService.searchAlbumMb(album, priority)?.also {
            musicBrainzCacheService.updateReleaseCache(it)
        }?.id ?: return album

        val mbRelease = cachedMusicBrainzService.getRelease(mbId, priority)

        if (mbRelease != null) {
            val trackCount = mbRelease.media?.sumOf { it.trackCount ?: 0 } ?: 0

            val artistCredits = mbRelease.artistCredit ?: emptyList()

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
                                otherColumn = { SongMusicBrainzTable.songId }
                            )
                            .innerJoin(
                                MBRecordingArtistCreditTable,
                                onColumn = { SongMusicBrainzTable.musicBrainzId },
                                otherColumn = { MBRecordingArtistCreditTable.recordingId }
                            )
                            .innerJoin(SongTable, onColumn = { SongArtistTable.songId }, otherColumn = { SongTable.id })
                            .select(SongArtistTable.artistId, MBRecordingArtistCreditTable.artistId)
                            .where { (SongArtistTable.artistId inList allCandidateIds) and (MBRecordingArtistCreditTable.artistId inList mbArtistIds) and (SongTable.albumId neq id) }
                            .map { it[SongArtistTable.artistId].value to it[MBRecordingArtistCreditTable.artistId].value }

                        val fromAlbums = AlbumArtistTable
                            .innerJoin(
                                AlbumMusicBrainzTable,
                                onColumn = { AlbumArtistTable.albumId },
                                otherColumn = { AlbumMusicBrainzTable.albumId }
                            )
                            .innerJoin(
                                MBReleaseArtistCreditTable,
                                onColumn = { AlbumMusicBrainzTable.musicBrainzId },
                                otherColumn = { MBReleaseArtistCreditTable.releaseId }
                            )
                            .select(AlbumArtistTable.artistId, MBReleaseArtistCreditTable.artistId)
                            .where { (AlbumArtistTable.artistId inList allCandidateIds) and (MBReleaseArtistCreditTable.artistId inList mbArtistIds) and (AlbumArtistTable.albumId neq id) }
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
                        artist = artistService.createArtist(
                            name = name,
                            musicBrainzId = mbId,
                            userId = userId
                        )
                    } else {
                        artist = candidates.firstOrNull()
                    }
                }

                if (artist != null) {
                    resolvedArtists.add(artist)
                }
            }

            val finalArtists = resolvedArtists.distinctBy { it.id }

            val mbTracks = mbRelease.media?.flatMapIndexed { mediaIndex, media ->
                val discNumber = mediaIndex + 1
                media.tracks?.map { track ->
                    Triple(discNumber, track.position ?: 1, track)
                } ?: emptyList()
            } ?: emptyList()

            dbQuery {
                if (trackCount > 0) {
                    AlbumTable.update({ AlbumTable.id eq id }) { row ->
                        row[songCount] = trackCount
                    }
                }

                if (finalArtists.isNotEmpty()) {
                    AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId eq id }
                    AlbumArtistTable.batchInsert(finalArtists) { artist ->
                        this[AlbumArtistTable.albumId] = id
                        this[AlbumArtistTable.artistId] = artist.id
                    }
                }

                if (mbTracks.isNotEmpty()) {
                    val dbSongs = SongTable
                        .leftJoin(SongMusicBrainzTable)
                        .select(
                            SongTable.id,
                            SongTable.title,
                            SongTable.trackNumber,
                            SongTable.discNumber,
                            SongMusicBrainzTable.musicBrainzId
                        )
                        .where { SongTable.albumId eq id }
                        .map { row ->
                            val songId = row[SongTable.id].value
                            val smbId = row.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value
                            val title = row[SongTable.title]
                            Quadruple(songId, smbId, title, row)
                        }

                    for ((discNo, trackNo, mbTrack) in mbTracks) {
                        val mbRecordingId = mbTrack.recording?.id
                        val mbTitle = mbTrack.title ?: mbTrack.recording?.title

                        val matchedSong = dbSongs.find { it.second == mbRecordingId }
                            ?: if (mbTitle != null) {
                                dbSongs.find { it.third.equals(mbTitle, ignoreCase = true) }
                            } else null

                        if (matchedSong != null) {
                            SongTable.update({ SongTable.id eq matchedSong.first }) {
                                it[trackNumber] = trackNo
                                it[discNumber] = discNo
                            }
                        }
                    }
                }
            }

            val genres = (mbRelease.genres?.map { it.name }
                ?: emptyList()) + (mbRelease.releaseGroup?.genres?.map { it.name } ?: emptyList())
            if (genres.isNotEmpty()) {
                val genreIds = genreService.getOrCreateGenres(genres)
                dbQuery {
                    AlbumGenreTable.deleteWhere { AlbumGenreTable.albumId eq id }
                    AlbumGenreTable.batchInsert(genreIds) { genreId ->
                        this[AlbumGenreTable.albumId] = id
                        this[AlbumGenreTable.genreId] = genreId
                    }
                }
            }
        }

        return setMusicBrainzId(id, mbId, userId, triggerMerge)
    }

    suspend fun updateMusicBrainzLastCheck(id: UUID) = dbQuery {
        AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
            it[albumId] = id
            it[lastCheck] = Clock.System.now().toEpochMilliseconds()
        }
    }

    suspend fun setMusicBrainzId(
        id: UUID,
        musicBrainzId: UUID?,
        userId: UUID? = null,
        triggerMerge: Boolean = true
    ): Album? {
        if (musicBrainzId != null) {
            val releaseExists = dbQuery {
                MBReleaseTable.select(MBReleaseTable.id)
                    .where { MBReleaseTable.id eq musicBrainzId }
                    .any()
            }

            if (!releaseExists) {
                cachedMusicBrainzService.getRelease(musicBrainzId)
            }
        }

        dbQuery {
            AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
                it[albumId] = id
                it[AlbumMusicBrainzTable.musicBrainzId] = musicBrainzId
                it[lastCheck] = Clock.System.now().toEpochMilliseconds()
            }
        }

        if (musicBrainzId != null && triggerMerge) {
            ApplicationScope.scope.launch {
                dbQuery {
                    libraryMergeService.mergeDuplicateAlbums()
                }
            }
        }

        return byId(id, userId)
    }

    suspend fun byId(id: UUID, userId: UUID? = null): Album? = querySingle(userId = userId) {
        where { AlbumTable.id eq id }
    }

    override suspend fun byMusicBrainzId(mbId: UUID): List<Album> = byMusicBrainzId(mbId, null)

    suspend fun byMusicBrainzId(mbId: UUID, userId: UUID? = null): List<Album> {
        val directMatches = queryAlbums(0, Int.MAX_VALUE, userId = userId) {
            where { AlbumMusicBrainzTable.musicBrainzId eq mbId }
        }.data
        if (directMatches.isNotEmpty()) return directMatches

        val releaseGroupId = musicBrainzCacheService.getReleaseGroup(mbId)?.id
            ?: cachedMusicBrainzService.getRelease(mbId)?.releaseGroup?.id
            ?: cachedMusicBrainzService.getReleaseGroup(mbId)?.id
            ?: return emptyList()

        val otherAlbumIds = dbQuery {
            AlbumMusicBrainzTable
                .innerJoin(MBReleaseTable, onColumn = { AlbumMusicBrainzTable.musicBrainzId }, otherColumn = { MBReleaseTable.id })
                .select(AlbumMusicBrainzTable.albumId)
                .where { MBReleaseTable.releaseGroupId eq releaseGroupId }
                .map { it[AlbumMusicBrainzTable.albumId].value }
        }

        if (otherAlbumIds.isEmpty()) return emptyList()

        return byIds(otherAlbumIds, userId).sortedWith(
            compareByDescending<Album> { it.songCount }
                .thenByDescending { it.coverId != null }
        )
    }

    suspend fun byIds(@LogParam("size") ids: List<UUID>, userId: UUID? = null): List<Album> =
        queryAlbums(0, Int.MAX_VALUE, userId = userId) {
            where { AlbumTable.id inList ids }
        }.data

    suspend fun versions(id: UUID, userId: UUID? = null): List<Album> {
        val album = byId(id, userId) ?: return emptyList()

        if (album.musicbrainzId != null) {
            val release = cachedMusicBrainzService.getRelease(album.musicbrainzId!!)
            val releaseGroupId = release?.releaseGroup?.id
            if (releaseGroupId != null) {
                val otherAlbumIds = dbQuery {
                    AlbumMusicBrainzTable
                        .innerJoin(MBReleaseTable, onColumn = { AlbumMusicBrainzTable.musicBrainzId }, otherColumn = { MBReleaseTable.id })
                        .select(AlbumMusicBrainzTable.albumId)
                        .where { (MBReleaseTable.releaseGroupId eq releaseGroupId) and (AlbumMusicBrainzTable.albumId neq id) }
                        .map { it[AlbumMusicBrainzTable.albumId].value }
                }

                if (otherAlbumIds.isNotEmpty()) {
                    return byIds(otherAlbumIds, userId)
                }
            }
        }

        return emptyList()
    }

    suspend fun byName(
        page: Int,
        pageSize: Int,
        name: String,
        userId: UUID? = null
    ): PaginatedResponse<Album> = queryAlbums(page, pageSize, userId = userId) {
        where { AlbumTable.name eq name }
    }

    suspend fun byArtist(
        page: Int,
        pageSize: Int,
        artistId: UUID,
        singles: Boolean,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        queryAlbums(page, pageSize, userId = userId) {
            val albumIds = AlbumArtistTable
                .select(AlbumArtistTable.columns)
                .where { AlbumArtistTable.artistId eq artistId }
                .map { it[AlbumArtistTable.albumId].value }

            if (!singles) where { AlbumTable.songCount greater 1 }
            else where { AlbumTable.songCount eq 1 }
            andWhere { AlbumTable.id inList albumIds }
            orderBy(AlbumTable.releaseDate, SortOrder.DESC_NULLS_LAST)
        }

    suspend fun rankedSearch(
        page: Int,
        pageSize: Int,
        query: String,
        userId: UUID? = null
    ): PaginatedResponse<Album> =
        queryAlbums(page, pageSize, userId = userId, columnSet = {
            leftJoin(artistGroupJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistGroupJoinAlias[ArtistMemberTable.artistId] })
                .leftJoin(artistGroupAlias, onColumn = { artistGroupJoinAlias[ArtistMemberTable.groupId] }, otherColumn = { artistGroupAlias[ArtistTable.id] })
                .leftJoin(artistMemberJoinAlias, onColumn = { ArtistTable.id }, otherColumn = { artistMemberJoinAlias[ArtistMemberTable.groupId] })
                .leftJoin(artistMemberAlias, onColumn = { artistMemberJoinAlias[ArtistMemberTable.artistId] }, otherColumn = { artistMemberAlias[ArtistTable.id] })
                .withMBReleaseSearch()
                .withMBArtistSearch()
        }) {
            rankedSearchQuery(
                query,
                listOf(10, 5, 5, 3, 3, 5, 3, 5, 5, 3),
                listOf(
                    AlbumTable.name,
                    ArtistTable.name,
                    ArtistAliasTable.name,
                    artistGroupAlias[ArtistTable.name],
                    artistMemberAlias[ArtistTable.name]
                ) + mbReleaseSearchColumns + mbArtistSearchColumns,
                AlbumTable.id
            )
            andWhere { AlbumTable.songCount greater 1 }
        }

    suspend fun allAlbums(
        page: Int,
        pageSize: Int,
        userId: UUID? = null
    ): PaginatedResponse<Album> = queryAlbums(page, pageSize, userId = userId)

    suspend fun updateAlbum(album: Album, userId: UUID? = null): Album? {
        upsertAlbum(album)
        return byId(album.id, userId)
    }

    fun allAlbumIds(): Flow<UUID> = flow {
        AlbumTable
            .select(AlbumTable.id)
            .fetchBatchedResultsByIdKeyset(AlbumTable.id, 1000) { batch ->
                for (row in batch) {
                    emit(row[AlbumTable.id].value)
                }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun allAlbumsFlow(): Flow<Album> = allAlbumIds().chunked(100).flatMapConcat { ids ->
        byIds(ids).asFlow()
    }

    fun albumIdsWithoutMusicBrainzId(): Flow<UUID> = flow {
        val oneWeekAgo = Clock.System.now() - 7.days

        AlbumTable
            .leftJoin(AlbumMusicBrainzTable)
            .select(AlbumTable.id)
            .where {
                AlbumMusicBrainzTable.albumId.isNull() or
                        (AlbumMusicBrainzTable.lastCheck eq 0L) or
                        (AlbumMusicBrainzTable.musicBrainzId.isNull() and (AlbumMusicBrainzTable.lastCheck less oneWeekAgo.toEpochMilliseconds()))
            }
            .fetchBatchedResultsByIdKeyset(AlbumTable.id, 1000) { batch ->
                for (row in batch) {
                    emit(row[AlbumTable.id].value)
                }
            }
    }

    @Suppress("DuplicatedCode")
    suspend fun deleteAlbums(ids: List<UUID>): Boolean = dbQuery {
        val paths = SongTable
            .select(SongTable.albumId, SongTable.filePath)
            .where { SongTable.albumId inList ids }
            .map { it[SongTable.filePath] }

        logger.info("Found ${paths.size} files to delete.")

        val deletedSongs = SongTable.deleteWhere {
            SongTable.albumId inList ids
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
            if (file.exists() && file.toPath().isSymbolicLink())
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

    private suspend fun querySingle(
        userId: UUID? = null,
        query: Query.() -> Query = { this }
    ) = queryAlbums(0, Int.MAX_VALUE, userId = userId, query = query).data.singleOrNull()

    private suspend fun queryAlbums(
        page: Int,
        pageSize: Int,
        userId: UUID? = null,
        columnSet: ColumnSet.() -> ColumnSet = { this },
        query: Query.() -> Query = { this }
    ) = dbQuery {
        val offset = if (pageSize == Int.MAX_VALUE) 0 else 1
        val rows = AlbumTable
            .leftJoin(
                AlbumArtistTable,
                onColumn = { AlbumTable.id },
                otherColumn = { AlbumArtistTable.albumId })
            .leftJoin(
                ArtistTable,
                onColumn = { AlbumArtistTable.artistId },
                otherColumn = { ArtistTable.id }
            )
            .followedArtist(userId)
            .leftJoin(
                ArtistMusicBrainzTable,
                onColumn = { ArtistTable.id },
                otherColumn = { ArtistMusicBrainzTable.artistId }
            )
            .leftJoin(ArtistAliasTable)
            .leftJoin(AlbumMusicBrainzTable)
            .leftJoin(AlbumGenreTable)
            .leftJoin(GenreTable)
            .leftJoin(ImageTable, onColumn = { AlbumTable.cover }, otherColumn = { ImageTable.id })
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

        val albumIds = rows.map { it[AlbumTable.id].value }.distinct()

        val statsByAlbumId = if (albumIds.isNotEmpty()) {
            calculateAlbumStats(albumIds)
        } else {
            emptyMap()
        }

        val data = mapEagerly(rows, statsByAlbumId).distinctBy { it.id }

        PaginatedResponse(
            data = data.drop(page * pageSize).take(pageSize),
            total = data.size,
            page = page,
            pageSize = pageSize,
            hasNextPage = data.drop(page * pageSize).size >= pageSize + offset,
        )
    }

    private fun mapEagerly(
        rows: List<ResultRow>,
        albumStats: Map<UUID, Pair<Long, Long>>
    ): List<Album> {
        val albumMap = mutableMapOf<UUID, Album>()
        val albumArtistsMap = mutableMapOf<UUID, MutableList<Artist>>()
        val albumGenresMap = mutableMapOf<UUID, MutableList<Genre>>()

        for (row in rows) {
            val albumId = row[AlbumTable.id].value

            albumMap.getOrPut(albumId) {
                val genres = rows.filter { it[AlbumTable.id].value == albumId }
                    .mapNotNull { r ->
                        val gid = r.getOrNull(GenreTable.id)?.value ?: return@mapNotNull null
                        val gname = r.getOrNull(GenreTable.name) ?: return@mapNotNull null
                        Genre(gid, gname)
                    }.distinctBy { it.id }
                mapAlbum(row, genres)
            }

            if (row.getOrNull(ArtistTable.id) != null) {
                val artist = mapArtist(row, followedTable = followedArtistAlias)
                if (artist !in albumArtistsMap.getOrDefault(albumId, emptyList())) {
                    albumArtistsMap.getOrPut(albumId) { mutableListOf() }.add(artist)
                }
            }

            if (row.getOrNull(GenreTable.id) != null) {
                val genre = Genre(row[GenreTable.id].value, row[GenreTable.name])
                if (genre !in albumGenresMap.getOrDefault(albumId, emptyList())) {
                    albumGenresMap.getOrPut(albumId) { mutableListOf() }.add(genre)
                }
            }
        }

        return albumMap.values.map { album ->
            val albumArtists = albumArtistsMap[album.id]?.distinctBy { it.id } ?: listOf()
            val albumGenres = albumGenresMap[album.id]?.distinctBy { it.id } ?: listOf()

            album.copy(
                artists = albumArtists,
                genres = albumGenres,
                totalDuration = albumStats[album.id]?.first ?: -1L,
                totalSize = albumStats[album.id]?.second ?: -1L
            )
        }
    }

    data class BulkCreateAlbumResult(
        val albumToIds: Map<InsertableAlbum, UUID>,
        val newlyCreated: Set<InsertableAlbum>
    )

    override suspend fun createBatch(albums: List<InsertableAlbum>): Map<UUID, Album> {
        val result = getOrBulkCreateWithResult(albums)
        return byIds(result.albumToIds.values.toList()).associateBy { it.id }
    }

    suspend fun getOrBulkCreateWithResult(albums: List<InsertableAlbum>): BulkCreateAlbumResult {
        if (albums.isEmpty()) return BulkCreateAlbumResult(emptyMap(), emptySet())

        val artistService = get<ArtistService>()
        val imageService = get<ImageService>()

        val uniqueCoverHashed = albums.distinctBy { it.coverHash }.mapNotNull { it.coverHash }
        val albumsByIdentity = albums.groupBy {
            if (it.originalId != null) it.originalId
            else Triple(it.name, it.artists.sorted(), it.releaseDate)
        }.mapValues { (_, group) ->
            group.maxByOrNull {
                (if (it.releaseDate != null) 1 else 0) +
                        (if (it.songCount > 0) 1 else 0) +
                        (if (it.coverHash != null) 1 else 0)
            }!!
        }
        val uniqueAlbumMetadata = albumsByIdentity.values.toList()
        val uniqueAlbumNames = uniqueAlbumMetadata.map { it.name }
        val uniqueSongCounts = uniqueAlbumMetadata.map { it.songCount }
        val uniqueReleaseDates = uniqueAlbumMetadata.map { getISOFromDate(it.releaseDate) }
        val uniqueOriginalIds = uniqueAlbumMetadata.map { it.originalId }
        val allRequiredArtistNames = albums.flatMap { it.artists }.distinct()

        val artistIdMap: Map<String, List<UUID>> =
            artistService.getOrBulkCreate(allRequiredArtistNames)
        val imageMap: Map<String, UUID> = imageService.getCoverHashes(uniqueCoverHashed)

        val potentialAlbumRows = queryAlbums(0, Int.MAX_VALUE) {
            where { AlbumTable.name inList uniqueAlbumNames }
            andWhere { AlbumTable.releaseDate inList uniqueReleaseDates }
            andWhere { AlbumTable.songCount inList uniqueSongCounts }
            andWhere { (AlbumTable.originalId inList uniqueOriginalIds) or (AlbumTable.originalId eq null) }
        }.data

        val potentialAlbumIds = potentialAlbumRows.map { it.id }.toSet()

        val albumArtistLinks = dbQuery {
            AlbumArtistTable
                .select(AlbumArtistTable.albumId, AlbumArtistTable.artistId)
                .where { AlbumArtistTable.albumId inList potentialAlbumIds }
                .toList()
        }

        val artistsByPotentialAlbumId = albumArtistLinks
            .groupBy(
                { it[AlbumArtistTable.albumId].value },
                { it[AlbumArtistTable.artistId].value })
            .mapValues { (_, artistIds) -> artistIds.toSet() }

        fun getIdentityKey(originalId: String?, name: String, artists: List<String>, releaseDate: PlatformLocalDate?): Any {
            return originalId ?: Triple(name, artists.sorted(), getISOFromDate(releaseDate))
        }

        val finalMatchMap = mutableMapOf<Any, UUID>()

        for (row in potentialAlbumRows) {
            val albumId = row.id
            val albumArtists = artistsByPotentialAlbumId[albumId] ?: emptySet()

            val inputAlbum = uniqueAlbumMetadata.firstOrNull {
                if (it.originalId != null && row.originalId != null) {
                    it.originalId == row.originalId
                } else if (it.originalId == null && row.originalId == null) {
                    it.name == row.name &&
                            getISOFromDate(it.releaseDate) == getISOFromDate(row.releaseDate) &&
                            it.songCount == row.songCount
                } else {
                    false
                }
            }

            if (inputAlbum != null) {
                val requiredArtistIdsForInput =
                    inputAlbum.artists.flatMap { artistIdMap[it] ?: emptyList() }.toSet()

                if (inputAlbum.originalId != null || albumArtists == requiredArtistIdsForInput) {
                    finalMatchMap[getIdentityKey(row.originalId, row.name, inputAlbum.artists, row.releaseDate)] = albumId
                }
            }
        }

        val newAlbumsToInsert = uniqueAlbumMetadata.filter { album ->
            val key = getIdentityKey(album.originalId, album.name, album.artists, album.releaseDate)
            !finalMatchMap.containsKey(key)
        }

        val newRows = if (newAlbumsToInsert.isNotEmpty()) {
            dbQuery {
                AlbumTable.batchInsert(newAlbumsToInsert) { album ->
                    this[AlbumTable.name] = album.name
                    this[AlbumTable.releaseDate] = getISOFromDate(album.releaseDate)
                    this[AlbumTable.songCount] = album.songCount
                    this[AlbumTable.cover] = imageMap[album.coverHash]
                    this[AlbumTable.originalId] = album.originalId
                }
            }
        } else {
            emptyList()
        }

        val newAlbumIdLookupMap = newRows.associate { row ->
            val rowOriginalId = row[AlbumTable.originalId]
            val rowName = row[AlbumTable.name]
            val rowReleaseDate = row[AlbumTable.releaseDate]

            val matchedAlbum = newAlbumsToInsert.first {
                if (it.originalId != null && rowOriginalId != null) {
                    it.originalId == rowOriginalId
                } else if (it.originalId == null && rowOriginalId == null) {
                    it.name == rowName && getISOFromDate(it.releaseDate) == rowReleaseDate
                } else false
            }

            getIdentityKey(rowOriginalId, rowName, matchedAlbum.artists, matchedAlbum.releaseDate) to row[AlbumTable.id].value
        }

        val newAlbumArtistLinks = newAlbumsToInsert.flatMap { album ->
            val key = getIdentityKey(album.originalId, album.name, album.artists, album.releaseDate)
            val albumId = newAlbumIdLookupMap[key]
            if (albumId != null) {
                album.artists.flatMap { artistName ->
                    artistIdMap[artistName]?.map { artistId ->
                        albumId to artistId
                    } ?: emptyList()
                }
            } else {
                emptyList()
            }
        }

        if (newAlbumArtistLinks.isNotEmpty()) {
            dbQuery {
                AlbumArtistTable.batchInsert(newAlbumArtistLinks) { (albumId, artistId) ->
                    this[AlbumArtistTable.albumId] = albumId
                    this[AlbumArtistTable.artistId] = artistId
                }
            }
        }

        val finalCombinedIdMap = finalMatchMap + newAlbumIdLookupMap

        val resultMap = albums.associateWith { album ->
            val key = getIdentityKey(album.originalId, album.name, album.artists, album.releaseDate)
            finalCombinedIdMap[key]
        }.filterValueNotNull()

        return BulkCreateAlbumResult(resultMap, newAlbumsToInsert.toSet())
    }

    suspend fun getOrBulkCreate(albums: List<InsertableAlbum>): Map<InsertableAlbum, UUID> =
        getOrBulkCreateWithResult(albums).albumToIds

    suspend fun deleteEmptyAlbums(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int =
        dbQuery {
            val emptyAlbums = AlbumTable
                .select(AlbumTable.id)
                .where {
                    notExists(
                        SongTable.select(SongTable.id).where {
                            SongTable.albumId eq AlbumTable.id
                        }
                    )
                }
                .map { it[AlbumTable.id].value }

            onProgress(0.0, "Found ${emptyAlbums.size} empty albums")

            val chunks = emptyAlbums.chunked(5000)
            chunks.forEachIndexed { index, batch ->
                val progress = (index.toDouble() / chunks.size) * 100.0
                onProgress(
                    progress,
                    "Deleting batch ${index + 1}/${chunks.size} (${batch.size} albums)"
                )

                AlbumTable.deleteWhere { AlbumTable.id inList batch }
                AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId inList batch }
            }

            onProgress(100.0, "Deleted ${emptyAlbums.size} albums")
            logger.info("Deleted ${emptyAlbums.size} empty albums")
            emptyAlbums.size
        }

    suspend fun upsertAlbum(album: Album) = dbQuery {
        AlbumTable.upsert(AlbumTable.id) {
            it[id] = album.id
            it[name] = album.name
            it[releaseDate] = getISOFromDate(album.releaseDate)
            it[songCount] = album.songCount
            it[cover] = album.coverId?.let { coverId -> EntityID(coverId, ImageTable) }
            it[originalId] = album.originalId
        }

        if (album.musicbrainzId != null) {
            val mbId = album.musicbrainzId!!
            if (MBReleaseTable.selectAll().where { MBReleaseTable.id eq mbId }.empty()) {
                MBReleaseTable.insert {
                    it[id] = EntityID(mbId, MBReleaseTable)
                    it[title] = album.name
                }
            }

            AlbumMusicBrainzTable.upsert(AlbumMusicBrainzTable.albumId) {
                it[albumId] = album.id
                it[musicBrainzId] = mbId
            }
        }

        AlbumArtistTable.deleteWhere { AlbumArtistTable.albumId eq album.id }
        AlbumArtistTable.batchInsert(album.artists) { artist ->
            this[AlbumArtistTable.albumId] = album.id
            this[AlbumArtistTable.artistId] = artist.id
        }
    }
}
