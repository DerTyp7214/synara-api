package dev.dertyp.services.import

import dev.dertyp.core.*
import dev.dertyp.data.*
import dev.dertyp.plugins.IImporter
import dev.dertyp.services.Service
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.*
import dev.dertyp.services.release.AppleMusicReleaseService
import dev.dertyp.utils.Barcodes
import dev.dertyp.utils.parsers.ParserFactory
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class UpcomingReleaseImportService(
    private val importService: ImportService,
    private val songService: SongService,
    private val musicBrainzService: IMusicBrainzService,
    private val appleMusicReleaseService: AppleMusicReleaseService,
    private val environment: ApplicationEnvironment,
) : Service() {

    sealed interface Source {
        data class Apple(val id: String) : Source
        data class MbRelease(val id: UUID) : Source
        data class MbReleaseGroup(val id: UUID) : Source
    }

    data class UpcomingRelease(
        val url: String,
        val source: Source,
        val title: String,
        val artists: List<String>,
        val releaseDate: LocalDate?,
        val trackCount: Int
    )

    data class Plan(
        val album: IMetadataService.Album,
        val missing: List<String>
    )

    private data class SourceTrack(
        val title: String,
        val artists: List<String>,
        val trackNumber: Int?,
        val discNumber: Int?,
        val isrcs: List<String>
    )

    private data class LookupOutcome(
        val track: IMetadataService.Track?,
        val missing: String?
    )

    private val appleMusicService: AppleMusicService
        get() = MetadataService.getMetadataService(
            IMetadataService.MetadataType.appleMusic,
            environment
        ) as AppleMusicService

    private val tidalService: MetadataService
        get() = MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, environment)

    companion object {
        private const val APPLE_PREFIX = "appleMusic:"

        private val MB_RELEASE_GROUP = Regex("/release-group/([0-9a-fA-F-]{36})")
        private val MB_RELEASE = Regex("/release/([0-9a-fA-F-]{36})")

        private const val LOOKUP_PARALLELISM = 4
        private val LOOKUP_TIMEOUT = 30.seconds
    }

    suspend fun detect(url: String, importer: IImporter): UpcomingRelease? {
        if (importer.metadataType != IMetadataService.MetadataType.tidal) return null

        val parser = ParserFactory.getParser(url) ?: return null
        return when (parser.name) {
            "apple" -> detectApple(url, parser.parse(url))
            "musicbrainz" -> detectMusicBrainz(url)
            else -> null
        }
    }

    suspend fun resolve(release: UpcomingRelease): Plan {
        val apple = appleMusicService
        val source = release.source

        var upc: String? = null
        var appleImages: List<IMetadataService.Image> = emptyList()
        var appleTracks: List<SourceTrack> = emptyList()

        if (source is Source.Apple) {
            appleTracks = apple.getAlbumTracks("$APPLE_PREFIX${source.id}", HttpClientPriority.HIGH)
                .toList()
                .map { it.toSourceTrack() }

            if (apple.catalogEnabled) {
                val catalogAlbum = apple.getCatalogAlbumsByIds(listOf(source.id), HttpClientPriority.HIGH).firstOrNull()
                upc = catalogAlbum?.upc?.takeIf { it.isNotBlank() }
                appleImages = listOfNotNull(catalogAlbum?.image)
            } else {
                appleImages = apple.getAlbumsByIds(listOf(source.id), HttpClientPriority.HIGH)
                    .firstOrNull()?.images.orEmpty()
            }
        }

        val sourceIsrcs = appleTracks.flatMap { it.isrcs }.map { normalizeIsrc(it) }.toSet()
        val candidates = musicBrainzCandidates(release, upc)

        var selected = pickRelease(candidates, upc, sourceIsrcs)
        if (selected != null && selected.media.orEmpty().all { it.tracks.isNullOrEmpty() }) {
            selected = musicBrainzService.getRelease(selected.id) ?: selected
        }

        val mbTracks = selected?.let { mbTracks(it) }.orEmpty()
        val merged = if (source is Source.Apple) mergeTracks(appleTracks, mbTracks) else mbTracks

        val barcode = selected?.barcode?.takeIf { it.isNotBlank() } ?: upc
        val images = if (source is Source.Apple) {
            appleImages
        } else if (apple.catalogEnabled && barcode != null) {
            listOfNotNull(apple.getCatalogAlbumsByUpc(barcode, HttpClientPriority.HIGH).firstOrNull()?.image)
        } else {
            emptyList()
        }

        val album = IMetadataService.Album(
            id = selected?.id?.toString() ?: fallbackAlbumId(source),
            title = selected?.title?.takeIf { it.isNotBlank() } ?: release.title,
            artists = selected?.artistCredit
                ?.takeIf { it.isNotEmpty() }
                ?.let { listOf(joinArtistCredit(it)) }
                ?: release.artists,
            trackCount = selected?.let { trackTotal(it).takeIf { count -> count > 0 } } ?: release.trackCount,
            releaseDate = periodStart(selected?.date) ?: release.releaseDate,
            images = images,
            barcode = barcode
        )

        val tidal = tidalService
        val semaphore = Semaphore(LOOKUP_PARALLELISM)

        val outcomes = coroutineScope {
            merged.map { track ->
                async { lookupOnTidal(tidal, semaphore, track, album) }
            }.awaitAll()
        }

        val missing = mutableListOf<String>()
        outcomes.forEach { outcome ->
            outcome.missing?.let {
                missing += it
                logger.info("Skipping $it")
            }
        }

        val resolved = outcomes.mapNotNull { it.track }.distinctBy { it.id }
        return Plan(album.copy(tracks = resolved.asFlow()), missing)
    }

    suspend fun submit(plan: Plan, importer: IImporter, user: User): Int {
        if (plan.missing.isNotEmpty()) {
            logger.info("Skipping ${plan.missing.size} tracks not yet on Tidal: ${plan.missing.joinToString(", ")}")
        }

        var queued = 0
        plan.album.tracks
            .filterExisting(
                songService,
                user,
                chunkSize = 20,
                deduplicateByIsrc = plan.album.barcode != null,
                isrcAlbumBarcode = plan.album.barcode
            )
            .collect { chunk ->
                if (chunk.isEmpty()) return@collect
                importService.addToQueue(
                    UrlImportQueueEntry(
                        urls = chunk.map { "https://tidal.com/track/${it.id}" }.toMutableList(),
                        ids = chunk.map { it.id },
                        byUser = user.id,
                        maxRetries = chunk.size,
                        type = Type.SONG,
                        importer = ImportBackend(importer.id),
                        metadata = plan.album
                    )
                )
                queued += chunk.size
            }
        return queued
    }

    private suspend fun detectApple(url: String, parsed: Pair<String, Type?>?): UpcomingRelease? {
        if (parsed == null) return null
        if (parsed.second != null && parsed.second != Type.ALBUM) return null

        val id = parsed.first.removePrefix(APPLE_PREFIX)
        if (id.isBlank()) return null

        val apple = appleMusicService
        val today = LocalDate.now()

        if (apple.catalogEnabled) {
            val album = apple.getCatalogAlbumsByIds(listOf(id), HttpClientPriority.HIGH).firstOrNull() ?: return null
            val upcoming = (album.releaseDate != null && album.releaseDate.isAfter(today)) || !album.isComplete
            if (!upcoming) return null
            return UpcomingRelease(
                url = url,
                source = Source.Apple(id),
                title = album.title,
                artists = listOf(album.artistName),
                releaseDate = album.releaseDate,
                trackCount = album.trackCount
            )
        }

        val album = apple.getAlbumsByIds(listOf(id), HttpClientPriority.HIGH).firstOrNull() ?: return null
        val date = album.releaseDate ?: return null
        if (!date.isAfter(today)) return null
        return UpcomingRelease(
            url = url,
            source = Source.Apple(id),
            title = album.title,
            artists = album.artists,
            releaseDate = date,
            trackCount = album.trackCount
        )
    }

    private suspend fun detectMusicBrainz(url: String): UpcomingRelease? {
        MB_RELEASE_GROUP.find(url)?.groupValues?.get(1)?.toUuidOrNull()?.let { groupId ->
            val release = pickRelease(musicBrainzService.getReleasesByReleaseGroup(groupId), null, emptySet())
                ?: return null
            return upcomingFromMusicBrainz(url, Source.MbReleaseGroup(groupId), release)
        }

        MB_RELEASE.find(url)?.groupValues?.get(1)?.toUuidOrNull()?.let { releaseId ->
            val release = musicBrainzService.getRelease(releaseId) ?: return null
            return upcomingFromMusicBrainz(url, Source.MbRelease(releaseId), release)
        }

        return null
    }

    private fun upcomingFromMusicBrainz(
        url: String,
        source: Source,
        release: MusicBrainzRelease
    ): UpcomingRelease? {
        val end = periodEnd(release.date)
        if (end != null && !end.isAfter(LocalDate.now())) return null

        return UpcomingRelease(
            url = url,
            source = source,
            title = release.title.orEmpty(),
            artists = release.artistCredit.orEmpty().mapNotNull { it.name ?: it.artist?.name },
            releaseDate = periodStart(release.date),
            trackCount = trackTotal(release)
        )
    }

    private suspend fun musicBrainzCandidates(
        release: UpcomingRelease,
        upc: String?
    ): List<MusicBrainzRelease> = when (val source = release.source) {
        is Source.Apple -> {
            val groupId = appleMusicReleaseService.releaseGroupIdFor(source.id)
            val byGroup = groupId?.let { musicBrainzService.getReleasesByReleaseGroup(it) }.orEmpty()
            if (byGroup.isNotEmpty()) {
                byGroup
            } else if (upc != null) {
                listOfNotNull(musicBrainzService.searchReleaseByBarcode(upc, release.artists))
            } else {
                emptyList()
            }
        }

        is Source.MbReleaseGroup -> musicBrainzService.getReleasesByReleaseGroup(source.id)
        is Source.MbRelease -> listOfNotNull(musicBrainzService.getRelease(source.id))
    }

    private suspend fun lookupOnTidal(
        tidal: MetadataService,
        semaphore: Semaphore,
        track: SourceTrack,
        album: IMetadataService.Album
    ): LookupOutcome {
        if (track.isrcs.isEmpty()) return LookupOutcome(null, "${track.title} (no ISRC)")

        var failed = false
        for (isrc in track.isrcs) {
            val outcome = semaphore.withPermit {
                withTimeoutOrNull(LOOKUP_TIMEOUT) {
                    runCatching { tidal.getTrackByIsrc(isrc, HttpClientPriority.HIGH) }
                }
            }

            when {
                outcome == null -> failed = true
                outcome.isFailure -> {
                    failed = true
                    logger.info("Tidal ISRC lookup failed for ${track.title} ($isrc)")
                }

                else -> {
                    val hit = outcome.getOrNull()
                    if (hit != null) {
                        if (hit.albumTitle != null && hit.albumTitle != album.title) {
                            logger.info(
                                "Tidal track ${hit.id} for ${track.title} comes from \"${hit.albumTitle}\" " +
                                        "instead of \"${album.title}\""
                            )
                        }
                        return LookupOutcome(
                            IMetadataService.Track(
                                id = hit.id,
                                title = track.title,
                                artists = track.artists,
                                duration = hit.duration,
                                trackNumber = track.trackNumber,
                                discNumber = track.discNumber,
                                images = emptyList(),
                                albumId = album.id,
                                albumTitle = album.title,
                                isrc = isrc
                            ),
                            null
                        )
                    }
                }
            }
        }

        return LookupOutcome(null, "${track.title} (${if (failed) "lookup failed" else "not on Tidal yet"})")
    }

    private fun mergeTracks(sourceTracks: List<SourceTrack>, mbTracks: List<SourceTrack>): List<SourceTrack> {
        if (mbTracks.isEmpty()) return sourceTracks

        val byIsrc = mutableMapOf<String, SourceTrack>()
        val byTitle = mutableMapOf<String, SourceTrack>()
        mbTracks.forEach { mb ->
            mb.isrcs.forEach { byIsrc.putIfAbsent(normalizeIsrc(it), mb) }
            byTitle.putIfAbsent(mb.title.cleanTitle().lowercase(), mb)
        }

        return sourceTracks.map { track ->
            val match = track.isrcs.firstNotNullOfOrNull { byIsrc[normalizeIsrc(it)] }
                ?: byTitle[track.title.cleanTitle().lowercase()]
                ?: return@map track

            SourceTrack(
                title = match.title.takeIf { it.isNotBlank() } ?: track.title,
                artists = match.artists.takeIf { it.isNotEmpty() } ?: track.artists,
                trackNumber = match.trackNumber ?: track.trackNumber,
                discNumber = match.discNumber ?: track.discNumber,
                isrcs = (track.isrcs + match.isrcs).distinctBy { normalizeIsrc(it) }
            )
        }
    }

    private fun mbTracks(release: MusicBrainzRelease): List<SourceTrack> =
        release.media.orEmpty().flatMapIndexed { discIndex, media ->
            media.tracks.orEmpty().map { track ->
                SourceTrack(
                    title = track.title ?: track.recording?.title.orEmpty(),
                    artists = track.recording?.artistCredit.orEmpty().mapNotNull { it.name ?: it.artist?.name },
                    trackNumber = track.position,
                    discNumber = discIndex + 1,
                    isrcs = track.recording?.isrcs.orEmpty().filter { it.isNotBlank() }
                )
            }
        }

    private fun pickRelease(
        candidates: List<MusicBrainzRelease>,
        upc: String?,
        sourceIsrcs: Set<String>
    ): MusicBrainzRelease? {
        val normalizedUpc = Barcodes.normalize(upc)
        return candidates.maxWithOrNull(
            compareBy<MusicBrainzRelease> {
                if (normalizedUpc != null && Barcodes.normalize(it.barcode) == normalizedUpc) 1 else 0
            }
                .thenBy { isrcOverlap(it, sourceIsrcs) }
                .thenBy { if (it.status == "Official") 1 else 0 }
                .thenBy { trackTotal(it) }
                .thenByDescending { periodStart(it.date) ?: LocalDate.MAX }
        )
    }

    private fun isrcOverlap(release: MusicBrainzRelease, sourceIsrcs: Set<String>): Int {
        if (sourceIsrcs.isEmpty()) return 0
        return release.media.orEmpty().sumOf { media ->
            media.tracks.orEmpty().count { track ->
                track.recording?.isrcs.orEmpty().any { normalizeIsrc(it) in sourceIsrcs }
            }
        }
    }

    private fun trackTotal(release: MusicBrainzRelease): Int =
        release.media.orEmpty().sumOf { it.trackCount ?: it.tracks.orEmpty().size }

    private fun fallbackAlbumId(source: Source): String = when (source) {
        is Source.Apple -> "$APPLE_PREFIX${source.id}"
        is Source.MbRelease -> source.id.toString()
        is Source.MbReleaseGroup -> source.id.toString()
    }

    private fun joinArtistCredit(credits: List<MusicBrainzArtistCredit>): String =
        credits.mapIndexed { index, credit ->
            val name = credit.name ?: credit.artist?.name.orEmpty()
            val join = if (index == credits.lastIndex) "" else credit.joinphrase?.takeIf { it.isNotEmpty() } ?: ", "
            name + join
        }.joinToString("").trim()

    private fun IMetadataService.Track.toSourceTrack(): SourceTrack = SourceTrack(
        title = title,
        artists = artists,
        trackNumber = trackNumber,
        discNumber = discNumber,
        isrcs = listOfNotNull(isrc?.takeIf { it.isNotBlank() })
    )

    private fun normalizeIsrc(isrc: String): String = isrc.filter { it.isLetterOrDigit() }.uppercase()

    private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun periodStart(date: String?): LocalDate? {
        val raw = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            when {
                raw.length >= 10 -> LocalDate.parse(raw.substring(0, 10))
                raw.length == 7 -> LocalDate.parse("$raw-01")
                raw.length == 4 -> LocalDate.parse("$raw-01-01")
                else -> null
            }
        }.getOrNull()
    }

    private fun periodEnd(date: String?): LocalDate? {
        val raw = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            when {
                raw.length >= 10 -> LocalDate.parse(raw.substring(0, 10))
                raw.length == 7 -> YearMonth.parse(raw).atEndOfMonth()
                raw.length == 4 -> LocalDate.of(raw.toInt(), 12, 31)
                else -> null
            }
        }.getOrNull()
    }
}
