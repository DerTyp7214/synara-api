package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.*
import dev.dertyp.data.ArtistType
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.ReleaseType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.platformDateFromEpochMilliseconds
import dev.dertyp.services.metadata.*
import dev.dertyp.services.models.FollowedArtist
import dev.dertyp.services.models.RecentRelease
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Clock

class ReleaseService(private val environment: ApplicationEnvironment) : Service() {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val artistService by inject<ArtistService>()
    private val imageService by inject<ImageService>()

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

        val data = RecentReleaseTable
            .leftJoin(ImageTable, onColumn = { RecentReleaseTable.imageId }, otherColumn = { ImageTable.id })
            .selectAll()
            .where { (RecentReleaseTable.artistId inList followedArtistIds) and (RecentReleaseTable.albumId.isNull()) and (RecentReleaseTable.songId.isNull()) and (RecentReleaseTable.releaseDate.isNotNull()) }
            .orderBy(RecentReleaseTable.releaseDate to SortOrder.DESC)
            .limit(pageSize)
            .offset((page * pageSize).toLong())
            .map {
                RecentRelease(
                    releaseId = it[RecentReleaseTable.releaseId].value,
                    artistId = it[RecentReleaseTable.artistId].value,
                    artistName = it[RecentReleaseTable.artistName],
                    title = it[RecentReleaseTable.title],
                    releaseDate = it[RecentReleaseTable.releaseDate]?.let { ms -> platformDateFromEpochMilliseconds(ms) },
                    type = it[RecentReleaseTable.type],
                    imageId = it[RecentReleaseTable.imageId]?.value,
                    blurHash = it.getOrNull(ImageTable.blurHash),
                    links = try {
                        ApplicationScope.json.decodeFromString<List<String>>(it[RecentReleaseTable.links])
                    } catch (_: Exception) {
                        emptyList()
                    },
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

    suspend fun fetchNewReleases(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> = coroutineScope {
        val tidalService = MetadataService.getMetadataService(
            IMetadataService.MetadataType.tidal,
            environment
        ) as TidalService

        val appleMusicService = MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService

        val artists = dbQuery {
            FollowedArtistTable.innerJoin(ArtistMusicBrainzTable, onColumn = { FollowedArtistTable.artistId }, otherColumn = { ArtistMusicBrainzTable.artistId })
                .selectAll()
                .map { Pair(it[ArtistMusicBrainzTable.artistId].value, it[ArtistMusicBrainzTable.musicBrainzId]) }
                .distinctBy { it.second }
        }

        logger.info("Starting to fetch new releases for ${artists.size} artists")
        onProgress(0.0, "Starting to fetch new releases for ${artists.size} artists")

        val totalArtists = artists.size
        var currentProgress = 0.0
        val progressMutex = Mutex()
        val dbSemaphore = Semaphore(1)

        val results = artists.map { (artistId, mbId) ->
            async {
                val artistWeight = 1.0 / totalArtists
                var progressAddedForArtist = 0.0
                var artistName: String? = null
                try {
                    if (mbId == null) return@async null
                    artistName = dbSemaphore.withPermit {
                        dbQuery {
                            ArtistTable.selectAll().where { ArtistTable.id eq artistId }.singleOrNull()?.get(ArtistTable.name)
                        }
                    } ?: return@async null

                    logger.info("Fetching releases for artist: $artistName")

                    val mbReleases = musicBrainzService.fetchReleasesByArtist(mbId.value, priority = HttpClientPriority.LOW)

                    val albumMappings = dbSemaphore.withPermit {
                        dbQuery {
                            AlbumMusicBrainzTable.innerJoin(AlbumArtistTable, onColumn = { AlbumMusicBrainzTable.albumId }, otherColumn = { AlbumArtistTable.albumId })
                                .selectAll()
                                .where { AlbumArtistTable.artistId eq artistId }
                                .mapNotNull { it[AlbumMusicBrainzTable.musicBrainzId]?.let { mbId -> mbId.value to it[AlbumMusicBrainzTable.albumId].value } }
                                .toMap()
                        }
                    }

                    val songMappings = dbSemaphore.withPermit {
                        dbQuery {
                            SongMusicBrainzTable.innerJoin(SongArtistTable, onColumn = { SongMusicBrainzTable.songId }, otherColumn = { SongArtistTable.songId })
                                .selectAll()
                                .where { SongArtistTable.artistId eq artistId }
                                .mapNotNull { it[SongMusicBrainzTable.musicBrainzId]?.let { mbId -> mbId.value to it[SongMusicBrainzTable.songId].value } }
                                .toMap()
                        }
                    }

                    val groups = musicBrainzService.fetchReleaseGroups(mbId.value, priority = HttpClientPriority.LOW)
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
                                val groupId = group.id
                                val alreadyExists = dbSemaphore.withPermit {
                                    dbQuery {
                                        RecentReleaseTable.selectAll()
                                            .where { RecentReleaseTable.releaseId eq groupId }
                                            .any()
                                    }
                                }
                                if (alreadyExists) return@async false

                                val groupReleases = mbReleases.filter { it.releaseGroup?.id == group.id }
                                val groupReleaseIds = (groupReleases.map { it.id } + group.id).toSet()

                                logger.info("New release found for $artistName: ${group.title}")

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

                                val mbReleasesForGroup = musicBrainzService.fetchReleasesByReleaseGroup(groupId, priority = HttpClientPriority.LOW)
                                val allRelations = (group.relations ?: emptyList()) + mbReleasesForGroup.flatMap { it.relations ?: emptyList() }
                                val relations = allRelations.mapNotNull { it.url?.resource }.distinct()
                                val odesliLinks = mutableListOf<String>()
                                for (link in relations) {
                                    if (link.contains("spotify.com") || link.contains("itunes.apple.com") || link.contains("apple.com") ||
                                        link.contains("youtube.com") || link.contains("amazon.com") || link.contains("deezer.com") ||
                                        link.contains("tidal.com") || link.contains("bandcamp.com")) {
                                        val resolved = resolvePlatformLinks(link, priority = HttpClientPriority.LOW)
                                        if (resolved.isNotEmpty()) {
                                            odesliLinks.addAll(resolved)
                                            break
                                        }
                                    }
                                }

                                val finalLinks = (relations + odesliLinks).distinct().toMutableList()

                                val isSingle = group.primaryType?.lowercase() == "single" || group.primaryType == null ||
                                        group.relations?.any { it.type == "single from" } == true

                                val groupRecordings = if (isSingle) {
                                    musicBrainzService.fetchRecordingsByReleaseGroup(groupId, priority = HttpClientPriority.LOW)
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
                                            val titleMatches = album.title.cleanTitle().removeSuffix("- Single").trim().equals(cleanGroupTitle, ignoreCase = true) ||
                                                    album.additionalTitles.any { it.cleanTitle().removeSuffix("- Single").trim().equals(cleanGroupTitle, ignoreCase = true) } ||
                                                    albumNames.any { albumName ->
                                                        album.title.cleanTitle().equals(albumName.cleanTitle(), ignoreCase = true) ||
                                                                album.additionalTitles.any { it.cleanTitle().equals(albumName.cleanTitle(), ignoreCase = true) }
                                                    }

                                            titleMatches && album.artists.any { it.equals(artistName, ignoreCase = true) }
                                        }
                                        if (matchedAlbum != null) break
                                    }

                                    if (matchedAlbum != null) {
                                        val appleUrl = "https://music.apple.com/album/${matchedAlbum.id}"
                                        finalLinks.add(appleUrl)
                                        finalLinks.addAll(resolvePlatformLinks(appleUrl, priority = HttpClientPriority.LOW))
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
                                        finalLinks.addAll(resolvePlatformLinks(tidalUrl, priority = HttpClientPriority.LOW))
                                    } else {
                                        logger.info("Tidal search returned no results for \"$artistName ${group.title}\" or related albums.")
                                    }
                                }

                                val distinctLinks = finalLinks.distinct()

                                val imageId = fetchReleaseGroupImage(group.id)

                                dbSemaphore.withPermit {
                                    musicBrainzCacheService.updateReleaseGroupCache(group)

                                    dbQuery {
                                        RecentReleaseTable.upsert(RecentReleaseTable.releaseId) {
                                            it[releaseId] = groupId
                                            it[RecentReleaseTable.artistId] = artistId
                                            it[RecentReleaseTable.artistName] = artistName
                                            it[title] = group.title
                                            it[RecentReleaseTable.releaseDate] = releaseDate

                                            val determinedType = if (isSingle) ReleaseType.Single else ReleaseType.fromString(group.primaryType)
                                            it[type] = determinedType
                                            it[RecentReleaseTable.imageId] = imageId
                                            it[RecentReleaseTable.links] = ApplicationScope.json.encodeToString(distinctLinks)
                                            it[RecentReleaseTable.albumId] = libraryAlbumId
                                            it[RecentReleaseTable.songId] = librarySongId
                                        }
                                    }
                                }
                                true
                            } catch (e: Exception) {
                                logger.error("Failed to process group ${group.id}", e)
                                false
                            } finally {
                                progressMutex.withLock {
                                    currentProgress += groupWeight
                                    progressAddedForArtist += groupWeight
                                }
                                dbSemaphore.withPermit {
                                    onProgress(currentProgress * 100.0, "Processed $artistName: ${group.title}")
                                }
                            }
                        }
                    }.awaitAll().count { it }

                    dbSemaphore.withPermit {
                        dbQuery {
                            FollowedArtistTable.update({ FollowedArtistTable.artistId eq artistId }) {
                                it[lastCheck] = Clock.System.now().toEpochMilliseconds()
                            }
                        }
                    }

                    if (newReleasesCount > 0) artistName to newReleasesCount else null
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
        }.awaitAll().filterNotNull().toMap()

        onProgress(100.0, "Finished fetching new releases")

        logger.info("Finished fetching new releases. Found new releases for ${results.size} artists.")
        results
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

    internal suspend fun resolvePlatformLinks(
        platformUrl: String,
        priority: HttpClientPriority = HttpClientPriority.NORMAL
    ): List<String> {
        return try {
            val response = ApiClient.queueInstance.enqueue("https://api.song.link/v1-alpha.1/links", priority = priority) {
                parameter("url", platformUrl)
                parameter("userCountry", "US")
            }
            if (response.status.value in 200..299) {
                val body = response.body<OdesliResponse>()
                body.linksByPlatform.values.map { it.url }
            } else emptyList()
        } catch (e: Exception) {
            logger.error("Error resolving platform links via Odesli for $platformUrl", e)
            emptyList()
        }
    }

    @Serializable
    private data class OdesliResponse(
        val linksByPlatform: Map<String, OdesliPlatformLink>
    )

    @Serializable
    private data class OdesliPlatformLink(
        val url: String
    )
}
