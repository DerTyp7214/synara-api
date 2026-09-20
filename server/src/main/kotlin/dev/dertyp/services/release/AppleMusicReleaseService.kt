package dev.dertyp.services.release

import dev.dertyp.ApiClient
import dev.dertyp.core.*
import dev.dertyp.data.InsertableImage
import dev.dertyp.data.ReleaseType
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.ImageService
import dev.dertyp.services.Service
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.*
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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class AppleMusicReleaseService(private val environment: ApplicationEnvironment) : Service() {
    private val imageService by inject<ImageService>()
    private val artistResolver by inject<AppleMusicArtistResolver>()

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val appleMusicService: AppleMusicService
        get() = MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService

    private data class ProcessResult(val stored: Int = 0, val upcoming: Int = 0, val matched: Int = 0)

    companion object {
        const val PROVIDER = "apple"

        private val LOOKBACK = 180.days
        private val UPCOMING_HORIZON = 365.days
        private val IMAGE_RETRY = 7.days

        fun providerReleaseId(provider: String, externalId: String): UUID =
            UUID.nameUUIDFromBytes("provider_release:$provider:$externalId".toByteArray())
    }

    suspend fun fetchFollowedArtistReleases(
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Any?> = coroutineScope {
        val apple = appleMusicService
        if (!apple.catalogEnabled) {
            onProgress(100.0, "Apple Music catalog is not configured")
            return@coroutineScope mapOf<String, Any?>("skipped" to true, "reason" to "catalog disabled")
        }

        val artists = dbQuery {
            FollowedArtistTable
                .innerJoin(ArtistTable, { FollowedArtistTable.artistId }, { ArtistTable.id })
                .select(FollowedArtistTable.artistId, ArtistTable.name)
                .map { it[FollowedArtistTable.artistId].value to it[ArtistTable.name] }
                .distinctBy { it.first }
        }

        val totalArtists = artists.size
        if (totalArtists == 0) {
            onProgress(100.0, "No followed artists")
            return@coroutineScope mapOf<String, Any?>(
                "artists" to 0,
                "stored" to 0,
                "upcoming" to 0,
                "matched" to 0,
                "skipped" to false
            )
        }

        onProgress(0.0, "Fetching Apple Music releases for $totalArtists followed artists")

        val dbSemaphore = Semaphore(1)
        val artistSemaphore = Semaphore(5)
        val progressMutex = Mutex()
        var processed = 0

        val results = artists.map { (artistId, artistName) ->
            async {
                artistSemaphore.withPermit {
                    val result = runCatching {
                        processArtist(artistId, artistName, apple, dbSemaphore, HttpClientPriority.LOW)
                    }.onFailure {
                        logger.error("Apple Music release fetch failed for artist $artistId", it)
                    }.getOrDefault(ProcessResult())

                    progressMutex.withLock {
                        processed++
                        onProgress(processed * 100.0 / totalArtists, "Checked $artistName")
                    }
                    result
                }
            }
        }.awaitAll()

        unlinkUnfollowedProviderReleaseImages()
        onProgress(100.0, "Finished fetching Apple Music releases")

        mapOf<String, Any?>(
            "artists" to totalArtists,
            "stored" to results.sumOf { it.stored },
            "upcoming" to results.sumOf { it.upcoming },
            "matched" to results.sumOf { it.matched },
            "skipped" to false
        )
    }

    suspend fun fetchArtistReleases(artistId: UUID): Int {
        val apple = appleMusicService
        if (!apple.catalogEnabled) return 0

        val artistName = dbQuery {
            ArtistTable.select(ArtistTable.name)
                .where { ArtistTable.id eq artistId }
                .singleOrNull()?.get(ArtistTable.name)
        } ?: return 0

        return processArtist(artistId, artistName, apple, Semaphore(1), HttpClientPriority.LOW).stored
    }

    fun fetchArtistReleasesAsync(artistId: UUID) {
        serviceScope.launch {
            runCatching { fetchArtistReleases(artistId) }
                .onFailure { logger.error("Apple Music release fetch failed for artist $artistId", it) }
        }
    }

    private suspend fun processArtist(
        artistId: UUID,
        artistName: String,
        apple: AppleMusicService,
        dbSemaphore: Semaphore,
        priority: HttpClientPriority
    ): ProcessResult {
        val appleArtistId = artistResolver.resolve(artistId, priority) ?: return ProcessResult()
        val catalogAlbums = apple.getArtistCatalogAlbums(appleArtistId, priority) ?: return ProcessResult()

        val nowMs = Clock.System.now().toEpochMilliseconds()
        val oldest = nowMs - LOOKBACK.inWholeMilliseconds
        val newest = nowMs + UPCOMING_HORIZON.inWholeMilliseconds

        val candidates = catalogAlbums.mapNotNull { album ->
            val date = album.releaseDate
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli()
                ?: return@mapNotNull null
            if (date < oldest || date > newest) null else album to date
        }
        if (candidates.isEmpty()) return ProcessResult()

        val barcodes = candidates.mapNotNull { it.first.upc }.filter { it.isNotBlank() }.distinct()

        val providerLinks = dbSemaphore.withPermit {
            dbQuery {
                RecentReleaseTable
                    .innerJoin(
                        RecentReleaseProviderTable,
                        { RecentReleaseTable.releaseId },
                        { RecentReleaseProviderTable.releaseId })
                    .select(RecentReleaseProviderTable.externalId, RecentReleaseTable.releaseId)
                    .where { RecentReleaseTable.artistId eq artistId }
                    .andWhere { RecentReleaseProviderTable.provider eq PROVIDER }
                    .associate { it[RecentReleaseProviderTable.externalId] to it[RecentReleaseTable.releaseId].value }
            }
        }

        val titleGroups = dbSemaphore.withPermit {
            dbQuery {
                RecentReleaseTable
                    .select(RecentReleaseTable.title, RecentReleaseTable.releaseId)
                    .where { RecentReleaseTable.artistId eq artistId }
                    .associate { normalizeTitle(it[RecentReleaseTable.title]) to it[RecentReleaseTable.releaseId].value }
            }
        }

        val barcodeGroups = if (barcodes.isEmpty()) emptyMap<String, UUID>() else dbSemaphore.withPermit {
            dbQuery {
                MBReleaseTable
                    .select(MBReleaseTable.barcode, MBReleaseTable.releaseGroupId)
                    .where { MBReleaseTable.barcode inList barcodes }
                    .andWhere { MBReleaseTable.releaseGroupId.isNotNull() }
                    .mapNotNull { row ->
                        val barcode = row[MBReleaseTable.barcode] ?: return@mapNotNull null
                        val group = row[MBReleaseTable.releaseGroupId]?.value ?: return@mapNotNull null
                        barcode to group
                    }
                    .toMap()
            }
        }

        val libraryAlbums = dbSemaphore.withPermit {
            dbQuery {
                val linked = AlbumProviderTable
                    .innerJoin(AlbumArtistTable, { AlbumProviderTable.albumId }, { AlbumArtistTable.albumId })
                    .select(AlbumProviderTable.externalId, AlbumProviderTable.albumId)
                    .where { AlbumArtistTable.artistId eq artistId }
                    .andWhere { AlbumProviderTable.provider eq PROVIDER }
                    .associate { it[AlbumProviderTable.externalId] to it[AlbumProviderTable.albumId].value }

                val imported = AlbumTable
                    .innerJoin(AlbumArtistTable, { AlbumTable.id }, { AlbumArtistTable.albumId })
                    .select(AlbumTable.id, AlbumTable.originalId)
                    .where { AlbumArtistTable.artistId eq artistId }
                    .andWhere { AlbumTable.originalId like "appleMusic:%" }
                    .mapNotNull { row ->
                        val original = row[AlbumTable.originalId] ?: return@mapNotNull null
                        original.removePrefix("appleMusic:") to row[AlbumTable.id].value
                    }
                    .toMap()

                imported + linked
            }
        }

        val librarySongs = dbSemaphore.withPermit {
            dbQuery {
                SongProviderTable
                    .innerJoin(SongArtistTable, { SongProviderTable.songId }, { SongArtistTable.songId })
                    .select(SongProviderTable.externalId, SongProviderTable.songId)
                    .where { SongArtistTable.artistId eq artistId }
                    .andWhere { SongProviderTable.provider eq PROVIDER }
                    .associate { it[SongProviderTable.externalId] to it[SongProviderTable.songId].value }
            }
        }

        var stored = 0
        var upcoming = 0
        var matched = 0

        for ((album, date) in candidates) {
            val rowId = providerReleaseId(PROVIDER, album.id)
            val existingLink = providerLinks[album.id]
            val matchedGroupId = existingLink
                ?: album.upc?.let { barcodeGroups[it] }
                ?: titleGroups[normalizeTitle(album.title)]
            val matchedAlbumId = libraryAlbums[album.id]
            val matchedSongId = if (matchedAlbumId == null && album.isSingle) librarySongs[album.id] else null
            val releaseType = when {
                album.title.trim().endsWith("- EP", ignoreCase = true) -> ReleaseType.EP
                album.isSingle -> ReleaseType.Single
                else -> ReleaseType.Album
            }
            val albumUrl = album.url
                ?: ParserFactory.toUrl(PROVIDER, album.id, Type.ALBUM)
                ?: ""
            val albumArtworkUrl = album.image?.url
            val albumArtistName = album.artistName.ifBlank { artistName }

            val existing = dbSemaphore.withPermit {
                dbQuery {
                    ProviderReleaseTable
                        .select(ProviderReleaseTable.imageId, ProviderReleaseTable.lastImageFetch)
                        .where { ProviderReleaseTable.id eq rowId }
                        .singleOrNull()
                        ?.let { it[ProviderReleaseTable.imageId]?.value to it[ProviderReleaseTable.lastImageFetch] }
                }
            }

            dbSemaphore.withPermit {
                dbQuery {
                    ProviderReleaseTable.upsert(
                        ProviderReleaseTable.id,
                        onUpdateExclude = listOf(
                            ProviderReleaseTable.imageId,
                            ProviderReleaseTable.lastImageFetch,
                            ProviderReleaseTable.addedAt
                        )
                    ) {
                        it[ProviderReleaseTable.id] = rowId
                        it[ProviderReleaseTable.provider] = PROVIDER
                        it[ProviderReleaseTable.externalId] = album.id
                        it[ProviderReleaseTable.artistId] = artistId
                        it[ProviderReleaseTable.artistName] = albumArtistName
                        it[ProviderReleaseTable.title] = album.title
                        it[ProviderReleaseTable.releaseDate] = date
                        it[ProviderReleaseTable.type] = releaseType
                        it[ProviderReleaseTable.single] = album.isSingle
                        it[ProviderReleaseTable.complete] = album.isComplete
                        it[ProviderReleaseTable.compilation] = album.isCompilation
                        it[ProviderReleaseTable.trackCount] = album.trackCount
                        it[ProviderReleaseTable.upc] = album.upc
                        it[ProviderReleaseTable.url] = albumUrl
                        it[ProviderReleaseTable.artworkUrl] = albumArtworkUrl
                        it[ProviderReleaseTable.releaseGroupId] = matchedGroupId
                        it[ProviderReleaseTable.albumId] = matchedAlbumId
                        it[ProviderReleaseTable.songId] = matchedSongId
                        it[ProviderReleaseTable.lastUpdate] = nowMs
                    }

                    if (matchedGroupId != null && existingLink == null && albumUrl.isNotBlank()) {
                        RecentReleaseProviderTable.upsert(
                            RecentReleaseProviderTable.releaseId,
                            RecentReleaseProviderTable.provider,
                            RecentReleaseProviderTable.externalId
                        ) {
                            it[RecentReleaseProviderTable.releaseId] = matchedGroupId
                            it[RecentReleaseProviderTable.provider] = PROVIDER
                            it[RecentReleaseProviderTable.externalId] = album.id
                            it[RecentReleaseProviderTable.type] = Type.ALBUM.value
                            it[RecentReleaseProviderTable.rawUrl] = albumUrl
                        }
                    }
                }
            }

            stored++
            if (date > nowMs) upcoming++
            if (matchedGroupId != null) matched++

            val storedImageId = existing?.first
            val lastImageFetch = existing?.second
            val imageDue = storedImageId == null &&
                    (lastImageFetch == null || nowMs - lastImageFetch >= IMAGE_RETRY.inWholeMilliseconds)

            if (albumArtworkUrl != null && imageDue) {
                val persistedImageId = runCatching { persistArtwork(albumArtworkUrl) }
                    .onFailure { logger.error("Failed to persist Apple Music artwork for ${album.id}", it) }
                    .getOrNull()

                dbSemaphore.withPermit {
                    dbQuery {
                        ProviderReleaseTable.update({ ProviderReleaseTable.id eq rowId }) {
                            if (persistedImageId != null) it[ProviderReleaseTable.imageId] = persistedImageId
                            it[ProviderReleaseTable.lastImageFetch] = nowMs
                        }
                    }
                }
            }
        }

        return ProcessResult(stored, upcoming, matched)
    }

    private suspend fun persistArtwork(artworkUrl: String): UUID? {
        val bytes = fetchArtworkBytes(artworkUrl) ?: return null
        return imageService.createBatch(
            listOf(
                InsertableImage(
                    data = bytes,
                    imageHash = bytes.sha256(),
                    origin = artworkUrl
                )
            )
        ).values.firstOrNull()
    }

    internal suspend fun fetchArtworkBytes(url: String): ByteArray? =
        ApiClient.instance.safeGet<ByteArray>(url)

    suspend fun unlinkUnfollowedProviderReleaseImages(): Int {
        val releaseIds = dbQuery {
            ProviderReleaseTable
                .select(ProviderReleaseTable.id)
                .where { ProviderReleaseTable.imageId.isNotNull() }
                .andWhere { ProviderReleaseTable.artistId notInSubQuery FollowedArtistTable.select(FollowedArtistTable.artistId) }
                .map { it[ProviderReleaseTable.id].value }
        }
        if (releaseIds.isEmpty()) return 0

        dbQuery {
            releaseIds.chunked(10000).forEach { chunk ->
                ProviderReleaseTable.update({ ProviderReleaseTable.id inList chunk }) {
                    it[ProviderReleaseTable.imageId] = null
                    it[ProviderReleaseTable.lastImageFetch] = null
                }
            }
        }
        logger.info("Unlinked ${releaseIds.size} provider release cover images of unfollowed artists")
        return releaseIds.size
    }

    private fun normalizeTitle(title: String): String {
        var value = title.cleanTitle().trim()
        listOf("- Single", "- EP").forEach { suffix ->
            if (value.endsWith(suffix, ignoreCase = true)) value = value.dropLast(suffix.length).trim()
        }
        return value.lowercase()
    }
}
