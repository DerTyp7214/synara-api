package dev.dertyp.services

import dev.dertyp.data.ClientDevice
import dev.dertyp.data.ClientSetting
import dev.dertyp.data.ClientSettingConflict
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.data.ClientSettingsChanges
import dev.dertyp.data.ClientSettingsSnapshot
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.db.ClientDeviceTable
import dev.dertyp.db.ClientSettingHistoryTable
import dev.dertyp.db.ClientSettingScopeTable
import dev.dertyp.db.ClientSettingTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

class ClientSettingsService : Service() {
    companion object {
        const val MAX_KEY_LENGTH = 255
        const val MAX_VALUE_BYTES = 64 * 1024
        const val MAX_ENTRIES_PER_SCOPE = 2000
        const val MAX_BATCH = 200
        const val MAX_HISTORY = 20
        const val MAX_PAGE = 1000
        const val MAX_DEVICE_ID_LENGTH = 64
        const val MAX_PLATFORM_LENGTH = 32

        val TOMBSTONE_MAX_AGE_MS = 30.days.inWholeMilliseconds
        val DEVICE_MAX_AGE_MS = 180.days.inWholeMilliseconds

        private const val LOOKUP_CHUNK = 1000
    }

    private data class Scope(val version: Long, val modifiedAt: Long, val purgedVersion: Long)

    private data class ScopeKey(val userId: UUID, val scope: ClientSettingScope, val deviceId: String)

    private data class Tombstone(val scopeKey: ScopeKey, val key: String, val version: Long)

    private data class HistoryKey(val scopeKey: ScopeKey, val key: String)

    private val locks = ConcurrentHashMap<UUID, Mutex>()
    private val changes = ConcurrentHashMap<UUID, MutableSharedFlow<ClientSettingsChange>>()

    fun observe(userId: UUID): Flow<ClientSettingsChange> = changeFlow(userId).asSharedFlow()

    suspend fun getSettings(
        userId: UUID,
        scope: ClientSettingScope,
        deviceId: String?,
        includeDeleted: Boolean
    ): List<ClientSetting> {
        val device = normalize(scope, deviceId)
        if (scope == ClientSettingScope.DEVICE) {
            lock(userId).withLock { dbQuery { touch(userId, device, Instant.now().toEpochMilli()) } }
        }
        return dbQuery { readEntries(userId, scope, device, includeDeleted) }
    }

    suspend fun getSnapshot(userId: UUID, deviceId: String): ClientSettingsSnapshot {
        val device = normalize(ClientSettingScope.DEVICE, deviceId)

        return lock(userId).withLock {
            dbQuery {
                touch(userId, device, Instant.now().toEpochMilli())

                val synced = loadScope(userId, ClientSettingScope.SYNCED, "")
                val own = loadScope(userId, ClientSettingScope.DEVICE, device)

                ClientSettingsSnapshot(
                    deviceId = device,
                    syncedVersion = synced.version,
                    deviceVersion = own.version,
                    entries = readEntries(userId, ClientSettingScope.SYNCED, "", false) +
                        readEntries(userId, ClientSettingScope.DEVICE, device, false)
                )
            }
        }
    }

    suspend fun getChanges(
        userId: UUID,
        scope: ClientSettingScope,
        deviceId: String?,
        sinceVersion: Long,
        limit: Int
    ): ClientSettingsChanges {
        val device = normalize(scope, deviceId)
        val size = limit.coerceIn(1, MAX_PAGE)

        if (scope == ClientSettingScope.DEVICE) {
            lock(userId).withLock { dbQuery { touch(userId, device, Instant.now().toEpochMilli()) } }
        }

        return dbQuery {
            val current = loadScope(userId, scope, device)
            val rows = ClientSettingTable
                .selectAll()
                .where { ClientSettingTable.userId eq userId }
                .andWhere { ClientSettingTable.scope eq scope }
                .andWhere { ClientSettingTable.deviceId eq device }
                .andWhere { ClientSettingTable.version greater sinceVersion }
                .orderBy(ClientSettingTable.version to SortOrder.ASC, ClientSettingTable.key to SortOrder.ASC)
                .limit(size + 1)
                .map(::mapRow)

            ClientSettingsChanges(
                scope = scope,
                deviceId = device.ifBlank { null },
                version = current.version,
                entries = rows.take(size),
                hasMore = rows.size > size,
                fullResync = sinceVersion in 1 until current.purgedVersion
            )
        }
    }

    suspend fun setSettings(
        userId: UUID,
        scope: ClientSettingScope,
        deviceId: String?,
        entries: List<ClientSettingWrite>,
        force: Boolean
    ): ClientSettingsWriteResult {
        val device = normalize(scope, deviceId)
        val writer = writerOf(deviceId)
        validate(entries)

        if (entries.isEmpty()) {
            val current = dbQuery { loadScope(userId, scope, device) }
            return ClientSettingsWriteResult.Ok(current.version, emptyList())
        }

        val result = lock(userId).withLock {
            dbQuery {
                val now = Instant.now().toEpochMilli()
                if (writer != null) touch(userId, writer, now)

                val current = loadScope(userId, scope, device)
                val stored = loadEntries(userId, scope, device, entries.map { it.key }).associateBy { it.key }

                if (!force) {
                    val conflicts = entries.mapNotNull { write ->
                        val existing = stored[write.key]
                        val accepted = when {
                            existing == null -> write.baseVersion == 0L
                            existing.deleted -> write.baseVersion == 0L || write.baseVersion == existing.version
                            else -> write.baseVersion == existing.version
                        }
                        if (accepted) null else ClientSettingConflict(write.key, write.baseVersion, existing)
                    }

                    if (conflicts.isNotEmpty()) {
                        return@dbQuery ClientSettingsWriteResult.Conflict(current.version, conflicts)
                    }
                }

                val applicable = entries.filter { write ->
                    val existing = stored[write.key]
                    write.value != null || (existing != null && !existing.deleted)
                }

                if (applicable.isEmpty()) return@dbQuery ClientSettingsWriteResult.Ok(current.version, emptyList())

                val live = countLive(userId, scope, device)
                val added = applicable.count { write ->
                    val existing = stored[write.key]
                    write.value != null && (existing == null || existing.deleted)
                }
                val removed = applicable.count { it.value == null }
                require(live + added - removed <= MAX_ENTRIES_PER_SCOPE) {
                    "A settings scope holds at most $MAX_ENTRIES_PER_SCOPE entries"
                }

                val nextVersion = current.version + 1
                val written = applicable.map { write ->
                    stored[write.key]?.let { previous -> writeHistory(userId, scope, device, previous) }
                    writeEntry(userId, scope, device, write.key, write.value, nextVersion, now, writer)
                    trimHistory(userId, scope, device, write.key, MAX_HISTORY)

                    ClientSetting(
                        key = write.key,
                        value = write.value,
                        deleted = write.value == null,
                        version = nextVersion,
                        modifiedAt = now,
                        modifiedByDeviceId = writer,
                        scope = scope,
                        deviceId = device.ifBlank { null }
                    )
                }

                writeScope(userId, scope, device, current.copy(version = nextVersion, modifiedAt = now))

                ClientSettingsWriteResult.Ok(nextVersion, written)
            }
        }

        if (result is ClientSettingsWriteResult.Ok && result.entries.isNotEmpty()) {
            changes[userId]?.tryEmit(
                ClientSettingsChange(
                    scope = scope,
                    deviceId = device.ifBlank { null },
                    version = result.version,
                    keys = result.entries.map { it.key },
                    modifiedByDeviceId = writer
                )
            )
        }

        return result
    }

    suspend fun getHistory(
        userId: UUID,
        scope: ClientSettingScope,
        deviceId: String?,
        key: String,
        limit: Int
    ): List<ClientSetting> {
        val device = normalize(scope, deviceId)
        val size = limit.coerceIn(1, MAX_PAGE)

        return dbQuery {
            ClientSettingHistoryTable
                .selectAll()
                .where { ClientSettingHistoryTable.userId eq userId }
                .andWhere { ClientSettingHistoryTable.scope eq scope }
                .andWhere { ClientSettingHistoryTable.deviceId eq device }
                .andWhere { ClientSettingHistoryTable.key eq key }
                .orderBy(ClientSettingHistoryTable.version to SortOrder.DESC)
                .limit(size)
                .map(::mapHistoryRow)
        }
    }

    suspend fun restore(
        userId: UUID,
        scope: ClientSettingScope,
        deviceId: String?,
        key: String,
        version: Long,
        force: Boolean
    ): ClientSettingsWriteResult {
        val device = normalize(scope, deviceId)

        val historic = dbQuery {
            ClientSettingHistoryTable
                .selectAll()
                .where { ClientSettingHistoryTable.userId eq userId }
                .andWhere { ClientSettingHistoryTable.scope eq scope }
                .andWhere { ClientSettingHistoryTable.deviceId eq device }
                .andWhere { ClientSettingHistoryTable.key eq key }
                .andWhere { ClientSettingHistoryTable.version eq version }
                .singleOrNull()
                ?.let(::mapHistoryRow)
        }

        require(historic != null) { "No history entry for $key at version $version" }
        require(!historic.deleted) { "The history entry for $key at version $version is a deletion" }

        val base = dbQuery { loadEntries(userId, scope, device, listOf(key)).firstOrNull()?.version ?: 0 }

        return setSettings(
            userId = userId,
            scope = scope,
            deviceId = deviceId,
            entries = listOf(ClientSettingWrite(key, historic.value, base)),
            force = force
        )
    }

    suspend fun getDevices(userId: UUID): List<ClientDevice> = dbQuery {
        val versions = scopeVersions(userId)

        ClientDeviceTable
            .selectAll()
            .where { ClientDeviceTable.userId eq userId }
            .orderBy(ClientDeviceTable.lastSeenAt to SortOrder.DESC)
            .map { row -> mapDevice(row, versions[row[ClientDeviceTable.deviceId]] ?: 0) }
    }

    suspend fun registerDevice(userId: UUID, deviceId: String, name: String, platform: String): ClientDevice {
        val device = normalize(ClientSettingScope.DEVICE, deviceId)

        return lock(userId).withLock {
            dbQuery {
                touch(userId, device, Instant.now().toEpochMilli(), name, platform)

                val row = ClientDeviceTable
                    .selectAll()
                    .where { ClientDeviceTable.userId eq userId }
                    .andWhere { ClientDeviceTable.deviceId eq device }
                    .single()

                mapDevice(row, loadScope(userId, ClientSettingScope.DEVICE, device).version)
            }
        }
    }

    suspend fun deleteDevice(userId: UUID, deviceId: String) {
        val device = normalize(ClientSettingScope.DEVICE, deviceId)
        lock(userId).withLock { dbQuery { purgeDevice(userId, device) } }
    }

    suspend fun cleanup(
        tombstoneMaxAgeMs: Long = TOMBSTONE_MAX_AGE_MS,
        deviceMaxAgeMs: Long = DEVICE_MAX_AGE_MS,
        historyLimit: Int = MAX_HISTORY,
        onProgress: suspend (Double, String) -> Unit = { _, _ -> }
    ): Map<String, Int> {
        val now = Instant.now().toEpochMilli()
        val tombstoneCutoff = now - tombstoneMaxAgeMs
        val deviceCutoff = now - deviceMaxAgeMs

        val expired = dbQuery {
            ClientSettingTable
                .selectAll()
                .where { ClientSettingTable.deleted eq true }
                .andWhere { ClientSettingTable.modifiedAt less tombstoneCutoff }
                .map { row ->
                    Tombstone(
                        scopeKey = ScopeKey(
                            userId = row[ClientSettingTable.userId].value,
                            scope = row[ClientSettingTable.scope],
                            deviceId = row[ClientSettingTable.deviceId]
                        ),
                        key = row[ClientSettingTable.key],
                        version = row[ClientSettingTable.version]
                    )
                }
        }

        onProgress(0.0, "Found ${expired.size} expired tombstones to purge")

        val expiredByUser = expired.groupBy { it.scopeKey.userId }
        var tombstonesPurged = 0

        expiredByUser.entries.forEachIndexed { index, (owner, tombstones) ->
            onProgress(
                (index.toDouble() / expiredByUser.size) * 33.0,
                "Purging tombstones of user ${index + 1}/${expiredByUser.size}"
            )

            lock(owner).withLock {
                dbQuery {
                    tombstones.groupBy { it.scopeKey }.forEach { (scopeKey, rows) ->
                        val current = loadScope(scopeKey.userId, scopeKey.scope, scopeKey.deviceId)
                        val highest = rows.maxOf { it.version }
                        writeScope(
                            scopeKey.userId,
                            scopeKey.scope,
                            scopeKey.deviceId,
                            current.copy(purgedVersion = maxOf(current.purgedVersion, highest))
                        )
                        purgeKeys(scopeKey, rows.map { it.key })
                    }
                }
            }

            tombstonesPurged += tombstones.size
        }

        val historyKeys = dbQuery {
            ClientSettingHistoryTable
                .select(
                    ClientSettingHistoryTable.userId,
                    ClientSettingHistoryTable.scope,
                    ClientSettingHistoryTable.deviceId,
                    ClientSettingHistoryTable.key
                )
                .withDistinct()
                .map { row ->
                    HistoryKey(
                        scopeKey = ScopeKey(
                            userId = row[ClientSettingHistoryTable.userId].value,
                            scope = row[ClientSettingHistoryTable.scope],
                            deviceId = row[ClientSettingHistoryTable.deviceId]
                        ),
                        key = row[ClientSettingHistoryTable.key]
                    )
                }
        }

        onProgress(33.0, "Checking the history of ${historyKeys.size} keys")

        val historyByUser = historyKeys.groupBy { it.scopeKey.userId }
        var historyTrimmed = 0

        historyByUser.entries.forEachIndexed { index, (owner, keys) ->
            onProgress(
                33.0 + (index.toDouble() / historyByUser.size) * 33.0,
                "Trimming history of user ${index + 1}/${historyByUser.size}"
            )

            historyTrimmed += lock(owner).withLock {
                dbQuery {
                    keys.sumOf { entry ->
                        trimHistory(
                            entry.scopeKey.userId,
                            entry.scopeKey.scope,
                            entry.scopeKey.deviceId,
                            entry.key,
                            historyLimit
                        )
                    }
                }
            }
        }

        val staleDevices = dbQuery {
            ClientDeviceTable
                .select(ClientDeviceTable.userId, ClientDeviceTable.deviceId)
                .where { ClientDeviceTable.lastSeenAt less deviceCutoff }
                .map { row -> row[ClientDeviceTable.userId].value to row[ClientDeviceTable.deviceId] }
        }

        onProgress(66.0, "Found ${staleDevices.size} stale devices to delete")

        staleDevices.forEachIndexed { index, (owner, device) ->
            onProgress(
                66.0 + (index.toDouble() / staleDevices.size) * 34.0,
                "Deleting device ${index + 1}/${staleDevices.size}"
            )

            lock(owner).withLock { dbQuery { purgeDevice(owner, device) } }
        }

        onProgress(
            100.0,
            "Purged $tombstonesPurged tombstones, trimmed $historyTrimmed history entries and deleted ${staleDevices.size} devices"
        )

        return mapOf(
            "tombstonesPurged" to tombstonesPurged,
            "historyTrimmed" to historyTrimmed,
            "devicesDeleted" to staleDevices.size
        )
    }

    private fun validate(entries: List<ClientSettingWrite>) {
        require(entries.size <= MAX_BATCH) { "A settings write holds at most $MAX_BATCH entries" }

        val seen = mutableSetOf<String>()
        entries.forEach { write ->
            require(write.key.isNotBlank()) { "A settings key must not be blank" }
            require(write.key.length <= MAX_KEY_LENGTH) {
                "The settings key ${write.key} is longer than $MAX_KEY_LENGTH characters"
            }
            require(seen.add(write.key)) { "Duplicate settings key ${write.key} in the payload" }

            val value = write.value
            if (value != null) {
                require(value.toByteArray().size <= MAX_VALUE_BYTES) {
                    "The value of ${write.key} is larger than $MAX_VALUE_BYTES bytes"
                }
                require(runCatching { Json.parseToJsonElement(value) }.isSuccess) {
                    "The value of ${write.key} is not valid JSON"
                }
            }
        }
    }

    private fun normalize(scope: ClientSettingScope, deviceId: String?): String {
        if (scope == ClientSettingScope.SYNCED) return ""

        val device = deviceId.orEmpty()
        require(device.isNotBlank()) { "The device scope requires a device id" }
        require(device.length <= MAX_DEVICE_ID_LENGTH) {
            "A device id is at most $MAX_DEVICE_ID_LENGTH characters long"
        }

        return device
    }

    private fun writerOf(deviceId: String?): String? {
        val device = deviceId?.ifBlank { null } ?: return null
        require(device.length <= MAX_DEVICE_ID_LENGTH) {
            "A device id is at most $MAX_DEVICE_ID_LENGTH characters long"
        }

        return device
    }

    private fun lock(userId: UUID): Mutex = locks.computeIfAbsent(userId) { Mutex() }

    private fun changeFlow(userId: UUID): MutableSharedFlow<ClientSettingsChange> = changes.computeIfAbsent(userId) {
        MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    private fun readEntries(
        owner: UUID,
        target: ClientSettingScope,
        device: String,
        includeDeleted: Boolean
    ): List<ClientSetting> {
        val query = ClientSettingTable
            .selectAll()
            .where { ClientSettingTable.userId eq owner }
            .andWhere { ClientSettingTable.scope eq target }
            .andWhere { ClientSettingTable.deviceId eq device }

        if (!includeDeleted) query.andWhere { ClientSettingTable.deleted eq false }

        return query.orderBy(ClientSettingTable.key to SortOrder.ASC).map(::mapRow)
    }

    private fun loadEntries(
        owner: UUID,
        target: ClientSettingScope,
        device: String,
        keys: List<String>
    ): List<ClientSetting> = keys.distinct().chunked(LOOKUP_CHUNK).flatMap { chunk ->
        ClientSettingTable
            .selectAll()
            .where { ClientSettingTable.userId eq owner }
            .andWhere { ClientSettingTable.scope eq target }
            .andWhere { ClientSettingTable.deviceId eq device }
            .andWhere { ClientSettingTable.key inList chunk }
            .map(::mapRow)
    }

    private fun countLive(owner: UUID, target: ClientSettingScope, device: String): Int = ClientSettingTable
        .selectAll()
        .where { ClientSettingTable.userId eq owner }
        .andWhere { ClientSettingTable.scope eq target }
        .andWhere { ClientSettingTable.deviceId eq device }
        .andWhere { ClientSettingTable.deleted eq false }
        .count()
        .toInt()

    private fun loadScope(owner: UUID, target: ClientSettingScope, device: String): Scope = ClientSettingScopeTable
        .selectAll()
        .where { ClientSettingScopeTable.userId eq owner }
        .andWhere { ClientSettingScopeTable.scope eq target }
        .andWhere { ClientSettingScopeTable.deviceId eq device }
        .singleOrNull()
        ?.let { row ->
            Scope(
                version = row[ClientSettingScopeTable.version],
                modifiedAt = row[ClientSettingScopeTable.modifiedAt],
                purgedVersion = row[ClientSettingScopeTable.purgedVersion]
            )
        } ?: Scope(0, 0, 0)

    private fun scopeVersions(owner: UUID): Map<String, Long> = ClientSettingScopeTable
        .select(ClientSettingScopeTable.deviceId, ClientSettingScopeTable.version)
        .where { ClientSettingScopeTable.userId eq owner }
        .andWhere { ClientSettingScopeTable.scope eq ClientSettingScope.DEVICE }
        .associate { row -> row[ClientSettingScopeTable.deviceId] to row[ClientSettingScopeTable.version] }

    private fun writeScope(owner: UUID, target: ClientSettingScope, device: String, state: Scope) {
        val updated = ClientSettingScopeTable.update({
            (ClientSettingScopeTable.userId eq owner) and
                (ClientSettingScopeTable.scope eq target) and
                (ClientSettingScopeTable.deviceId eq device)
        }) {
            it[version] = state.version
            it[modifiedAt] = state.modifiedAt
            it[purgedVersion] = state.purgedVersion
        }

        if (updated == 0) {
            ClientSettingScopeTable.insert {
                it[ClientSettingScopeTable.userId] = owner
                it[ClientSettingScopeTable.scope] = target
                it[ClientSettingScopeTable.deviceId] = device
                it[version] = state.version
                it[modifiedAt] = state.modifiedAt
                it[purgedVersion] = state.purgedVersion
            }
        }
    }

    private fun writeEntry(
        owner: UUID,
        target: ClientSettingScope,
        device: String,
        entryKey: String,
        entryValue: String?,
        entryVersion: Long,
        at: Long,
        writer: String?
    ) {
        val updated = ClientSettingTable.update({
            (ClientSettingTable.userId eq owner) and
                (ClientSettingTable.scope eq target) and
                (ClientSettingTable.deviceId eq device) and
                (ClientSettingTable.key eq entryKey)
        }) {
            it[value] = entryValue
            it[deleted] = entryValue == null
            it[version] = entryVersion
            it[modifiedAt] = at
            it[modifiedByDeviceId] = writer
        }

        if (updated == 0) {
            ClientSettingTable.insert {
                it[ClientSettingTable.userId] = owner
                it[ClientSettingTable.scope] = target
                it[ClientSettingTable.deviceId] = device
                it[key] = entryKey
                it[value] = entryValue
                it[deleted] = entryValue == null
                it[version] = entryVersion
                it[modifiedAt] = at
                it[modifiedByDeviceId] = writer
            }
        }
    }

    private fun writeHistory(
        owner: UUID,
        target: ClientSettingScope,
        device: String,
        previous: ClientSetting
    ) {
        ClientSettingHistoryTable.insert {
            it[ClientSettingHistoryTable.userId] = owner
            it[ClientSettingHistoryTable.scope] = target
            it[ClientSettingHistoryTable.deviceId] = device
            it[key] = previous.key
            it[value] = previous.value
            it[deleted] = previous.deleted
            it[version] = previous.version
            it[modifiedAt] = previous.modifiedAt
            it[modifiedByDeviceId] = previous.modifiedByDeviceId
        }
    }

    private fun trimHistory(
        owner: UUID,
        target: ClientSettingScope,
        device: String,
        entryKey: String,
        historyLimit: Int
    ): Int {
        val limit = historyLimit.coerceAtLeast(1)
        val versions = ClientSettingHistoryTable
            .select(ClientSettingHistoryTable.version)
            .where { ClientSettingHistoryTable.userId eq owner }
            .andWhere { ClientSettingHistoryTable.scope eq target }
            .andWhere { ClientSettingHistoryTable.deviceId eq device }
            .andWhere { ClientSettingHistoryTable.key eq entryKey }
            .orderBy(ClientSettingHistoryTable.version to SortOrder.DESC)
            .limit(limit)
            .map { row -> row[ClientSettingHistoryTable.version] }

        if (versions.size < limit) return 0
        val threshold = versions.last()

        return ClientSettingHistoryTable.deleteWhere {
            (ClientSettingHistoryTable.userId eq owner) and
                (ClientSettingHistoryTable.scope eq target) and
                (ClientSettingHistoryTable.deviceId eq device) and
                (ClientSettingHistoryTable.key eq entryKey) and
                (ClientSettingHistoryTable.version less threshold)
        }
    }

    private fun purgeKeys(scopeKey: ScopeKey, keys: List<String>) {
        keys.distinct().chunked(LOOKUP_CHUNK).forEach { chunk ->
            ClientSettingTable.deleteWhere {
                (ClientSettingTable.userId eq scopeKey.userId) and
                    (ClientSettingTable.scope eq scopeKey.scope) and
                    (ClientSettingTable.deviceId eq scopeKey.deviceId) and
                    (ClientSettingTable.deleted eq true) and
                    (ClientSettingTable.key inList chunk)
            }

            ClientSettingHistoryTable.deleteWhere {
                (ClientSettingHistoryTable.userId eq scopeKey.userId) and
                    (ClientSettingHistoryTable.scope eq scopeKey.scope) and
                    (ClientSettingHistoryTable.deviceId eq scopeKey.deviceId) and
                    (ClientSettingHistoryTable.key inList chunk)
            }
        }
    }

    private fun purgeDevice(owner: UUID, device: String) {
        ClientSettingHistoryTable.deleteWhere {
            (ClientSettingHistoryTable.userId eq owner) and
                (ClientSettingHistoryTable.scope eq ClientSettingScope.DEVICE) and
                (ClientSettingHistoryTable.deviceId eq device)
        }

        ClientSettingTable.deleteWhere {
            (ClientSettingTable.userId eq owner) and
                (ClientSettingTable.scope eq ClientSettingScope.DEVICE) and
                (ClientSettingTable.deviceId eq device)
        }

        ClientSettingScopeTable.deleteWhere {
            (ClientSettingScopeTable.userId eq owner) and
                (ClientSettingScopeTable.scope eq ClientSettingScope.DEVICE) and
                (ClientSettingScopeTable.deviceId eq device)
        }

        ClientDeviceTable.deleteWhere {
            (ClientDeviceTable.userId eq owner) and (ClientDeviceTable.deviceId eq device)
        }
    }

    private fun touch(
        owner: UUID,
        device: String,
        at: Long,
        deviceName: String? = null,
        devicePlatform: String? = null
    ) {
        val updated = ClientDeviceTable.update({
            (ClientDeviceTable.userId eq owner) and (ClientDeviceTable.deviceId eq device)
        }) {
            it[lastSeenAt] = at
            if (deviceName != null) it[name] = deviceName
            if (devicePlatform != null) it[platform] = devicePlatform.take(MAX_PLATFORM_LENGTH)
        }

        if (updated == 0) {
            ClientDeviceTable.insert {
                it[ClientDeviceTable.userId] = owner
                it[ClientDeviceTable.deviceId] = device
                it[name] = deviceName.orEmpty()
                it[platform] = devicePlatform.orEmpty().take(MAX_PLATFORM_LENGTH)
                it[createdAt] = at
                it[lastSeenAt] = at
            }
        }
    }

    private fun mapRow(row: ResultRow): ClientSetting = ClientSetting(
        key = row[ClientSettingTable.key],
        value = row[ClientSettingTable.value],
        deleted = row[ClientSettingTable.deleted],
        version = row[ClientSettingTable.version],
        modifiedAt = row[ClientSettingTable.modifiedAt],
        modifiedByDeviceId = row[ClientSettingTable.modifiedByDeviceId],
        scope = row[ClientSettingTable.scope],
        deviceId = row[ClientSettingTable.deviceId].ifBlank { null }
    )

    private fun mapHistoryRow(row: ResultRow): ClientSetting = ClientSetting(
        key = row[ClientSettingHistoryTable.key],
        value = row[ClientSettingHistoryTable.value],
        deleted = row[ClientSettingHistoryTable.deleted],
        version = row[ClientSettingHistoryTable.version],
        modifiedAt = row[ClientSettingHistoryTable.modifiedAt],
        modifiedByDeviceId = row[ClientSettingHistoryTable.modifiedByDeviceId],
        scope = row[ClientSettingHistoryTable.scope],
        deviceId = row[ClientSettingHistoryTable.deviceId].ifBlank { null }
    )

    private fun mapDevice(row: ResultRow, settingsVersion: Long): ClientDevice = ClientDevice(
        deviceId = row[ClientDeviceTable.deviceId],
        name = row[ClientDeviceTable.name],
        platform = row[ClientDeviceTable.platform],
        createdAt = row[ClientDeviceTable.createdAt],
        lastSeenAt = row[ClientDeviceTable.lastSeenAt],
        settingsVersion = settingsVersion
    )
}
