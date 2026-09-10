package dev.dertyp.services

import dev.dertyp.core.paging
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueSyncDevice
import dev.dertyp.data.QueueUploadStart
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.db.QueueSyncDeviceTable
import dev.dertyp.db.SessionTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserQueueEntryTable
import dev.dertyp.db.UserQueueTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.koin.core.component.inject
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class QueueService : Service() {
    companion object {
        const val MAX_PAGE = 1000
        val STALE_QUEUE_MS = 30.days.inWholeMilliseconds

        private const val LOOKUP_CHUNK = 1000
    }

    private val songService by inject<SongService>()

    private data class Meta(
        val version: Long,
        val modifiedAt: Long,
        val modifiedBySessionId: UUID?,
        val modifiedByDeviceName: String?,
        val currentIndex: Int,
        val shuffleMode: Boolean,
        val repeatMode: RepeatMode,
        val sourceId: String?
    )

    private data class Row(
        val queueId: Long,
        val songId: UUID,
        val position: Int,
        val shuffledPosition: Int?,
        val explicit: Boolean
    )

    private class Staging(
        val userId: UUID,
        val sessionId: UUID?,
        val baseVersion: Long,
        val force: Boolean,
        val expiresAt: Long
    ) {
        val items: MutableList<QueueItem> = Collections.synchronizedList(mutableListOf())
    }

    internal var uploadTtlMs: Long = 10.minutes.inWholeMilliseconds

    private val locks = ConcurrentHashMap<UUID, Mutex>()
    private val changes = ConcurrentHashMap<UUID, MutableSharedFlow<QueueInfo>>()
    private val uploads = ConcurrentHashMap<UUID, Staging>()
    private val activeUploads = ConcurrentHashMap<UUID, UUID>()

    suspend fun getInfo(userId: UUID): QueueInfo = dbQuery {
        info(loadMeta(userId), countEntries(userId))
    }

    suspend fun getQueue(userId: UUID, page: Int, pageSize: Int, includeSongs: Boolean): PaginatedResponse<QueueItem> {
        val size = pageSize.coerceIn(1, MAX_PAGE)
        val total = dbQuery { countEntries(userId) }
        val rows = dbQuery {
            UserQueueEntryTable
                .selectAll()
                .where { UserQueueEntryTable.userId eq userId }
                .orderBy(UserQueueEntryTable.position to SortOrder.ASC, UserQueueEntryTable.queueId to SortOrder.ASC)
                .paging(page, size)
                .map(::mapRow)
        }

        val songs = if (includeSongs && rows.isNotEmpty()) {
            songService.byIds(rows.map { it.songId }.distinct(), userId).associateBy { it.id }
        } else {
            emptyMap()
        }

        return PaginatedResponse(
            data = rows.map { row ->
                QueueItem(
                    songId = row.songId,
                    queueId = row.queueId,
                    position = row.position,
                    shuffledPosition = row.shuffledPosition,
                    explicit = row.explicit,
                    song = songs[row.songId]
                )
            },
            page = page,
            total = total,
            pageSize = size,
            hasNextPage = (page + 1).toLong() * size < total
        )
    }

    fun observe(userId: UUID): Flow<QueueInfo> = changeFlow(userId).asSharedFlow()

    suspend fun beginUpload(userId: UUID, sessionId: UUID?, baseVersion: Long, force: Boolean): QueueUploadStart {
        purgeExpired()

        val current = getInfo(userId)
        if (!force && baseVersion != current.version) return QueueUploadStart.Conflict(current)

        val uploadId = UUID.randomUUID()
        val expiresAt = Instant.now().toEpochMilli() + uploadTtlMs
        uploads[uploadId] = Staging(userId, sessionId, baseVersion, force, expiresAt)
        activeUploads.put(userId, uploadId)?.let(uploads::remove)

        return QueueUploadStart.Started(uploadId, expiresAt)
    }

    fun uploadPage(userId: UUID, uploadId: UUID, items: List<QueueItem>): Int {
        purgeExpired()
        require(items.size <= MAX_PAGE) { "A queue upload page holds at most $MAX_PAGE entries" }

        val staging = staging(userId, uploadId)
        synchronized(staging.items) {
            staging.items.addAll(items)
            return staging.items.size
        }
    }

    suspend fun commitUpload(userId: UUID, uploadId: UUID, meta: QueueMeta): QueueWriteResult {
        purgeExpired()

        val staging = staging(userId, uploadId)
        uploads.remove(uploadId)
        activeUploads.remove(userId, uploadId)

        val items = synchronized(staging.items) { staging.items.toList() }
        requireDistinctIds(items)

        return write(userId, staging.sessionId, staging.baseVersion, staging.force) { current, rows ->
            val known = existingSongs(items.map { it.songId })
            rows.clear()
            items.filter { it.songId in known }.mapTo(rows) {
                Row(it.queueId, it.songId, it.position, it.shuffledPosition, it.explicit)
            }
            current.copy(
                currentIndex = meta.currentIndex,
                shuffleMode = meta.shuffleMode,
                repeatMode = meta.repeatMode,
                sourceId = meta.sourceId
            )
        }
    }

    fun cancelUpload(userId: UUID, uploadId: UUID) {
        val staging = uploads[uploadId] ?: return
        if (staging.userId != userId) return
        uploads.remove(uploadId)
        activeUploads.remove(userId, uploadId)
    }

    suspend fun insert(
        userId: UUID,
        sessionId: UUID?,
        baseVersion: Long,
        position: Int,
        items: List<QueueItem>,
        force: Boolean
    ): QueueWriteResult {
        requireDistinctIds(items)

        return write(userId, sessionId, baseVersion, force) { meta, rows ->
            val taken = rows.mapTo(mutableSetOf()) { it.queueId }
            val clash = items.firstOrNull { it.queueId in taken }
            require(clash == null) { "Queue id ${clash?.queueId} is already used" }

            val known = existingSongs(items.map { it.songId })
            val accepted = items.filter { it.songId in known }
            val size = rows.size
            val at = position.coerceIn(0, size)

            if (accepted.isNotEmpty()) {
                if (meta.shuffleMode) {
                    val maxPosition = rows.maxOfOrNull { it.position } ?: -1
                    val shifted = rows.map { row ->
                        val shuffled = row.shuffledPosition
                        if (shuffled != null && shuffled >= at) row.copy(shuffledPosition = shuffled + accepted.size) else row
                    }
                    rows.clear()
                    rows.addAll(shifted)
                    accepted.forEachIndexed { index, item ->
                        rows.add(Row(item.queueId, item.songId, maxPosition + 1 + index, at + index, item.explicit))
                    }
                } else {
                    val shifted = rows.map { row ->
                        if (row.position >= at) row.copy(position = row.position + accepted.size) else row
                    }
                    rows.clear()
                    rows.addAll(shifted)
                    accepted.forEachIndexed { index, item ->
                        rows.add(Row(item.queueId, item.songId, at + index, null, item.explicit))
                    }
                }
            }

            val currentIndex = if (size > 0 && at <= meta.currentIndex) meta.currentIndex + accepted.size else meta.currentIndex
            meta.copy(currentIndex = currentIndex)
        }
    }

    suspend fun remove(
        userId: UUID,
        sessionId: UUID?,
        baseVersion: Long,
        queueIds: List<Long>,
        force: Boolean
    ): QueueWriteResult = write(userId, sessionId, baseVersion, force) { meta, rows ->
        val dropped = queueIds.toSet()
        val removedBefore = activeOrder(rows, meta.shuffleMode)
            .take(meta.currentIndex.coerceAtLeast(0))
            .count { it.queueId in dropped }
        rows.removeAll { it.queueId in dropped }
        meta.copy(currentIndex = meta.currentIndex - removedBefore)
    }

    suspend fun move(
        userId: UUID,
        sessionId: UUID?,
        baseVersion: Long,
        queueId: Long,
        toPosition: Int,
        force: Boolean
    ): QueueWriteResult = write(userId, sessionId, baseVersion, force) { meta, rows ->
        val active = activeOrder(rows, meta.shuffleMode).toMutableList()
        val from = active.indexOfFirst { it.queueId == queueId }
        val to = toPosition.coerceIn(0, (active.size - 1).coerceAtLeast(0))

        if (from < 0 || from == to) {
            meta
        } else {
            active.add(to, active.removeAt(from))
            val reordered = active.mapIndexed { index, row ->
                if (meta.shuffleMode) row.copy(shuffledPosition = index) else row.copy(position = index)
            }
            rows.clear()
            rows.addAll(reordered)

            val currentIndex = when {
                meta.currentIndex == from -> to
                from < meta.currentIndex && to >= meta.currentIndex -> meta.currentIndex - 1
                from > meta.currentIndex && to <= meta.currentIndex -> meta.currentIndex + 1
                else -> meta.currentIndex
            }
            meta.copy(currentIndex = currentIndex)
        }
    }

    suspend fun setCurrentIndex(
        userId: UUID,
        sessionId: UUID?,
        baseVersion: Long,
        currentIndex: Int,
        force: Boolean
    ): QueueWriteResult = write(userId, sessionId, baseVersion, force) { meta, _ ->
        meta.copy(currentIndex = currentIndex.coerceAtLeast(0))
    }

    suspend fun setModes(
        userId: UUID,
        sessionId: UUID?,
        baseVersion: Long,
        shuffleMode: Boolean,
        repeatMode: RepeatMode,
        force: Boolean
    ): QueueWriteResult = write(userId, sessionId, baseVersion, force) { meta, rows ->
        val current = activeOrder(rows, meta.shuffleMode).getOrNull(meta.currentIndex)

        when {
            shuffleMode && !meta.shuffleMode -> {
                val order = listOfNotNull(current) + rows.filter { it.queueId != current?.queueId }.shuffled()
                val shuffledIndex = order.withIndex().associate { (index, row) -> row.queueId to index }
                val updated = rows.map { it.copy(shuffledPosition = shuffledIndex[it.queueId]) }
                rows.clear()
                rows.addAll(updated)
                meta.copy(currentIndex = 0, shuffleMode = true, repeatMode = repeatMode)
            }

            !shuffleMode && meta.shuffleMode -> {
                val updated = rows.map { it.copy(shuffledPosition = null) }
                rows.clear()
                rows.addAll(updated)
                meta.copy(currentIndex = current?.position ?: 0, shuffleMode = false, repeatMode = repeatMode)
            }

            else -> meta.copy(repeatMode = repeatMode)
        }
    }

    suspend fun setSyncEnabled(userId: UUID, sessionId: UUID, enabled: Boolean, deviceName: String) {
        lock(userId).withLock {
            dbQuery {
                val updated = QueueSyncDeviceTable.update({ QueueSyncDeviceTable.sessionId eq sessionId }) {
                    it[QueueSyncDeviceTable.userId] = userId
                    it[QueueSyncDeviceTable.deviceName] = deviceName
                    it[QueueSyncDeviceTable.enabled] = enabled
                }

                if (updated == 0) {
                    QueueSyncDeviceTable.insert {
                        it[QueueSyncDeviceTable.sessionId] = sessionId
                        it[QueueSyncDeviceTable.userId] = userId
                        it[QueueSyncDeviceTable.deviceName] = deviceName
                        it[QueueSyncDeviceTable.enabled] = enabled
                    }
                }
            }
        }
    }

    suspend fun ackSynced(userId: UUID, sessionId: UUID, version: Long) {
        dbQuery {
            QueueSyncDeviceTable.update({
                (QueueSyncDeviceTable.sessionId eq sessionId) and (QueueSyncDeviceTable.userId eq userId)
            }) {
                it[lastSyncedVersion] = version
                it[lastSyncAt] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun getSyncDevices(userId: UUID, currentSessionId: UUID?): List<QueueSyncDevice> = dbQuery {
        QueueSyncDeviceTable
            .innerJoin(SessionTable)
            .selectAll()
            .where { QueueSyncDeviceTable.userId eq userId }
            .andWhere { QueueSyncDeviceTable.enabled eq true }
            .orderBy(SessionTable.lastActive to SortOrder.DESC)
            .map { row ->
                val sessionId = row[QueueSyncDeviceTable.sessionId].value
                QueueSyncDevice(
                    sessionId = sessionId,
                    deviceName = row[QueueSyncDeviceTable.deviceName],
                    enabled = row[QueueSyncDeviceTable.enabled],
                    lastSyncedVersion = row[QueueSyncDeviceTable.lastSyncedVersion],
                    lastSyncAt = row[QueueSyncDeviceTable.lastSyncAt],
                    lastActive = row[SessionTable.lastActive],
                    isCurrent = sessionId == currentSessionId
                )
            }
    }

    suspend fun deviceName(sessionId: UUID?): String? {
        if (sessionId == null) return null
        return dbQuery { loadDeviceName(sessionId) }
    }

    suspend fun cleanupStaleQueues(
        maxAgeMs: Long = STALE_QUEUE_MS,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Int {
        val cutoff = Instant.now().toEpochMilli() - maxAgeMs
        val stale = dbQuery {
            UserQueueTable
                .select(UserQueueTable.userId)
                .where { UserQueueTable.modifiedAt less cutoff }
                .andWhere { UserQueueTable.userId inSubQuery UserQueueEntryTable.select(UserQueueEntryTable.userId) }
                .map { it[UserQueueTable.userId].value }
        }

        onProgress(0.0, "Found ${stale.size} stale queues to clear")

        stale.forEachIndexed { index, userId ->
            onProgress((index.toDouble() / stale.size) * 100.0, "Clearing queue ${index + 1}/${stale.size}")

            val cleared = lock(userId).withLock {
                dbQuery {
                    val current = loadMeta(userId)
                    UserQueueEntryTable.deleteWhere { UserQueueEntryTable.userId eq userId }

                    val stored = current.copy(
                        version = current.version + 1,
                        modifiedAt = Instant.now().toEpochMilli(),
                        modifiedBySessionId = null,
                        modifiedByDeviceName = null,
                        currentIndex = 0
                    )

                    UserQueueTable.update({ UserQueueTable.userId eq userId }) {
                        it[version] = stored.version
                        it[modifiedAt] = stored.modifiedAt
                        it[modifiedBySessionId] = null
                        it[modifiedByDeviceName] = null
                        it[currentIndex] = 0
                    }

                    info(stored, 0)
                }
            }

            changes[userId]?.tryEmit(cleared)
        }

        onProgress(100.0, "Cleared ${stale.size} stale queues")
        return stale.size
    }

    private suspend fun write(
        userId: UUID,
        sessionId: UUID?,
        baseVersion: Long,
        force: Boolean,
        block: (Meta, MutableList<Row>) -> Meta
    ): QueueWriteResult {
        val result = lock(userId).withLock {
            dbQuery {
                val current = loadMeta(userId)
                if (!force && baseVersion != current.version) {
                    return@dbQuery QueueWriteResult.Conflict(info(current, countEntries(userId)))
                }

                val rows = loadRows(userId).toMutableList()
                val applied = normalize(block(current, rows), rows)
                val now = Instant.now().toEpochMilli()
                val stored = applied.copy(
                    version = current.version + 1,
                    modifiedAt = now,
                    modifiedBySessionId = sessionId,
                    modifiedByDeviceName = sessionId?.let(::loadDeviceName)
                )

                persist(userId, stored, rows)

                if (sessionId != null) {
                    QueueSyncDeviceTable.update({ QueueSyncDeviceTable.sessionId eq sessionId }) {
                        it[lastSyncedVersion] = stored.version
                        it[lastSyncAt] = now
                    }
                }

                QueueWriteResult.Ok(info(stored, rows.size))
            }
        }

        if (result is QueueWriteResult.Ok) changes[userId]?.tryEmit(result.info)
        return result
    }

    private fun normalize(meta: Meta, rows: MutableList<Row>): Meta {
        val byOriginal = rows.sortedWith(compareBy<Row>({ it.position }, { it.queueId }))
        val originalIndex = byOriginal.withIndex().associate { (index, row) -> row.queueId to index }
        val shuffledIndex = if (meta.shuffleMode) {
            rows.sortedWith(compareBy<Row>({ it.shuffledPosition ?: Int.MAX_VALUE }, { it.queueId }))
                .withIndex()
                .associate { (index, row) -> row.queueId to index }
        } else {
            emptyMap()
        }

        val normalized = byOriginal.map { row ->
            row.copy(position = originalIndex.getValue(row.queueId), shuffledPosition = shuffledIndex[row.queueId])
        }
        rows.clear()
        rows.addAll(normalized)

        return meta.copy(currentIndex = if (rows.isEmpty()) 0 else meta.currentIndex.coerceIn(0, rows.size - 1))
    }

    private fun persist(userId: UUID, meta: Meta, rows: List<Row>) {
        UserQueueEntryTable.deleteWhere { UserQueueEntryTable.userId eq userId }

        rows.chunked(LOOKUP_CHUNK).forEach { chunk ->
            UserQueueEntryTable.batchInsert(chunk) { row ->
                this[UserQueueEntryTable.userId] = userId
                this[UserQueueEntryTable.queueId] = row.queueId
                this[UserQueueEntryTable.songId] = row.songId
                this[UserQueueEntryTable.position] = row.position
                this[UserQueueEntryTable.shuffledPosition] = row.shuffledPosition
                this[UserQueueEntryTable.explicit] = row.explicit
            }
        }

        UserQueueTable.upsert(UserQueueTable.userId) {
            it[UserQueueTable.userId] = userId
            it[version] = meta.version
            it[modifiedAt] = meta.modifiedAt
            it[modifiedBySessionId] = meta.modifiedBySessionId
            it[modifiedByDeviceName] = meta.modifiedByDeviceName
            it[currentIndex] = meta.currentIndex
            it[shuffleMode] = meta.shuffleMode
            it[repeatMode] = meta.repeatMode
            it[sourceId] = meta.sourceId
        }
    }

    private fun activeOrder(rows: List<Row>, shuffleMode: Boolean): List<Row> =
        if (shuffleMode) rows.sortedWith(compareBy<Row>({ it.shuffledPosition ?: Int.MAX_VALUE }, { it.queueId }))
        else rows.sortedWith(compareBy<Row>({ it.position }, { it.queueId }))

    private fun existingSongs(ids: List<UUID>): Set<UUID> =
        ids.distinct().chunked(LOOKUP_CHUNK).flatMapTo(mutableSetOf()) { chunk ->
            SongTable
                .select(SongTable.id)
                .where { SongTable.id inList chunk }
                .map { it[SongTable.id].value }
        }

    private fun requireDistinctIds(items: List<QueueItem>) {
        val seen = mutableSetOf<Long>()
        val duplicate = items.firstOrNull { !seen.add(it.queueId) }
        require(duplicate == null) { "Duplicate queue id ${duplicate?.queueId} in the payload" }
    }

    private fun staging(userId: UUID, uploadId: UUID): Staging {
        val staging = uploads[uploadId]
        require(staging != null && staging.userId == userId) { "Unknown or expired upload $uploadId" }
        return staging
    }

    private fun purgeExpired() {
        val now = Instant.now().toEpochMilli()
        uploads.entries.removeIf { it.value.expiresAt <= now }
        activeUploads.entries.removeIf { !uploads.containsKey(it.value) }
    }

    private fun lock(userId: UUID): Mutex = locks.computeIfAbsent(userId) { Mutex() }

    private fun changeFlow(userId: UUID): MutableSharedFlow<QueueInfo> = changes.computeIfAbsent(userId) {
        MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    private fun loadMeta(userId: UUID): Meta = UserQueueTable
        .selectAll()
        .where { UserQueueTable.userId eq userId }
        .singleOrNull()
        ?.let { row ->
            Meta(
                version = row[UserQueueTable.version],
                modifiedAt = row[UserQueueTable.modifiedAt],
                modifiedBySessionId = row[UserQueueTable.modifiedBySessionId],
                modifiedByDeviceName = row[UserQueueTable.modifiedByDeviceName],
                currentIndex = row[UserQueueTable.currentIndex],
                shuffleMode = row[UserQueueTable.shuffleMode],
                repeatMode = row[UserQueueTable.repeatMode],
                sourceId = row[UserQueueTable.sourceId]
            )
        } ?: Meta(0, 0, null, null, 0, false, RepeatMode.OFF, null)

    private fun loadRows(userId: UUID): List<Row> = UserQueueEntryTable
        .selectAll()
        .where { UserQueueEntryTable.userId eq userId }
        .orderBy(UserQueueEntryTable.position to SortOrder.ASC, UserQueueEntryTable.queueId to SortOrder.ASC)
        .map(::mapRow)

    private fun loadDeviceName(sessionId: UUID): String? = QueueSyncDeviceTable
        .select(QueueSyncDeviceTable.deviceName)
        .where { QueueSyncDeviceTable.sessionId eq sessionId }
        .singleOrNull()
        ?.get(QueueSyncDeviceTable.deviceName)
        ?.takeIf { it.isNotBlank() }

    private fun countEntries(userId: UUID): Int = UserQueueEntryTable
        .selectAll()
        .where { UserQueueEntryTable.userId eq userId }
        .count()
        .toInt()

    private fun mapRow(row: ResultRow): Row = Row(
        queueId = row[UserQueueEntryTable.queueId],
        songId = row[UserQueueEntryTable.songId].value,
        position = row[UserQueueEntryTable.position],
        shuffledPosition = row[UserQueueEntryTable.shuffledPosition],
        explicit = row[UserQueueEntryTable.explicit]
    )

    private fun info(meta: Meta, total: Int): QueueInfo = QueueInfo(
        version = meta.version,
        modifiedAt = meta.modifiedAt,
        modifiedBySessionId = meta.modifiedBySessionId,
        modifiedByDeviceName = meta.modifiedByDeviceName,
        currentIndex = meta.currentIndex,
        shuffleMode = meta.shuffleMode,
        repeatMode = meta.repeatMode,
        sourceId = meta.sourceId,
        total = total
    )
}
