package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.koin.core.component.inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class MusicBrainzCacheWorker : Worker("MusicBrainzCacheWorker") {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        var artistsUpdated = 0
        var recordingsUpdated = 0
        var releasesUpdated = 0
        var releaseGroupsUpdated = 0

        val oneMonthAgo = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
        fun getRetryTimestamp(): Long = oneMonthAgo + (2..5).random().days.inWholeMilliseconds

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

        val total = (totalArtists + totalReleaseGroups + totalReleases + totalRecordings).toDouble()

        val artistBasePercentage = .0
        val artistMaxPercentage = if (total > 0) totalArtists / total * 100 else 0.0

        val releaseGroupBasePercentage = artistBasePercentage + artistMaxPercentage
        val releaseGroupMaxPercentage = if (total > 0) totalReleaseGroups / total * 100 else 0.0

        val releaseBasePercentage = releaseGroupBasePercentage + releaseGroupMaxPercentage
        val releaseMaxPercentage = if (total > 0) totalReleases / total * 100 else 0.0

        val recordingBasePercentage = releaseBasePercentage + releaseMaxPercentage
        val recordingMaxPercentage = if (total > 0) totalRecordings / total * 100 else 0.0

        suspend fun progress(
            current: Int,
            totalSub: Long,
            basePercentage: Double,
            maxPercentage: Double,
            message: String
        ) {
            val subProgress = if (totalSub > 0) current.toDouble() / totalSub else 1.0
            onProgress(basePercentage + subProgress * maxPercentage, message)
        }

        logger.info("Updating $totalArtists stale artists in MusicBrainz cache")
        musicBrainzCacheService.staleArtistIdsFlow(oneMonthAgo).collect { id ->
            try {
                musicBrainzService.fetchArtistById(id, HttpClientPriority.LOW)?.let {
                    musicBrainzCacheService.updateArtistCache(it)
                    artistsUpdated++
                } ?: run {
                    musicBrainzCacheService.updateArtistLastUpdate(id, getRetryTimestamp())
                }
                progress(
                    current = artistsUpdated,
                    totalSub = totalArtists,
                    basePercentage = artistBasePercentage,
                    maxPercentage = artistMaxPercentage,
                    message = "Updating artists: $artistsUpdated/$totalArtists (${total.toInt()})"
                )
            } catch (e: Exception) {
                logger.error("Failed to update artist $id in cache: ${e.message}")
            }
        }

        logger.info("Updating $totalReleaseGroups stale release groups in MusicBrainz cache")
        musicBrainzCacheService.staleReleaseGroupIdsFlow(oneMonthAgo).collect { id ->
            try {
                musicBrainzService.fetchReleaseGroupById(id, HttpClientPriority.LOW)?.let {
                    musicBrainzCacheService.updateReleaseGroupCache(it)
                    releaseGroupsUpdated++
                } ?: run {
                    musicBrainzCacheService.updateReleaseGroupLastUpdate(id, getRetryTimestamp())
                }
                progress(
                    current = releaseGroupsUpdated,
                    totalSub = totalReleaseGroups,
                    basePercentage = releaseGroupBasePercentage,
                    maxPercentage = releaseGroupMaxPercentage,
                    message = "Updating release groups: $releaseGroupsUpdated/$totalReleaseGroups (${total.toInt()})"
                )
            } catch (e: Exception) {
                logger.error("Failed to update release group $id in cache: ${e.message}")
            }
        }

        logger.info("Updating $totalReleases stale releases in MusicBrainz cache")
        musicBrainzCacheService.staleReleaseIdsFlow(oneMonthAgo).collect { id ->
            try {
                musicBrainzService.fetchReleaseById(id, HttpClientPriority.LOW)?.let {
                    musicBrainzCacheService.updateReleaseCache(it)
                    releasesUpdated++
                } ?: run {
                    musicBrainzCacheService.updateReleaseLastUpdate(id, getRetryTimestamp())
                }
                progress(
                    current = releasesUpdated,
                    totalSub = totalReleases,
                    basePercentage = releaseBasePercentage,
                    maxPercentage = releaseMaxPercentage,
                    message = "Updating releases: $releasesUpdated/$totalReleases (${total.toInt()})"
                )
            } catch (e: Exception) {
                logger.error("Failed to update release $id in cache: ${e.message}")
            }
        }

        logger.info("Updating $totalRecordings stale recordings in MusicBrainz cache")
        musicBrainzCacheService.staleRecordingIdsFlow(oneMonthAgo).collect { id ->
            try {
                musicBrainzService.fetchRecordingById(id, HttpClientPriority.LOW)?.let {
                    musicBrainzCacheService.updateRecordingCache(it)
                    recordingsUpdated++
                } ?: run {
                    musicBrainzCacheService.updateRecordingLastUpdate(id, getRetryTimestamp())
                }
                progress(
                    current = recordingsUpdated,
                    totalSub = totalRecordings,
                    basePercentage = recordingBasePercentage,
                    maxPercentage = recordingMaxPercentage,
                    message = "Updating recordings: $recordingsUpdated/$totalRecordings (${total.toInt()})"
                )
            } catch (e: Exception) {
                logger.error("Failed to update recording $id in cache: ${e.message}")
            }
        }

        return mapOf(
            "artistsUpdated" to artistsUpdated,
            "releaseGroupsUpdated" to releaseGroupsUpdated,
            "releasesUpdated" to releasesUpdated,
            "recordingsUpdated" to recordingsUpdated
        )
    }
}
