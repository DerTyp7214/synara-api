package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.TaskKeys
import dev.dertyp.db.ListenTable
import dev.dertyp.db.MBArtistTable
import dev.dertyp.db.MBRecordingArtistCreditTable
import dev.dertyp.db.MBRecordingReleaseTable
import dev.dertyp.db.MBRecordingTable
import dev.dertyp.db.MBReleaseGroupCoverTable
import dev.dertyp.db.MBReleaseGroupTable
import dev.dertyp.db.MBReleaseTable
import dev.dertyp.dbQuery
import dev.dertyp.services.ReleaseService
import dev.dertyp.services.metadata.MusicBrainzCacheService
import dev.dertyp.services.metadata.MusicBrainzService
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@WorkerTask(TaskKeys.MUSICBRAINZ_CACHE_WORKER, "MusicBrainz Cache Worker")
class MusicBrainzCacheWorker : Worker("MusicBrainzCacheWorker") {
    private val musicBrainzService by inject<MusicBrainzService>()
    private val musicBrainzCacheService by inject<MusicBrainzCacheService>()
    private val releaseService by inject<ReleaseService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        var artistsUpdated = 0
        var recordingsUpdated = 0
        var releasesUpdated = 0
        var releaseGroupsUpdated = 0
        var listenRecordingsCached = 0
        var coversFetched = 0
        var coversChecked = 0

        val oneMonthAgo = Clock.System.now().toEpochMilliseconds() - 90.days.inWholeMilliseconds
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

        val listenRecordingIds = uncachedListenRecordingIds(oneMonthAgo)
        val activeCoverGroupIds = listenCoverGroupIds()
        val coverGroupIds = coverGroupIdsNeedingFetch(activeCoverGroupIds)
        val prunedCovers = pruneUnlistenedCovers(activeCoverGroupIds)

        val total = (totalArtists + totalReleaseGroups + totalReleases + totalRecordings +
            listenRecordingIds.size + coverGroupIds.size).toDouble()

        val artistBasePercentage = .0
        val artistMaxPercentage = if (total > 0) totalArtists / total * 100 else 0.0

        val releaseGroupBasePercentage = artistBasePercentage + artistMaxPercentage
        val releaseGroupMaxPercentage = if (total > 0) totalReleaseGroups / total * 100 else 0.0

        val releaseBasePercentage = releaseGroupBasePercentage + releaseGroupMaxPercentage
        val releaseMaxPercentage = if (total > 0) totalReleases / total * 100 else 0.0

        val recordingBasePercentage = releaseBasePercentage + releaseMaxPercentage
        val recordingMaxPercentage = if (total > 0) totalRecordings / total * 100 else 0.0

        val listenRecordingBasePercentage = recordingBasePercentage + recordingMaxPercentage
        val listenRecordingMaxPercentage = if (total > 0) listenRecordingIds.size / total * 100 else 0.0

        val coverBasePercentage = listenRecordingBasePercentage + listenRecordingMaxPercentage
        val coverMaxPercentage = if (total > 0) coverGroupIds.size / total * 100 else 0.0

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

        logger.info("Caching ${listenRecordingIds.size} listen-referenced recordings in MusicBrainz cache")
        listenRecordingIds.forEachIndexed { index, id ->
            try {
                musicBrainzService.fetchRecordingById(id, HttpClientPriority.LOW)?.let {
                    musicBrainzCacheService.updateRecordingCache(it)
                    listenRecordingsCached++
                } ?: run {
                    musicBrainzCacheService.updateRecordingLastUpdate(id, getRetryTimestamp())
                }
                progress(
                    current = index + 1,
                    totalSub = listenRecordingIds.size.toLong(),
                    basePercentage = listenRecordingBasePercentage,
                    maxPercentage = listenRecordingMaxPercentage,
                    message = "Caching listened recordings: ${index + 1}/${listenRecordingIds.size}"
                )
            } catch (e: Exception) {
                logger.error("Failed to cache listened recording $id: ${e.message}")
            }
        }

        logger.info("Fetching covers for ${coverGroupIds.size} listened release groups")
        coverGroupIds.forEach { groupId ->
            try {
                val imageId = releaseService.fetchReleaseGroupImage(groupId)
                dbQuery {
                    MBReleaseGroupCoverTable.upsert(MBReleaseGroupCoverTable.releaseGroupId) {
                        it[releaseGroupId] = groupId
                        it[MBReleaseGroupCoverTable.imageId] = imageId
                        it[lastFetch] = Clock.System.now().toEpochMilliseconds()
                    }
                }
                if (imageId != null) coversFetched++
                coversChecked++
                progress(
                    current = coversChecked,
                    totalSub = coverGroupIds.size.toLong(),
                    basePercentage = coverBasePercentage,
                    maxPercentage = coverMaxPercentage,
                    message = "Fetching listened release covers: $coversChecked/${coverGroupIds.size}"
                )
            } catch (e: Exception) {
                logger.error("Failed to fetch cover for release group $groupId: ${e.message}")
            }
        }

        return mapOf(
            "artistsUpdated" to artistsUpdated,
            "releaseGroupsUpdated" to releaseGroupsUpdated,
            "releasesUpdated" to releasesUpdated,
            "recordingsUpdated" to recordingsUpdated,
            "listenRecordingsCached" to listenRecordingsCached,
            "listenCoversFetched" to coversFetched,
            "listenCoversPruned" to prunedCovers
        )
    }

    private suspend fun uncachedListenRecordingIds(oneMonthAgo: Long): List<UUID> = dbQuery {
        val referenced = ListenTable.select(ListenTable.recordingMbid)
            .where { ListenTable.recordingMbid.isNotNull() }
            .withDistinct()
            .mapNotNull { it[ListenTable.recordingMbid] }

        val complete = mutableSetOf<UUID>()
        referenced.chunked(CHUNK_SIZE).forEach { chunk ->
            MBRecordingTable
                .join(
                    MBRecordingArtistCreditTable,
                    JoinType.INNER,
                    onColumn = MBRecordingTable.id,
                    otherColumn = MBRecordingArtistCreditTable.recordingId,
                )
                .select(MBRecordingTable.id)
                .where { MBRecordingTable.id inList chunk }
                .andWhere { MBRecordingTable.lastUpdate greater 0L }
                .withDistinct()
                .forEach { complete.add(it[MBRecordingTable.id].value) }
            MBRecordingTable
                .select(MBRecordingTable.id)
                .where { MBRecordingTable.id inList chunk }
                .andWhere { MBRecordingTable.lastUpdate greaterEq oneMonthAgo }
                .forEach { complete.add(it[MBRecordingTable.id].value) }
        }

        referenced.filterNot { it in complete }
    }

    private suspend fun listenCoverGroupIds(): Set<UUID> = dbQuery {
        val releaseMbids = ListenTable.select(ListenTable.releaseMbid)
            .where { ListenTable.releaseMbid.isNotNull() }
            .withDistinct()
            .mapNotNull { it[ListenTable.releaseMbid] }
            .toMutableSet()

        val recordingsWithoutRelease = ListenTable.select(ListenTable.recordingMbid)
            .where { ListenTable.recordingMbid.isNotNull() }
            .andWhere { ListenTable.releaseMbid.isNull() }
            .withDistinct()
            .mapNotNull { it[ListenTable.recordingMbid] }
        val firstReleaseByRecording = mutableMapOf<UUID, UUID>()
        recordingsWithoutRelease.chunked(CHUNK_SIZE).forEach { chunk ->
            MBRecordingReleaseTable
                .select(MBRecordingReleaseTable.recordingId, MBRecordingReleaseTable.releaseId)
                .where { MBRecordingReleaseTable.recordingId inList chunk }
                .forEach {
                    firstReleaseByRecording.putIfAbsent(
                        it[MBRecordingReleaseTable.recordingId].value,
                        it[MBRecordingReleaseTable.releaseId].value,
                    )
                }
        }
        releaseMbids.addAll(firstReleaseByRecording.values)

        val groupIds = mutableSetOf<UUID>()
        releaseMbids.chunked(CHUNK_SIZE).forEach { chunk ->
            MBReleaseTable
                .select(MBReleaseTable.releaseGroupId)
                .where { MBReleaseTable.id inList chunk }
                .forEach { row -> row[MBReleaseTable.releaseGroupId]?.value?.let(groupIds::add) }
        }
        groupIds
    }

    private suspend fun coverGroupIdsNeedingFetch(activeGroupIds: Set<UUID>): List<UUID> = dbQuery {
        val coverRetryCutoff = Clock.System.now().toEpochMilliseconds() - COVER_RETRY.inWholeMilliseconds
        val fresh = mutableSetOf<UUID>()
        activeGroupIds.chunked(CHUNK_SIZE).forEach { chunk ->
            MBReleaseGroupCoverTable
                .select(MBReleaseGroupCoverTable.releaseGroupId)
                .where { MBReleaseGroupCoverTable.releaseGroupId inList chunk }
                .andWhere {
                    MBReleaseGroupCoverTable.imageId.isNotNull() or
                        (MBReleaseGroupCoverTable.lastFetch greaterEq coverRetryCutoff)
                }
                .forEach { fresh.add(it[MBReleaseGroupCoverTable.releaseGroupId].value) }
        }
        activeGroupIds.filterNot { it in fresh }
    }

    private suspend fun pruneUnlistenedCovers(activeGroupIds: Set<UUID>): Int = dbQuery {
        val stale = MBReleaseGroupCoverTable
            .select(MBReleaseGroupCoverTable.releaseGroupId)
            .map { it[MBReleaseGroupCoverTable.releaseGroupId].value }
            .filterNot { it in activeGroupIds }
        stale.chunked(CHUNK_SIZE).forEach { chunk ->
            MBReleaseGroupCoverTable.deleteWhere { releaseGroupId inList chunk }
        }
        stale.size
    }

    private companion object {
        const val CHUNK_SIZE = 1000
        val COVER_RETRY = 30.days
    }
}
