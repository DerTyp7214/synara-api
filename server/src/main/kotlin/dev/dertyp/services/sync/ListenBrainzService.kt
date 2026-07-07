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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.*
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.milliseconds

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
                    .where { ListenTable.listenBrainzUserId eq lbId }
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
        var maxTs: Long? = null
        var newestSeen = watermark
        var total = 0
        var pages = 0

        while (true) {
            val page = fetchListens(username, token, maxTs) ?: break
            if (page.isEmpty()) break
            pages++

            val fresh = page.filter { it.listenedAt > watermark }
            val ingested = listenService.ingestListenBrainz(lbUserId, matchListens(fresh))
            total += ingested
            logger.info("[$username] page $pages: fetched ${page.size}, ingested $ingested (total $total)")
            onProgress("$username: $total listen(s) synced")
            if (ingested > 0) signalChange()

            newestSeen = maxOf(newestSeen, page.maxOf { it.listenedAt })
            val reachedWatermark = page.any { it.listenedAt <= watermark }
            if (reachedWatermark || page.size < PAGE_COUNT) break
            maxTs = page.minOf { it.listenedAt }
        }

        dbQuery {
            ListenBrainzUserTable.update({ ListenBrainzUserTable.id eq lbUserId }) {
                it[ListenBrainzUserTable.lastListenedAt] = newestSeen
                it[ListenBrainzUserTable.lastSyncedAt] = System.currentTimeMillis()
            }
        }
        logger.info("[$username] sync complete: $total new listen(s) over $pages page(s)")
        signalChange()
        return total
    }

    private suspend fun fetchListens(username: String, token: String?, maxTs: Long?): List<LbListen>? {
        val url = buildString {
            append("$API_BASE/user/${username.encodeURLPathPart()}/listens?count=$PAGE_COUNT")
            if (maxTs != null) append("&max_ts=$maxTs")
        }
        val response = ApiClient.instance.safeQueuedGet<LbListensResponse>(url) {
            if (!token.isNullOrBlank()) header(HttpHeaders.Authorization, "Token $token")
        } ?: return null
        return response.payload.listens
    }

    private suspend fun matchListens(listens: List<LbListen>): List<IncomingListen> {
        if (listens.isEmpty()) return emptyList()

        val mbids = listens.mapNotNull { it.recordingMbid()?.toPlatformUuidOrNull() }.distinct()
        val directIsrcs = listens.mapNotNull { it.isrc()?.uppercase() }

        val match = dbQuery {
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

            Match(mbidToIsrcs, isrcToSong, mbidToSong)
        }

        return listens.mapNotNull { listen ->
            val mbid = listen.recordingMbid()?.toPlatformUuidOrNull()
            val candidateIsrcs = buildList {
                listen.isrc()?.uppercase()?.let { add(it) }
                mbid?.let { match.mbidToIsrcs[it] }?.let { addAll(it) }
            }

            val songId = candidateIsrcs.firstNotNullOfOrNull { match.isrcToSong[it] }
                ?: mbid?.let { match.mbidToSong[it] }
                ?: return@mapNotNull null
            IncomingListen(songId, listen.listenedAt * 1000)
        }
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
    fun isrc(): String? = trackMetadata.additionalInfo?.isrc
}

@Serializable
private data class LbTrackMetadata(
    @SerialName("additional_info") val additionalInfo: LbAdditionalInfo? = null,
    @SerialName("mbid_mapping") val mbidMapping: LbMbidMapping? = null,
)

@Serializable
private data class LbAdditionalInfo(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
    val isrc: String? = null,
)

@Serializable
private data class LbMbidMapping(
    @SerialName("recording_mbid") val recordingMbid: String? = null,
)
