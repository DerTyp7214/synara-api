package dev.dertyp.services.schedule

import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import io.ktor.util.logging.KtorSimpleLogger
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MusicBrainzCacheWorker : KoinComponent {
    private val logger = KtorSimpleLogger("MusicBrainzCacheWorker")
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()

    private val isRunning = AtomicBoolean(false)

    suspend fun run(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Map<String, Int> {
        if (!isRunning.compareAndSet(false, true)) {
            logger.info("MusicBrainzCacheWorker is already running. Skipping this run.")
            return emptyMap()
        }

        var artistsUpdated = 0
        var recordingsUpdated = 0
        var releasesUpdated = 0
        var releaseGroupsUpdated = 0

        try {
            val oneMonthAgo = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
            logger.info("Starting MusicBrainzCacheWorker")
            onProgress(0.0, "Starting MusicBrainzCacheWorker")

            val totalArtists = dbQuery {
                MBArtistTable.selectAll().where { MBArtistTable.lastUpdate less oneMonthAgo }
                    .count()
            }
            val totalReleaseGroups = dbQuery {
                MBReleaseGroupTable.selectAll()
                    .where { MBReleaseGroupTable.lastUpdate less oneMonthAgo }.count()
            }
            val totalReleases = dbQuery {
                MBReleaseTable.selectAll().where { MBReleaseTable.lastUpdate less oneMonthAgo }
                    .count()
            }
            val totalRecordings = dbQuery {
                MBRecordingTable.selectAll().where { MBRecordingTable.lastUpdate less oneMonthAgo }
                    .count()
            }

            val total = totalArtists + totalReleaseGroups + totalReleases + totalRecordings

            val artistBasePercentage = .0
            val artistMaxPercentage = totalArtists.coerceAtLeast(1) / total.toDouble() * 100

            val releaseGroupBasePercentage = artistBasePercentage + artistMaxPercentage
            val releaseGroupMaxPercentage = totalReleaseGroups.coerceAtLeast(1) / total.toDouble() * 100

            val releaseBasePercentage = releaseGroupBasePercentage + releaseGroupMaxPercentage
            val releaseMaxPercentage = totalReleases.coerceAtLeast(1) / total.toDouble() * 100

            val recordingBasePercentage = releaseBasePercentage + releaseMaxPercentage
            val recordingMaxPercentage = totalRecordings.coerceAtLeast(1) / total.toDouble() * 100

            suspend fun progress(
                progress: Double,
                basePercentage: Double,
                maxPercentage: Double,
                message: String
            ) {
                onProgress(basePercentage + progress * maxPercentage, message)
            }

            logger.info("Updating $totalArtists stale artists in MusicBrainz cache")
            musicBrainzCacheService.staleArtistIdsFlow(oneMonthAgo).collect { id ->
                try {
                    musicBrainzService.fetchArtistById(id)?.let {
                        musicBrainzCacheService.updateArtistCache(it)
                        artistsUpdated++
                    }
                    progress(
                        progress = artistsUpdated.toDouble() / totalArtists.coerceAtLeast(1),
                        basePercentage = artistBasePercentage,
                        maxPercentage = artistMaxPercentage,
                        message = "Updating artists: $artistsUpdated/$totalArtists ($total)"
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update artist $id in cache: ${e.message}")
                }
            }

            logger.info("Updating $totalReleaseGroups stale release groups in MusicBrainz cache")
            musicBrainzCacheService.staleReleaseGroupIdsFlow(oneMonthAgo).collect { id ->
                try {
                    musicBrainzService.fetchReleaseGroupById(id)?.let {
                        musicBrainzCacheService.updateReleaseGroupCache(it)
                        releaseGroupsUpdated++
                    }
                    progress(
                        progress = releaseGroupsUpdated.toDouble() / totalReleaseGroups.coerceAtLeast(1),
                        basePercentage = releaseGroupBasePercentage,
                        maxPercentage = releaseGroupMaxPercentage,
                        message = "Updating release groups: $releaseGroupsUpdated/$totalReleaseGroups ($total)"
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update release group $id in cache: ${e.message}")
                }
            }

            logger.info("Updating $totalReleases stale releases in MusicBrainz cache")
            musicBrainzCacheService.staleReleaseIdsFlow(oneMonthAgo).collect { id ->
                try {
                    musicBrainzService.fetchReleaseById(id)?.let {
                        musicBrainzCacheService.updateReleaseCache(it)
                        releasesUpdated++
                    }
                    progress(
                        progress = releasesUpdated.toDouble() / totalReleases.coerceAtLeast(1),
                        basePercentage = releaseBasePercentage,
                        maxPercentage = releaseMaxPercentage,
                        message = "Updating releases: $releasesUpdated/$totalReleases ($total)"
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update release $id in cache: ${e.message}")
                }
            }

            logger.info("Updating $totalRecordings stale recordings in MusicBrainz cache")
            musicBrainzCacheService.staleRecordingIdsFlow(oneMonthAgo).collect { id ->
                try {
                    musicBrainzService.fetchRecordingById(id)?.let {
                        musicBrainzCacheService.updateRecordingCache(it)
                        recordingsUpdated++
                    }
                    progress(
                        progress = recordingsUpdated.toDouble() / totalRecordings.coerceAtLeast(1),
                        basePercentage = recordingBasePercentage,
                        maxPercentage = recordingMaxPercentage,
                        message = "Updating recordings: $recordingsUpdated/$totalRecordings ($total)"
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update recording $id in cache: ${e.message}")
                }
            }

            onProgress(100.0, "Finished updating MusicBrainz cache")
            logger.info("MusicBrainzCacheWorker finished. Updated $artistsUpdated artists, $releaseGroupsUpdated release groups, $releasesUpdated releases, $recordingsUpdated recordings.")
        } finally {
            isRunning.set(false)
        }

        return mapOf(
            "artistsUpdated" to artistsUpdated,
            "releaseGroupsUpdated" to releaseGroupsUpdated,
            "releasesUpdated" to releasesUpdated,
            "recordingsUpdated" to recordingsUpdated
        )
    }
}
