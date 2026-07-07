package dev.dertyp.services.sync

import dev.dertyp.ApiClient
import dev.dertyp.PlatformUUID
import dev.dertyp.core.safeQueuedGet
import dev.dertyp.data.ListenBrainzStatus
import dev.dertyp.data.User
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.platformUUIDFromString
import dev.dertyp.services.IListenBrainzService
import dev.dertyp.services.IncomingListen
import dev.dertyp.services.ListenService
import dev.dertyp.services.Service
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ListenBrainzService : Service() {
    private val listenService by inject<ListenService>()

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun signalChange() {
        changes.tryEmit(Unit)
    }

    @OptIn(FlowPreview::class)
    fun statusFlow(userId: PlatformUUID): Flow<ListenBrainzStatus?> =
        changes
            .onStart { emit(Unit) }
            .debounce(100.milliseconds)
            .map { getStatus(userId) }
            .distinctUntilChanged()

    suspend fun link(userId: PlatformUUID, username: String, token: String?): ListenBrainzStatus {
        val lbUserId = dbQuery {
            val existingId = ListenBrainzUserTable
                .select(ListenBrainzUserTable.id)
                .where { ListenBrainzUserTable.username eq username }
                .singleOrNull()?.get(ListenBrainzUserTable.id)?.value

            val id = existingId ?: ListenBrainzUserTable.insertAndGetId {
                it[ListenBrainzUserTable.username] = username
                it[ListenBrainzUserTable.token] = token
            }.value

            if (existingId != null && token != null) {
                ListenBrainzUserTable.update({ ListenBrainzUserTable.id eq id }) {
                    it[ListenBrainzUserTable.token] = token
                }
            }

            UserListenBrainzLinkTable.deleteWhere { UserListenBrainzLinkTable.userId eq userId }
            UserListenBrainzLinkTable.insert {
                it[UserListenBrainzLinkTable.userId] = userId
                it[UserListenBrainzLinkTable.listenBrainzUserId] = id
                it[UserListenBrainzLinkTable.enabled] = true
            }
            id
        }

        signalChange()
        serviceScope.launch {
            runCatching { syncAccount(lbUserId) }
                .onFailure { logger.error("Initial ListenBrainz backfill failed for account $lbUserId", it) }
        }
        return getStatus(userId) ?: error("ListenBrainz link missing after link")
    }

    suspend fun unlink(userId: PlatformUUID) {
        dbQuery { UserListenBrainzLinkTable.deleteWhere { UserListenBrainzLinkTable.userId eq userId } }
        signalChange()
    }

    suspend fun getStatus(userId: PlatformUUID): ListenBrainzStatus? = dbQuery {
        UserListenBrainzLinkTable.innerJoin(ListenBrainzUserTable)
            .selectAll()
            .where { UserListenBrainzLinkTable.userId eq userId }
            .singleOrNull()
            ?.let { row ->
                val lbId = row[UserListenBrainzLinkTable.listenBrainzUserId].value
                val count = ListenTable.selectAll()
                    .where { (ListenTable.listenBrainzUserId eq lbId) and ListenTable.songId.isNotNull() }
                    .count()
                ListenBrainzStatus(
                    username = row[ListenBrainzUserTable.username],
                    enabled = row[UserListenBrainzLinkTable.enabled],
                    lastListenedAt = row[ListenBrainzUserTable.lastListenedAt],
                    lastSyncedAt = row[ListenBrainzUserTable.lastSyncedAt],
                    matchedListenCount = count
                )
            }
    }

    suspend fun syncNow(userId: PlatformUUID): ListenBrainzStatus {
        val lbId = dbQuery {
            UserListenBrainzLinkTable
                .select(UserListenBrainzLinkTable.listenBrainzUserId)
                .where { (UserListenBrainzLinkTable.userId eq userId) and (UserListenBrainzLinkTable.enabled eq true) }
                .singleOrNull()?.get(UserListenBrainzLinkTable.listenBrainzUserId)?.value
        }
        if (lbId != null) {
            serviceScope.launch {
                runCatching { syncAccount(lbId) }
                    .onFailure { logger.error("ListenBrainz sync failed for account $lbId", it) }
            }
        }
        return getStatus(userId) ?: error("No ListenBrainz link for user")
    }

    suspend fun syncAllAccounts(onProgress: suspend (Double, String) -> Unit = { _, _ -> }): Int {
        val accountIds = dbQuery {
            UserListenBrainzLinkTable
                .select(UserListenBrainzLinkTable.listenBrainzUserId)
                .where { UserListenBrainzLinkTable.enabled eq true }
                .mapNotNull { it[UserListenBrainzLinkTable.listenBrainzUserId].value }
                .distinct()
        }
        if (accountIds.isEmpty()) {
            logger.info("No linked ListenBrainz accounts to sync")
            onProgress(100.0, "No linked accounts")
            return 0
        }

        logger.info("Syncing ${accountIds.size} ListenBrainz account(s)")
        var total = 0
        accountIds.forEachIndexed { index, id ->
            val base = index.toDouble() / accountIds.size * 100.0
            runCatching { total += syncAccount(id) { message -> onProgress(base, message) } }
                .onFailure { logger.error("ListenBrainz sync failed for account $id", it) }
        }
        logger.info("ListenBrainz sync complete: $total new listen(s) across ${accountIds.size} account(s)")
        onProgress(100.0, "Synced $total listen(s) across ${accountIds.size} account(s)")
        return total
    }

    private suspend fun syncAccount(lbUserId: PlatformUUID, onProgress: suspend (String) -> Unit = {}): Int {
        val account = dbQuery {
            ListenBrainzUserTable.selectAll().where { ListenBrainzUserTable.id eq lbUserId }.singleOrNull()?.let {
                Triple(
                    it[ListenBrainzUserTable.username],
                    it[ListenBrainzUserTable.token],
                    it[ListenBrainzUserTable.lastListenedAt] ?: 0L
                )
            }
        } ?: return 0

        val (username, token, watermark) = account

        rematchUnmatched(lbUserId)

        var maxTs: Long? = null
        var newestSeen = watermark
        var total = 0
        var matchedTotal = 0
        var pages = 0
        var completed = false

        while (true) {
            val page = fetchListens(username, token, maxTs)
            if (page == null) {
                logger.error("[$username] listens fetch failed after retries at max_ts=$maxTs; aborting sync (watermark unchanged, will resume next run)")
                break
            }
            if (page.isEmpty()) {
                completed = true
                break
            }
            pages++

            val fresh = page.filter { it.listenedAt > watermark }
            val incoming = toIncomingListens(fresh)
            val matched = incoming.count { it.songId != null }
            listenService.ingestListenBrainz(lbUserId, incoming)
            total += incoming.size
            matchedTotal += matched
            logger.info("[$username] page $pages: fetched ${page.size}, stored ${incoming.size}, matched $matched (total $total, matched $matchedTotal)")
            onProgress("$username: $total listen(s) synced")
            if (incoming.isNotEmpty()) signalChange()

            newestSeen = maxOf(newestSeen, page.maxOf { it.listenedAt })
            if (page.any { it.listenedAt <= watermark } || page.size < PAGE_COUNT) {
                completed = true
                break
            }
            maxTs = page.minOf { it.listenedAt }
        }

        dbQuery {
            ListenBrainzUserTable.update({ ListenBrainzUserTable.id eq lbUserId }) {
                if (completed) it[ListenBrainzUserTable.lastListenedAt] = newestSeen
                it[ListenBrainzUserTable.lastSyncedAt] = System.currentTimeMillis()
            }
        }
        logger.info("[$username] sync ${if (completed) "complete" else "interrupted"}: $total stored, $matchedTotal matched over $pages page(s)")
        signalChange()
        return total
    }

    private suspend fun fetchListens(username: String, token: String?, maxTs: Long?): List<LbListen>? {
        val url = buildString {
            append("$API_BASE/user/${username.encodeURLPathPart()}/listens?count=$PAGE_COUNT")
            if (maxTs != null) append("&max_ts=$maxTs")
        }
        repeat(FETCH_ATTEMPTS) { attempt ->
            val response = ApiClient.instance.safeQueuedGet<LbListensResponse>(url) {
                if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Token $token")
            }
            if (response != null) return response.payload.listens
            if (attempt < FETCH_ATTEMPTS - 1) {
                logger.warn("[$username] listens fetch failed (max_ts=$maxTs), retry ${attempt + 1}/${FETCH_ATTEMPTS - 1}")
                delay(2.seconds * (attempt + 1))
            }
        }
        return null
    }

    private suspend fun toIncomingListens(listens: List<LbListen>): List<IncomingListen> {
        if (listens.isEmpty()) return emptyList()

        val mbids = listens.mapNotNull { it.recordingMbid()?.toPlatformUuidOrNull() }.distinct()
        val directIsrcs = listens.mapNotNull { it.isrc()?.uppercase() }
        val match = dbQuery { resolveMatches(mbids, directIsrcs) }

        return listens.map { listen ->
            val mbid = listen.recordingMbid()?.toPlatformUuidOrNull()
            val candidateIsrcs = buildList {
                listen.isrc()?.uppercase()?.let { add(it) }
                mbid?.let { match.mbidToIsrcs[it] }?.let { addAll(it) }
            }
            val songId = candidateIsrcs.firstNotNullOfOrNull { match.isrcToSong[it] }
                ?: mbid?.let { match.mbidToSong[it] }

            IncomingListen(
                listenedAtMs = listen.listenedAt * 1000,
                songId = songId,
                recordingMbid = mbid,
                releaseMbid = listen.releaseMbid()?.toPlatformUuidOrNull(),
                artistMbids = listen.artistMbids().joinToString(",").ifBlank { null },
                trackName = listen.trackName(),
                artistName = listen.artistName(),
                releaseName = listen.releaseName(),
            )
        }
    }

    suspend fun rematchUnmatched(lbUserId: PlatformUUID): Int {
        val unmatchedMbids = dbQuery {
            ListenTable
                .select(ListenTable.recordingMbid)
                .where { (ListenTable.listenBrainzUserId eq lbUserId) and ListenTable.songId.isNull() and ListenTable.recordingMbid.isNotNull() }
                .mapNotNull { it[ListenTable.recordingMbid] }
                .distinct()
        }
        if (unmatchedMbids.isEmpty()) return 0

        val match = dbQuery { resolveMatches(unmatchedMbids, emptyList()) }
        val resolved = unmatchedMbids.mapNotNull { mbid ->
            val songId = match.mbidToSong[mbid]
                ?: match.mbidToIsrcs[mbid]?.firstNotNullOfOrNull { match.isrcToSong[it] }
            if (songId != null) mbid to songId else null
        }
        if (resolved.isEmpty()) return 0

        var updated = 0
        dbQuery {
            resolved.forEach { (mbid, songId) ->
                updated += ListenTable.update({
                    (ListenTable.listenBrainzUserId eq lbUserId) and (ListenTable.recordingMbid eq mbid) and ListenTable.songId.isNull()
                }) { it[ListenTable.songId] = songId }
            }
        }
        if (updated > 0) {
            logger.info("Re-matched $updated previously-unmatched listen(s) for account $lbUserId")
            signalChange()
        }
        return updated
    }

    private fun resolveMatches(mbids: List<PlatformUUID>, directIsrcs: List<String>): Match {
        val mbidToIsrcs = mbids.chunked(QUERY_CHUNK).flatMap { chunk ->
            MBRecordingIsrcTable
                .select(MBRecordingIsrcTable.recordingId, MBRecordingIsrcTable.isrc)
                .where { MBRecordingIsrcTable.recordingId inList chunk }
                .map { it[MBRecordingIsrcTable.recordingId].value to it[MBRecordingIsrcTable.isrc].uppercase() }
        }.groupBy({ it.first }, { it.second })

        val candidateIsrcs = (directIsrcs + mbidToIsrcs.values.flatten()).distinct()

        val isrcToSong = candidateIsrcs.chunked(QUERY_CHUNK).flatMap { chunk ->
            SongTable
                .select(SongTable.id, SongTable.isrc)
                .where { SongTable.isrc inList chunk }
                .mapNotNull { row -> row[SongTable.isrc]?.uppercase()?.let { it to row[SongTable.id].value } }
        }.toMap()

        val mbidToSong = mbids.chunked(QUERY_CHUNK).flatMap { chunk ->
            SongMusicBrainzTable
                .select(SongMusicBrainzTable.songId, SongMusicBrainzTable.musicBrainzId)
                .where { SongMusicBrainzTable.musicBrainzId inList chunk }
                .map { it[SongMusicBrainzTable.musicBrainzId]!!.value to it[SongMusicBrainzTable.songId].value }
        }.toMap()

        return Match(mbidToIsrcs, isrcToSong, mbidToSong)
    }

    private data class Match(
        val mbidToIsrcs: Map<PlatformUUID, List<String>>,
        val isrcToSong: Map<String, PlatformUUID>,
        val mbidToSong: Map<PlatformUUID, PlatformUUID>,
    )

    private fun String.toPlatformUuidOrNull(): PlatformUUID? =
        try { platformUUIDFromString(this) } catch (_: Exception) { null }

    companion object {
        private const val API_BASE = "https://api.listenbrainz.org/1"
        private const val PAGE_COUNT = 1000
        private const val QUERY_CHUNK = 1000
        private const val FETCH_ATTEMPTS = 4
    }
}

class RpcListenBrainzService(
    private val user: User,
    private val service: ListenBrainzService,
) : IListenBrainzService {
    override suspend fun link(username: String, token: String?): ListenBrainzStatus =
        service.link(user.id, username, token)

    override suspend fun unlink() = service.unlink(user.id)

    override suspend fun getStatus(): ListenBrainzStatus? = service.getStatus(user.id)

    override fun getStatusFlow(): Flow<ListenBrainzStatus?> = service.statusFlow(user.id)

    override suspend fun syncNow(): ListenBrainzStatus = service.syncNow(user.id)
}

@Serializable
private data class LbListensResponse(val payload: LbPayload = LbPayload())

@Serializable
private data class LbPayload(
    val count: Int = 0,
    val listens: List<LbListen> = emptyList(),
)

@Serializable
private data class LbListen(
    @SerialName("listened_at") val listenedAt: Long = 0,
    @SerialName("track_metadata") val trackMetadata: LbTrackMetadata = LbTrackMetadata(),
) {
    fun recordingMbid(): String? = trackMetadata.additionalInfo?.recordingMbid ?: trackMetadata.mbidMapping?.recordingMbid
    fun releaseMbid(): String? = trackMetadata.additionalInfo?.releaseMbid ?: trackMetadata.mbidMapping?.releaseMbid
    fun artistMbids(): List<String> =
        trackMetadata.additionalInfo?.artistMbids?.takeIf { it.isNotEmpty() } ?: trackMetadata.mbidMapping?.artistMbids ?: emptyList()
    fun isrc(): String? = trackMetadata.additionalInfo?.isrc
    fun trackName(): String? = trackMetadata.trackName
    fun artistName(): String? = trackMetadata.artistName
    fun releaseName(): String? = trackMetadata.releaseName
}

@Serializable
private data class LbTrackMetadata(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("release_name") val releaseName: String? = null,
    @SerialName("additional_info") val additionalInfo: LbAdditionalInfo? = null,
    @SerialName("mbid_mapping") val mbidMapping: LbMbidMapping? = null,
)

@Serializable
private data class LbAdditionalInfo(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("release_mbid") val releaseMbid: String? = null,
    @SerialName("artist_mbids") val artistMbids: List<String> = emptyList(),
    val isrc: String? = null,
)

@Serializable
private data class LbMbidMapping(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    @SerialName("release_mbid") val releaseMbid: String? = null,
    @SerialName("artist_mbids") val artistMbids: List<String> = emptyList(),
)
