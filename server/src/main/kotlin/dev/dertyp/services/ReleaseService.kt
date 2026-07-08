package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.*
import dev.dertyp.data.ArtistType
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.MusicBrainzRelease
import dev.dertyp.data.MusicBrainzReleaseGroup
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.ReleaseType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.platformDateFromEpochMilliseconds
import dev.dertyp.services.metadata.*
import dev.dertyp.services.models.FollowedArtist
import dev.dertyp.services.models.RecentRelease
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class ReleaseService(private val environment: ApplicationEnvironment) : Service() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val artistService by inject<ArtistService>()
    private val imageService by inject<ImageService>()
    private val linkResolverService by inject<LinkResolverService>()

    private val RELEASE_REFRESH_WINDOW = 14.days
    private val REFRESH_COOLDOWN = 20.hours

    suspend fun followArtist(userId: UUID, musicBrainzId: UUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): Boolean {
        val artistId = getOrCreateArtistByMbId(musicBrainzId, priority) ?: return false
        return dbQuery {
            FollowedArtistTable.upsert(FollowedArtistTable.userId, FollowedArtistTable.artistId) {
                it[FollowedArtistTable.userId] = userId
                it[FollowedArtistTable.artistId] = artistId
            }.insertedCount > 0
        }
    }

    private suspend fun getOrCreateArtistByMbId(musicBrainzId: UUID, priority: HttpClientPriority = HttpClientPriority.NORMAL): UUID? {
        val existingArtistId = dbQuery {
            ArtistMusicBrainzTable.selectAll()
                .where { ArtistMusicBrainzTable.musicBrainzId eq musicBrainzId }
                .singleOrNull()?.get(ArtistMusicBrainzTable.artistId)?.value
        }
        if (existingArtistId != null) return existingArtistId

        val mbArtist = musicBrainzService.fetchArtistById(musicBrainzId, priority) ?: return null
        musicBrainzCacheService.updateArtistCache(mbArtist)
        val artist = artistService.createArtist(
            name = mbArtist.name ?: "Unknown Artist",
            isGroup = mbArtist.type == ArtistType.GROUP,
            about = mbArtist.disambiguation ?: "",
            musicBrainzId = musicBrainzId
        )
        return artist.id
    }

    suspend fun unfollowArtist(userId: UUID, artistId: UUID): Boolean = dbQuery {
        FollowedArtistTable.deleteWhere {
            (FollowedArtistTable.userId eq userId) and (FollowedArtistTable.artistId eq artistId)
        } > 0
    }

    suspend fun getFollowedArtists(userId: UUID): List<FollowedArtist> = dbQuery {
        (FollowedArtistTable innerJoin ArtistTable)
            .selectAll()
            .where { FollowedArtistTable.userId eq userId }
            .map {
                FollowedArtist(
                    artistId = it[ArtistTable.id].value,
                    name = it[ArtistTable.name],
                    imageId = it[ArtistTable.image]?.value
                )
            }
    }

    suspend fun getRecentReleases(
        userId: UUID,
        page: Int = 0,
        pageSize: Int = 150
    ): PaginatedResponse<RecentRelease> = dbQuery {
        val followedArtistIds = FollowedArtistTable.selectAll()
            .where { FollowedArtistTable.userId eq userId }
            .map { it[FollowedArtistTable.artistId].value }

        val total = RecentReleaseTable
            .leftJoin(ImageTable, onColumn = { RecentReleaseTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .where { (RecentReleaseTable.artistId inList followedArtistIds) and (RecentReleaseTable.albumId.isNull()) and (RecentReleaseTable.songId.isNull()) and (RecentReleaseTable.releaseDate.isNotNull()) }
            .count()

        val releasesRows = RecentReleaseTable
            .leftJoin(ImageTable, onColumn = { RecentReleaseTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .where { (RecentReleaseTable.artistId inList followedArtistIds) and (RecentReleaseTable.albumId.isNull()) and (RecentReleaseTable.songId.isNull()) and (RecentReleaseTable.releaseDate.isNotNull()) }
            .orderBy(
                RecentReleaseTable.releaseDate to SortOrder.DESC,
                RecentReleaseTable.releaseId to SortOrder.DESC,
            )
            .limit(pageSize)
            .offset((page * pageSize).toLong())
            .toList()

        val releaseIds = releasesRows.map { it[RecentReleaseTable.releaseId].value }

        val providersMap = releaseIds.chunked(10000).flatMap { chunk ->
            RecentReleaseProviderTable.selectAll()
                .where { RecentReleaseProviderTable.releaseId inList chunk }
                .orderBy(
                    RecentReleaseProviderTable.provider to SortOrder.ASC,
                    RecentReleaseProviderTable.externalId to SortOrder.ASC,
                )
                .map { it[RecentReleaseProviderTable.releaseId].value to it[RecentReleaseProviderTable.rawUrl] }
        }.groupBy({ it.first }, { it.second })

        val data = releasesRows.map {
            val groupId = it[RecentReleaseTable.releaseId].value
            RecentRelease(
                releaseId = groupId,
                artistId = it[RecentReleaseTable.artistId].value,
                artistName = it[RecentReleaseTable.artistName],
                title = it[RecentReleaseTable.title],
                releaseDate = it[RecentReleaseTable.releaseDate]?.let { ms -> platformDateFromEpochMilliseconds(ms) },
                type = it[RecentReleaseTable.type],
                imageId = it[RecentReleaseTable.imageId]?.value,
                blurHash = it.getOrNull(ImageTable.blurHash),
                links = providersMap[groupId] ?: emptyList(),
                albumId = it[RecentReleaseTable.albumId]?.value,
                songId = it[RecentReleaseTable.songId]?.value
            )
        }

        PaginatedResponse(
            data = data,
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
            hasNextPage = (page + 1).toLong() * pageSize < total
        )
    }

    suspend fun getArtistRecentReleases(
        artistId: UUID,
        page: Int = 0,
        pageSize: Int = 150
    ): PaginatedResponse<RecentRelease> = dbQuery {
        val hasMbId = ArtistMusicBrainzTable.selectAll()
            .where { (ArtistMusicBrainzTable.artistId eq artistId) and ArtistMusicBrainzTable.musicBrainzId.isNotNull() }
            .any()

        if (!hasMbId) return@dbQuery PaginatedResponse(emptyList(), 0, page, pageSize, false)

        val query = RecentReleaseTable
            .leftJoin(ImageTable, onColumn = { RecentReleaseTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .where { RecentReleaseTable.artistId eq artistId }

        val total = query.count()

        val releasesRows = query
            .orderBy(
                RecentReleaseTable.releaseDate to SortOrder.DESC,
                RecentReleaseTable.releaseId to SortOrder.DESC,
            )
            .limit(pageSize)
            .offset((page * pageSize).toLong())
            .toList()

        val releaseIds = releasesRows.map { it[RecentReleaseTable.releaseId].value }

        val providersMap = releaseIds.chunked(10000).flatMap { chunk ->
            RecentReleaseProviderTable.selectAll()
                .where { RecentReleaseProviderTable.releaseId inList chunk }
                .orderBy(
                    RecentReleaseProviderTable.provider to SortOrder.ASC,
                    RecentReleaseProviderTable.externalId to SortOrder.ASC,
                )
                .map { it[RecentReleaseProviderTable.releaseId].value to it[RecentReleaseProviderTable.rawUrl] }
        }.groupBy({ it.first }, { it.second })

        val data = releasesRows.map {
            val groupId = it[RecentReleaseTable.releaseId].value
            RecentRelease(
                releaseId = groupId,
                artistId = it[RecentReleaseTable.artistId].value,
                artistName = it[RecentReleaseTable.artistName],
                title = it[RecentReleaseTable.title],
                releaseDate = it[RecentReleaseTable.releaseDate]?.let { ms -> platformDateFromEpochMilliseconds(ms) },
                type = it[RecentReleaseTable.type],
                imageId = it[RecentReleaseTable.imageId]?.value,
                blurHash = it.getOrNull(ImageTable.blurHash),
                links = providersMap[groupId] ?: emptyList(),
                albumId = it[RecentReleaseTable.albumId]?.value,
                songId = it[RecentReleaseTable.songId]?.value
            )
        }

        PaginatedResponse(
            data = data,
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
            hasNextPage = (page + 1).toLong() * pageSize < total
        )
    }

    suspend fun getRecentReleasesByMusicBrainzId(
        musicBrainzId: UUID,
        page: Int = 0,
        pageSize: Int = 150
    ): PaginatedResponse<RecentRelease> {
        val artistId = getOrCreateArtistByMbId(musicBrainzId) ?: return PaginatedResponse(emptyList(), 0, page, pageSize, false)
        return getArtistRecentReleases(artistId, page, pageSize)
    }

    suspend fun refreshRecentRelease(releaseId: UUID): RecentRelease? {
        val cachedArtistId = dbQuery {
            RecentReleaseTable.select(RecentReleaseTable.artistId)
                .where { RecentReleaseTable.releaseId eq releaseId }
                .singleOrNull()?.get(RecentReleaseTable.artistId)?.value
        }

        val artistId: UUID
        val mbId: UUID
        if (cachedArtistId != null) {
            val cachedMbId = dbQuery {
                ArtistMusicBrainzTable.select(ArtistMusicBrainzTable.musicBrainzId)
                    .where { ArtistMusicBrainzTable.artistId eq cachedArtistId }
                    .andWhere { ArtistMusicBrainzTable.musicBrainzId.isNotNull() }
                    .firstOrNull()?.get(ArtistMusicBrainzTable.musicBrainzId)?.value
            } ?: return null
            artistId = cachedArtistId
            mbId = cachedMbId
        } else {
            val releases = musicBrainzService.fetchReleasesByReleaseGroup(releaseId, priority = HttpClientPriority.HIGH)
            val artistMbId = releases.firstNotNullOfOrNull { release ->
                release.artistCredit?.firstNotNullOfOrNull { it.artist?.id }
            } ?: return null
            artistId = getOrCreateArtistByMbId(artistMbId, HttpClientPriority.HIGH) ?: return null
            mbId = artistMbId
        }

        val artistName = dbQuery {
            ArtistTable.select(ArtistTable.name)
                .where { ArtistTable.id eq artistId }
                .singleOrNull()?.get(ArtistTable.name)
        } ?: return null

        val tidalService = MetadataService.getMetadataService(
            IMetadataService.MetadataType.tidal,
            environment
        ) as TidalService

        val appleMusicService = MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService

        val mbReleases = musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.HIGH)

        val albumMappings = dbQuery {
            AlbumMusicBrainzTable.innerJoin(
                AlbumArtistTable,
                onColumn = { AlbumMusicBrainzTable.albumId },
                otherColumn = { AlbumArtistTable.albumId })
                .selectAll()
                .where { AlbumArtistTable.artistId eq artistId }
                .mapNotNull {
                    it[AlbumMusicBrainzTable.musicBrainzId]?.let { mb ->
                        mb.value to it[AlbumMusicBrainzTable.albumId].value
                    }
                }
                .toMap()
        }

        val songMappings = dbQuery {
            SongMusicBrainzTable.innerJoin(
                SongArtistTable,
                onColumn = { SongMusicBrainzTable.songId },
                otherColumn = { SongArtistTable.songId })
                .selectAll()
                .where { SongArtistTable.artistId eq artistId }
                .mapNotNull {
                    it[SongMusicBrainzTable.musicBrainzId]?.let { mb ->
                        mb.value to it[SongMusicBrainzTable.songId].value
                    }
                }
                .toMap()
        }

        val group = musicBrainzService.fetchReleaseGroupById(releaseId, priority = HttpClientPriority.HIGH)
            ?: musicBrainzCacheService.getReleaseGroup(releaseId)
            ?: return null

        processReleaseGroup(
            group = group,
            artistId = artistId,
            artistName = artistName,
            mbReleases = mbReleases,
            albumMappings = albumMappings,
            songMappings = songMappings,
            tidalService = tidalService,
            appleMusicService = appleMusicService,
            dbSemaphore = Semaphore(1),
            forceRefresh = true
        )

        return getRecentReleaseById(releaseId)
    }

    private suspend fun getRecentReleaseById(releaseId: UUID): RecentRelease? = dbQuery {
        val row = RecentReleaseTable
            .leftJoin(ImageTable, onColumn = { RecentReleaseTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .where { RecentReleaseTable.releaseId eq releaseId }
            .singleOrNull() ?: return@dbQuery null

        val links = RecentReleaseProviderTable.selectAll()
            .where { RecentReleaseProviderTable.releaseId eq releaseId }
            .orderBy(
                RecentReleaseProviderTable.provider to SortOrder.ASC,
                RecentReleaseProviderTable.externalId to SortOrder.ASC,
            )
            .map { it[RecentReleaseProviderTable.rawUrl] }

        RecentRelease(
            releaseId = row[RecentReleaseTable.releaseId].value,
            artistId = row[RecentReleaseTable.artistId].value,
            artistName = row[RecentReleaseTable.artistName],
            title = row[RecentReleaseTable.title],
            releaseDate = row[RecentReleaseTable.releaseDate]?.let { platformDateFromEpochMilliseconds(it) },
            type = row[RecentReleaseTable.type],
            imageId = row[RecentReleaseTable.imageId]?.value,
            blurHash = row.getOrNull(ImageTable.blurHash),
            links = links,
            albumId = row[RecentReleaseTable.albumId]?.value,
            songId = row[RecentReleaseTable.songId]?.value
        )
    }

    suspend fun fetchNewReleases(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> = coroutineScope {
        val tidalService = MetadataService.getMetadataService(
            IMetadataService.MetadataType.tidal,
            environment
        ) as TidalService

        val appleMusicService = MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService

        val followedArtists = dbQuery {
            FollowedArtistTable.innerJoin(ArtistMusicBrainzTable, { FollowedArtistTable.artistId }, { ArtistMusicBrainzTable.artistId })
                .select(FollowedArtistTable.artistId, ArtistMusicBrainzTable.musicBrainzId)
                .where { ArtistMusicBrainzTable.musicBrainzId.isNotNull() }
                .groupBy(FollowedArtistTable.artistId, ArtistMusicBrainzTable.musicBrainzId)
                .map { it[FollowedArtistTable.artistId].value to it[ArtistMusicBrainzTable.musicBrainzId]!!.value }
                .distinctBy { it.second }
        }

        val followedResults = processArtistsBatch(
            artists = followedArtists,
            tidalService = tidalService,
            appleMusicService = appleMusicService,
            onProgress = { p, s -> onProgress(p * 0.5, s) },
            label = "followed"
        )

        val unfollowedResults = fetchUnfollowedArtistsReleases(tidalService, appleMusicService) { p, s ->
            onProgress(p * 0.5 + 50.0, s)
        }

        onProgress(100.0, "Finished fetching new releases")

        backfillMissingRecentReleaseImages()

        followedResults + unfollowedResults
    }

    suspend fun backfillMissingRecentReleaseImages() {
        val now = Clock.System.now().toEpochMilliseconds()
        val candidates = dbQuery {
            RecentReleaseTable.select(RecentReleaseTable.releaseId, RecentReleaseTable.releaseDate, RecentReleaseTable.lastImageFetch)
                .where { RecentReleaseTable.imageId.isNull() }
                .map {
                    Triple(
                        it[RecentReleaseTable.releaseId].value,
                        it[RecentReleaseTable.releaseDate],
                        it[RecentReleaseTable.lastImageFetch]
                    )
                }
        }

        val missingImages = candidates.filter { (_, releaseDate, lastFetch) ->
            if (lastFetch == null) return@filter true

            val releaseAge = if (releaseDate != null) now - releaseDate else Long.MAX_VALUE
            val lastFetchAge = now - lastFetch

            val requiredCooldown = when {
                releaseAge < 5.days.inWholeMilliseconds -> 1.days
                releaseAge < 9.days.inWholeMilliseconds -> 2.days
                releaseAge < 30.days.inWholeMilliseconds -> 7.days
                else -> 30.days
            }

            lastFetchAge >= requiredCooldown.inWholeMilliseconds
        }.map { it.first }

        if (missingImages.isEmpty()) return

        logger.info("Backfilling images for ${missingImages.size} recent releases (Progressive cooldown)")
        val semaphore = Semaphore(5)

        coroutineScope {
            missingImages.forEach { releaseGroupId ->
                launch {
                    semaphore.withPermit {
                        try {
                            val imageId = fetchReleaseGroupImage(releaseGroupId)
                            dbQuery {
                                RecentReleaseTable.update({ RecentReleaseTable.releaseId eq releaseGroupId }) {
                                    it[RecentReleaseTable.imageId] = imageId
                                    it[RecentReleaseTable.lastImageFetch] = Clock.System.now().toEpochMilliseconds()
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to backfill image for release group $releaseGroupId", e)
                            dbQuery {
                                RecentReleaseTable.update({ RecentReleaseTable.releaseId eq releaseGroupId }) {
                                    it[RecentReleaseTable.lastImageFetch] = Clock.System.now().toEpochMilliseconds()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchUnfollowedArtistsReleases(
        tidalService: TidalService,
        appleMusicService: AppleMusicService,
        onProgress: suspend (Double, String) -> Unit
    ): Map<String, Int> = withTimeoutOrNull(2.hours) {
        val oneMonthAgo = Clock.System.now() - 30.days
        val unfollowedArtists: List<Pair<UUID, UUID>> = dbQuery {
            ArtistTable.innerJoin(ArtistMusicBrainzTable, { ArtistTable.id }, { ArtistMusicBrainzTable.artistId })
                .leftJoin(SongArtistTable, { ArtistTable.id }, { SongArtistTable.artistId })
                .leftJoin(FollowedArtistTable, { ArtistTable.id }, { FollowedArtistTable.artistId })
                .select(ArtistTable.id, ArtistMusicBrainzTable.musicBrainzId, SongArtistTable.songId.count())
                .where { FollowedArtistTable.artistId.isNull() }
                .andWhere { ArtistMusicBrainzTable.musicBrainzId.isNotNull() }
                .andWhere {
                    (ArtistMusicBrainzTable.lastReleaseCheck eq 0L) or
                            (ArtistMusicBrainzTable.lastReleaseCheck less oneMonthAgo.toEpochMilliseconds())
                }
                .groupBy(ArtistTable.id, ArtistMusicBrainzTable.musicBrainzId)
                .orderBy(SongArtistTable.songId.count(), SortOrder.DESC)
                .map { it[ArtistTable.id].value to it[ArtistMusicBrainzTable.musicBrainzId]!!.value }
        }

        logger.info("Starting to fetch new releases for ${unfollowedArtists.size} unfollowed artists (2h timeout)")
        processArtistsBatch(unfollowedArtists, tidalService, appleMusicService, onProgress, "unfollowed")
    } ?: emptyMap()

    private suspend fun processArtistsBatch(
        artists: List<Pair<UUID, UUID>>,
        tidalService: TidalService,
        appleMusicService: AppleMusicService,
        onProgress: suspend (Double, String) -> Unit,
        label: String
    ): Map<String, Int> = coroutineScope {
        val totalArtists = artists.size
        if (totalArtists == 0) return@coroutineScope emptyMap()

        logger.info("Starting to fetch new releases for $totalArtists $label artists")
        onProgress(0.0, "Starting to fetch new releases for $totalArtists $label artists")

        var currentProgress = 0.0
        val progressMutex = Mutex()
        val dbSemaphore = Semaphore(1)
        val artistSemaphore = Semaphore(15)

        val results = artists.map { (artistId, mbId) ->
            async {
                artistSemaphore.withPermit {
                    val artistWeight = 1.0 / totalArtists
                    var progressAddedForArtist = 0.0
                    var artistName: String? = null
                    try {
                        val resolvedArtistName = dbSemaphore.withPermit {
                            dbQuery {
                                ArtistTable.selectAll().where { ArtistTable.id eq artistId }.singleOrNull()
                                    ?.get(ArtistTable.name)
                            }
                        } ?: return@withPermit null
                        artistName = resolvedArtistName

                        logger.info("Fetching releases for $label artist: $resolvedArtistName")

                        val mbReleases = musicBrainzService.fetchReleasesByArtist(mbId, priority = HttpClientPriority.LOW)

                        val albumMappings = dbSemaphore.withPermit {
                            dbQuery {
                                AlbumMusicBrainzTable.innerJoin(
                                    AlbumArtistTable,
                                    onColumn = { AlbumMusicBrainzTable.albumId },
                                    otherColumn = { AlbumArtistTable.albumId })
                                    .selectAll()
                                    .where { AlbumArtistTable.artistId eq artistId }
                                    .mapNotNull {
                                        it[AlbumMusicBrainzTable.musicBrainzId]?.let { mbId ->
                                            mbId.value to it[AlbumMusicBrainzTable.albumId].value
                                        }
                                    }
                                    .toMap()
                            }
                        }

                        val songMappings = dbSemaphore.withPermit {
                            dbQuery {
                                SongMusicBrainzTable.innerJoin(
                                    SongArtistTable,
                                    onColumn = { SongMusicBrainzTable.songId },
                                    otherColumn = { SongArtistTable.songId })
                                    .selectAll()
                                    .where { SongArtistTable.artistId eq artistId }
                                    .mapNotNull {
                                        it[SongMusicBrainzTable.musicBrainzId]?.let { mbId ->
                                            mbId.value to it[SongMusicBrainzTable.songId].value
                                        }
                                    }
                                    .toMap()
                            }
                        }

                        val groups = musicBrainzService.fetchReleaseGroups(mbId, priority = HttpClientPriority.LOW)
                        if (groups.isEmpty()) {
                            progressMutex.withLock {
                                currentProgress += artistWeight
                                progressAddedForArtist += artistWeight
                            }
                            dbSemaphore.withPermit {
                                onProgress(currentProgress * 100.0, "Checked $artistName (no releases)")
                            }
                        }
                        val groupWeight = if (groups.isNotEmpty()) artistWeight / groups.size else 0.0

                        val newReleasesCount = groups.map { group ->
                            async {
                                try {
                                    processReleaseGroup(
                                        group = group,
                                        artistId = artistId,
                                        artistName = resolvedArtistName,
                                        mbReleases = mbReleases,
                                        albumMappings = albumMappings,
                                        songMappings = songMappings,
                                        tidalService = tidalService,
                                        appleMusicService = appleMusicService,
                                        dbSemaphore = dbSemaphore,
                                        forceRefresh = false
                                    )
                                } catch (e: Exception) {
                                    logger.error("Failed to process group ${group.id}", e)
                                    false
                                } finally {
                                    progressMutex.withLock {
                                        currentProgress += groupWeight
                                        progressAddedForArtist += groupWeight
                                    }
                                    dbSemaphore.withPermit {
                                        onProgress(currentProgress * 100.0, "Processed $resolvedArtistName: ${group.title}")
                                    }
                                }
                            }
                        }.awaitAll().count { it }

                        dbSemaphore.withPermit {
                            dbQuery {
                                FollowedArtistTable.update({ FollowedArtistTable.artistId eq artistId }) {
                                    it[lastCheck] = Clock.System.now().toEpochMilliseconds()
                                }
                                ArtistMusicBrainzTable.update({ ArtistMusicBrainzTable.artistId eq artistId }) {
                                    it[lastReleaseCheck] = Clock.System.now().toEpochMilliseconds() + (0.days .. 5.days).random().inWholeMilliseconds
                                }
                            }
                        }

                        if (newReleasesCount > 0) resolvedArtistName to newReleasesCount else null
                    } catch (e: Exception) {
                        logger.error("Failed to fetch new releases for artist $artistId", e)
                        null
                    } finally {
                        val remaining = artistWeight - progressAddedForArtist
                        if (remaining > 0.000001) {
                            progressMutex.withLock { currentProgress += remaining }
                            dbSemaphore.withPermit {
                                onProgress(currentProgress * 100.0, "Finished $artistName processing")
                            }
                        }
                    }
                }
            }
        }.awaitAll().filterNotNull().toMap()

        results
    }

    private suspend fun processReleaseGroup(
        group: MusicBrainzReleaseGroup,
        artistId: UUID,
        artistName: String,
        mbReleases: List<MusicBrainzRelease>,
        albumMappings: Map<UUID, UUID>,
        songMappings: Map<UUID, UUID>,
        tidalService: TidalService,
        appleMusicService: AppleMusicService,
        dbSemaphore: Semaphore,
        forceRefresh: Boolean = false
    ): Boolean {
        val groupId = group.id

        val existing = dbSemaphore.withPermit {
            dbQuery {
                RecentReleaseTable
                    .select(RecentReleaseTable.lastUpdate, RecentReleaseTable.releaseDate)
                    .where { RecentReleaseTable.releaseId eq groupId }
                    .singleOrNull()
            }
        }
        if (existing != null && !forceRefresh) {
            val lastUpdate = existing[RecentReleaseTable.lastUpdate]
            val storedReleaseDate = existing[RecentReleaseTable.releaseDate]
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val withinRefreshWindow = storedReleaseDate != null &&
                    nowMs >= storedReleaseDate &&
                    nowMs < storedReleaseDate + RELEASE_REFRESH_WINDOW.inWholeMilliseconds
            val cooldownPassed = lastUpdate == null ||
                    (nowMs - lastUpdate) >= REFRESH_COOLDOWN.inWholeMilliseconds
            if (!(withinRefreshWindow && cooldownPassed)) return false
        }
        val isRefresh = existing != null

        val groupReleases = mbReleases.filter { it.releaseGroup?.id == group.id }
        val groupReleaseIds = (groupReleases.map { it.id } + group.id).toSet()

        logger.info("Processing release for $artistName: ${group.title}")

        val releaseDate = try {
            val dateParts = group.firstReleaseDate?.split("-") ?: emptyList()
            when (dateParts.size) {
                3 -> LocalDate.of(dateParts[0].toInt(), dateParts[1].toInt(), dateParts[2].toInt())
                2 -> LocalDate.of(dateParts[0].toInt(), dateParts[1].toInt(), 1)
                1 -> LocalDate.of(dateParts[0].toInt(), 1, 1)
                else -> null
            }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        } catch (_: Exception) {
            null
        }

        val mbReleasesForGroup =
            musicBrainzService.fetchReleasesByReleaseGroup(groupId, priority = HttpClientPriority.LOW)
        val allRelations =
            (group.relations ?: emptyList()) + mbReleasesForGroup.flatMap { it.relations ?: emptyList() }
        val relations = allRelations.mapNotNull { it.url?.resource }.distinct()
        val resolvedLinks = linkResolverService.batchResolve(relations, priority = HttpClientPriority.LOW)

        val finalLinks = (relations + resolvedLinks).distinct().toMutableList()

        val isSingle =
            group.primaryType?.lowercase() == "single" || group.primaryType == null ||
                    group.relations?.any { it.type == "single from" } == true

        val groupRecordings = if (isSingle) {
            musicBrainzService.fetchRecordingsByReleaseGroup(
                groupId,
                priority = HttpClientPriority.LOW
            )
        } else emptyList()
        val groupRecordingIds = groupRecordings.map { it.id }.toSet()

        val libraryAlbumId = albumMappings.entries.find { (mbId, _) ->
            groupReleaseIds.contains(mbId)
        }?.value

        val librarySongId = songMappings.entries.find { (mbId, _) ->
            groupReleaseIds.contains(mbId) || groupRecordingIds.contains(mbId)
        }?.value

        val albumNames = if (isSingle) {
            val recordingAlbums = groupRecordings
                .asSequence()
                .flatMap { it.releases ?: emptyList() }
                .mapNotNull { it.releaseGroup }
                .filter { it.primaryType?.lowercase() == "album" }
                .map { it.title }
                .toList()

            val relationAlbums = group.relations?.filter { it.type == "single from" }
                ?.mapNotNull { it.releaseGroup?.title } ?: emptyList()

            (recordingAlbums + relationAlbums).distinct()
        } else emptyList()

        if (finalLinks.none { it.contains("apple.com") || it.contains("itunes.apple.com") }) {
            val searchQueries = mutableListOf("$artistName ${group.title.cleanTitle()}")
            if (isSingle) {
                albumNames.forEach { searchQueries.add("$artistName $it") }
            }

            var matchedAlbum: IMetadataService.Album? = null
            for (query in searchQueries.distinct()) {
                val searchedApple = appleMusicService.searchAlbums(
                    query,
                    25,
                    includeTracks = isSingle,
                    priority = HttpClientPriority.LOW
                )
                matchedAlbum = searchedApple.firstOrNull { album ->
                    val cleanGroupTitle = group.title.cleanTitle()
                    val titleMatches =
                        album.title.cleanTitle().removeSuffix("- Single").trim()
                            .equals(cleanGroupTitle, ignoreCase = true) ||
                                album.additionalTitles.any {
                                    it.cleanTitle().removeSuffix("- Single").trim()
                                        .equals(cleanGroupTitle, ignoreCase = true)
                                } ||
                                albumNames.any { albumName ->
                                    album.title.cleanTitle()
                                        .equals(albumName.cleanTitle(), ignoreCase = true) ||
                                            album.additionalTitles.any {
                                                it.cleanTitle()
                                                    .equals(albumName.cleanTitle(), ignoreCase = true)
                                            }
                                }

                    titleMatches && album.artists.any { it.equals(artistName, ignoreCase = true) }
                }
                if (matchedAlbum != null) break
            }

            if (matchedAlbum != null) {
                val appleUrl = "https://music.apple.com/album/${matchedAlbum.id}"
                finalLinks.add(appleUrl)
                finalLinks.addAll(
                    linkResolverService.resolvePlatformLinks(
                        appleUrl,
                        priority = HttpClientPriority.LOW
                    )
                )
            } else {
                logger.info("AppleMusic search returned no results for \"$artistName ${group.title}\" or related albums.")
            }
        }

        if (finalLinks.none { it.contains("tidal.com") }) {
            val searchQueries = mutableListOf("\"$artistName\" \"${group.title.cleanTitle()}\"")
            if (isSingle) {
                albumNames.forEach { searchQueries.add("\"$artistName\" \"$it\"") }
            }

            var matchedAlbum: IMetadataService.Album? = null
            for (query in searchQueries.distinct()) {
                val searchedTidal = tidalService.searchAlbums(
                    query,
                    15,
                    includeTracks = isSingle,
                    priority = HttpClientPriority.LOW
                )
                matchedAlbum = searchedTidal.firstOrNull { album ->
                    val cleanGroupTitle = group.title.cleanTitle()
                    val titleMatches = album.title.cleanTitle().equals(cleanGroupTitle, ignoreCase = true) ||
                            albumNames.any { albumName ->
                                album.title.cleanTitle().equals(albumName.cleanTitle(), ignoreCase = true)
                            }

                    titleMatches && album.artists.any { it.equals(artistName, ignoreCase = true) }
                }
                if (matchedAlbum != null) break
            }

            if (matchedAlbum != null) {
                val tidalUrl = "https://tidal.com/album/${matchedAlbum.id}"
                finalLinks.add(tidalUrl)
                finalLinks.addAll(
                    linkResolverService.resolvePlatformLinks(
                        tidalUrl,
                        priority = HttpClientPriority.LOW
                    )
                )
            } else {
                logger.info("Tidal search returned no results for \"$artistName ${group.title}\" or related albums.")
            }
        }

        val distinctLinks = finalLinks.distinct()

        val fetchedImageId = fetchReleaseGroupImage(group.id)
        val nowMs = Clock.System.now().toEpochMilliseconds()

        dbSemaphore.withPermit {
            musicBrainzCacheService.updateReleaseGroupCache(group)

            dbQuery {
                RecentReleaseTable.upsert(RecentReleaseTable.releaseId) {
                    it[releaseId] = groupId
                    it[RecentReleaseTable.artistId] = artistId
                    it[RecentReleaseTable.artistName] = artistName
                    it[title] = group.title
                    it[RecentReleaseTable.releaseDate] = releaseDate

                    val determinedType =
                        if (isSingle) ReleaseType.Single else ReleaseType.fromString(group.primaryType)
                    it[type] = determinedType
                    if (fetchedImageId != null || !isRefresh) {
                        it[RecentReleaseTable.imageId] = fetchedImageId
                    }
                    if (isRefresh) {
                        it[RecentReleaseTable.lastImageFetch] = nowMs
                    }
                    it[RecentReleaseTable.links] =
                        ApplicationScope.json.encodeToString(distinctLinks)
                    it[RecentReleaseTable.albumId] = libraryAlbumId
                    it[RecentReleaseTable.songId] = librarySongId
                    it[RecentReleaseTable.lastUpdate] = nowMs
                }

                if (isRefresh) {
                    RecentReleaseProviderTable.deleteWhere { RecentReleaseProviderTable.releaseId eq groupId }
                }

                distinctLinks.forEach { url ->
                    val parser = ParserFactory.getParser(url)
                    val parsed = parser?.parse(url)
                    val provider = parser?.name ?: "unknown"
                    val externalId = parsed?.first ?: url

                    RecentReleaseProviderTable.upsert(
                        RecentReleaseProviderTable.releaseId,
                        RecentReleaseProviderTable.provider,
                        RecentReleaseProviderTable.externalId
                    ) {
                        it[RecentReleaseProviderTable.releaseId] = groupId
                        it[RecentReleaseProviderTable.provider] = provider
                        it[RecentReleaseProviderTable.externalId] = externalId
                        it[RecentReleaseProviderTable.type] = parsed?.second?.value
                        it[RecentReleaseProviderTable.rawUrl] = url
                    }
                }
            }
        }
        return true
    }

    internal suspend fun fetchReleaseGroupImage(releaseGroupId: UUID): UUID? {
        val imageUrl = "https://coverartarchive.org/release-group/$releaseGroupId/front"
        val imageBytes = ApiClient.instance.safeGet<ByteArray>(imageUrl) ?: return null
        
        return imageService.createBatch(
            listOf(
                InsertableImage(
                    data = imageBytes,
                    imageHash = imageBytes.sha256(),
                    origin = imageUrl
                )
            )
        ).values.firstOrNull()
    }
}
