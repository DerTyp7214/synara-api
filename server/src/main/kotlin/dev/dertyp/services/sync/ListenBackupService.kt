package dev.dertyp.services.sync

import dev.dertyp.PlatformUUID
import dev.dertyp.data.ListenBackupConfig
import dev.dertyp.data.ListenBackupConnectionTest
import dev.dertyp.data.ListenBackupState
import dev.dertyp.db.ListenBackupConfigTable
import dev.dertyp.db.ListenSource
import dev.dertyp.db.ListenTable
import dev.dertyp.dbQuery
import dev.dertyp.listenbackup.BackupListen
import dev.dertyp.listenbackup.ListenBackupBatch
import dev.dertyp.listenbackup.ListenBackupBatchResult
import dev.dertyp.listenbackup.ListenBackupHealth
import dev.dertyp.listenbackup.ListenBackupProtocol
import dev.dertyp.listenbackup.ListenBackupStatus
import dev.dertyp.randomPlatformUUID
import dev.dertyp.services.IListenBackupService
import dev.dertyp.services.Service
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class RpcListenBackupService(private val service: ListenBackupService) : IListenBackupService {
    override suspend fun getState(): ListenBackupState = service.getState()
    override fun getStateFlow(): Flow<ListenBackupState> = service.stateFlow
    override suspend fun updateConfig(config: ListenBackupConfig): ListenBackupState = service.updateConfig(config)
    override suspend fun testConnection(config: ListenBackupConfig?): ListenBackupConnectionTest = service.testConnection(config)
    override suspend fun syncNow(): ListenBackupState {
        service.sync { _, _ -> }
        return service.getState()
    }
    override suspend fun resetCursor(): ListenBackupState = service.resetCursor()
}

class ListenBackupService : Service() {
    companion object {
        const val MAX_BATCH_SIZE = 10000
    }

    internal var httpClient: HttpClient = HttpClient(CIO) {
        install(UserAgent) { agent = "Synara/ListenBackup" }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 120_000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val syncMutex = Mutex()
    private val _stateFlow = MutableSharedFlow<ListenBackupState>(replay = 1)
    val stateFlow: Flow<ListenBackupState> = _stateFlow.onStart { emit(getState()) }

    private data class StoredConfig(
        val enabled: Boolean,
        val url: String,
        val apiKey: String?,
        val batchSize: Int,
        val serverId: PlatformUUID,
        val lastSyncedUpdatedAt: Long,
        val lastSyncAt: Long?,
        val lastSyncedCount: Int,
        val lastError: String?,
    ) {
        val isConfigured get() = url.isNotBlank()
        val baseUrl get() = url.trim().removeSuffix("/")
    }

    private fun readStored(): StoredConfig {
        val row = ListenBackupConfigTable.selectAll()
            .where { ListenBackupConfigTable.id eq ListenBackupConfigTable.DEFAULT_KEY }
            .singleOrNull()
        if (row == null) {
            val serverId = randomPlatformUUID()
            ListenBackupConfigTable.insert {
                it[id] = ListenBackupConfigTable.DEFAULT_KEY
                it[ListenBackupConfigTable.serverId] = serverId
            }
            return StoredConfig(false, "", null, 1000, serverId, 0L, null, 0, null)
        }
        return StoredConfig(
            enabled = row[ListenBackupConfigTable.enabled],
            url = row[ListenBackupConfigTable.url],
            apiKey = row[ListenBackupConfigTable.apiKey],
            batchSize = row[ListenBackupConfigTable.batchSize],
            serverId = row[ListenBackupConfigTable.serverId],
            lastSyncedUpdatedAt = row[ListenBackupConfigTable.lastSyncedUpdatedAt],
            lastSyncAt = row[ListenBackupConfigTable.lastSyncAt],
            lastSyncedCount = row[ListenBackupConfigTable.lastSyncedCount],
            lastError = row[ListenBackupConfigTable.lastError],
        )
    }

    private fun pendingCount(cursor: Long): Long {
        val countExpr = ListenTable.id.count()
        return ListenTable.select(countExpr)
            .where { ListenTable.listenSource eq ListenSource.LOCAL }
            .andWhere { ListenTable.updatedAt greater cursor }
            .single()[countExpr]
    }

    private fun StoredConfig.toState(pending: Long) = ListenBackupState(
        config = ListenBackupConfig(enabled = enabled, url = url, key = null, batchSize = batchSize),
        hasKey = !apiKey.isNullOrEmpty(),
        serverId = serverId,
        lastSyncAt = lastSyncAt,
        lastSyncedUpdatedAt = lastSyncedUpdatedAt,
        lastSyncedCount = lastSyncedCount,
        lastError = lastError,
        pendingCount = pending,
    )

    suspend fun getState(): ListenBackupState = dbQuery {
        val stored = readStored()
        stored.toState(pendingCount(stored.lastSyncedUpdatedAt))
    }

    private suspend fun emitState(): ListenBackupState = getState().also { _stateFlow.emit(it) }

    suspend fun updateConfig(config: ListenBackupConfig): ListenBackupState {
        require(config.batchSize in 1..MAX_BATCH_SIZE) { "batchSize must be between 1 and $MAX_BATCH_SIZE" }
        val url = config.url.trim()
        require(!config.enabled || url.isNotEmpty()) { "url is required when the backup is enabled" }
        require(url.isEmpty() || url.startsWith("http://") || url.startsWith("https://")) { "url must start with http:// or https://" }

        dbQuery {
            readStored()
            ListenBackupConfigTable.update({ ListenBackupConfigTable.id eq ListenBackupConfigTable.DEFAULT_KEY }) {
                it[enabled] = config.enabled
                it[ListenBackupConfigTable.url] = url
                if (config.key != null) it[apiKey] = config.key!!.ifEmpty { null }
                it[batchSize] = config.batchSize
            }
        }
        return emitState()
    }

    suspend fun resetCursor(): ListenBackupState {
        dbQuery {
            readStored()
            ListenBackupConfigTable.update({ ListenBackupConfigTable.id eq ListenBackupConfigTable.DEFAULT_KEY }) {
                it[lastSyncedUpdatedAt] = 0L
                it[lastError] = null
            }
        }
        return emitState()
    }

    suspend fun testConnection(config: ListenBackupConfig?): ListenBackupConnectionTest {
        val stored = dbQuery { readStored() }
        val target = if (config == null) stored else stored.copy(
            url = config.url,
            apiKey = config.key ?: stored.apiKey,
        )
        if (!target.isConfigured) return ListenBackupConnectionTest(false, "No receiver URL configured")

        return try {
            val health = httpClient.get("${target.baseUrl}${ListenBackupProtocol.HEALTH_PATH}").checked().body<ListenBackupHealth>()
            if (!health.ok) return ListenBackupConnectionTest(false, "Receiver reports unhealthy")
            val status = httpClient.get("${target.baseUrl}${ListenBackupProtocol.STATUS_PATH}") {
                target.apiKey?.let { header(ListenBackupProtocol.KEY_HEADER, it) }
                parameter(ListenBackupProtocol.SERVER_ID_PARAM, target.serverId.toString())
            }.checked().body<ListenBackupStatus>()
            ListenBackupConnectionTest(true, remoteListenCount = status.listenCount)
        } catch (e: Exception) {
            logger.warn("Listen backup connection test failed: ${e.message}")
            ListenBackupConnectionTest(false, e.message ?: e::class.simpleName)
        }
    }

    suspend fun sync(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> = syncMutex.withLock {
        val config = dbQuery { readStored() }
        if (!config.enabled) return mapOf("skipped" to "disabled")
        if (!config.isConfigured) return mapOf("skipped" to "no receiver URL configured")

        var cursor = config.lastSyncedUpdatedAt
        val total = dbQuery { pendingCount(cursor) }
        var sent = 0
        onProgress(0.0, "Pushing $total listen(s) to ${config.baseUrl}")

        try {
            while (true) {
                val page = dbQuery { loadPage(cursor, config.batchSize) }
                if (page.isEmpty()) break

                val batches = if (page.size < config.batchSize) {
                    listOf(page)
                } else {
                    val maxUpdatedAt = page.last().updatedAt
                    val complete = page.filter { it.updatedAt < maxUpdatedAt }
                    if (complete.isNotEmpty()) listOf(complete)
                    else dbQuery { loadAllAt(maxUpdatedAt) }.chunked(config.batchSize)
                }

                for (batch in batches) {
                    push(config, batch)
                    sent += batch.size
                    cursor = batch.maxOf { it.updatedAt }
                    dbQuery {
                        ListenBackupConfigTable.update({ ListenBackupConfigTable.id eq ListenBackupConfigTable.DEFAULT_KEY }) {
                            it[lastSyncedUpdatedAt] = cursor
                            it[lastSyncAt] = System.currentTimeMillis()
                            it[lastSyncedCount] = sent
                            it[lastError] = null
                        }
                    }
                    onProgress(if (total > 0) (sent.toDouble() / total).coerceAtMost(1.0) else 1.0, "Pushed $sent/$total listen(s)")
                }
            }
        } catch (e: Exception) {
            dbQuery {
                ListenBackupConfigTable.update({ ListenBackupConfigTable.id eq ListenBackupConfigTable.DEFAULT_KEY }) {
                    it[lastSyncAt] = System.currentTimeMillis()
                    it[lastSyncedCount] = sent
                    it[lastError] = e.message ?: e::class.simpleName
                }
            }
            emitState()
            throw e
        }

        if (sent == 0) {
            dbQuery {
                ListenBackupConfigTable.update({ ListenBackupConfigTable.id eq ListenBackupConfigTable.DEFAULT_KEY }) {
                    it[lastSyncAt] = System.currentTimeMillis()
                    it[lastSyncedCount] = 0
                    it[lastError] = null
                }
            }
        }
        emitState()
        onProgress(1.0, "Pushed $sent listen(s)")
        mapOf("sent" to sent, "pending" to total)
    }

    private fun loadPage(cursor: Long, limit: Int): List<BackupListen> =
        ListenTable.selectAll()
            .where { ListenTable.listenSource eq ListenSource.LOCAL }
            .andWhere { ListenTable.updatedAt greater cursor }
            .orderBy(ListenTable.updatedAt to SortOrder.ASC, ListenTable.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toBackupListen() }

    private fun loadAllAt(updatedAt: Long): List<BackupListen> =
        ListenTable.selectAll()
            .where { (ListenTable.listenSource eq ListenSource.LOCAL) and (ListenTable.updatedAt eq updatedAt) }
            .orderBy(ListenTable.id to SortOrder.ASC)
            .map { it.toBackupListen() }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toBackupListen() = BackupListen(
        id = this[ListenTable.id].value,
        userId = this[ListenTable.userId]?.value,
        songId = this[ListenTable.songId]?.value,
        recordingMbid = this[ListenTable.recordingMbid],
        recordingMsid = this[ListenTable.recordingMsid],
        releaseMbid = this[ListenTable.releaseMbid],
        isrcs = this[ListenTable.isrcs],
        artistMbids = this[ListenTable.artistMbids],
        trackName = this[ListenTable.trackName],
        artistName = this[ListenTable.artistName],
        releaseName = this[ListenTable.releaseName],
        listenedAt = this[ListenTable.listenedAt],
        msPlayed = this[ListenTable.msPlayed],
        updatedAt = this[ListenTable.updatedAt],
    )

    private suspend fun push(config: StoredConfig, batch: List<BackupListen>) {
        val result = httpClient.post("${config.baseUrl}${ListenBackupProtocol.LISTENS_PATH}") {
            config.apiKey?.let { header(ListenBackupProtocol.KEY_HEADER, it) }
            contentType(ContentType.Application.Json)
            setBody(ListenBackupBatch(config.serverId, batch))
        }.checked().body<ListenBackupBatchResult>()
        check(result.received == batch.size) { "Receiver accepted ${result.received} of ${batch.size} listen(s)" }
    }

    private suspend fun HttpResponse.checked(): HttpResponse {
        if (status.value !in 200..299) {
            val body = runCatching { bodyAsText() }.getOrDefault("").take(200)
            throw IllegalStateException("Receiver responded ${status.value}${if (body.isNotBlank()) ": $body" else ""}")
        }
        return this
    }
}
