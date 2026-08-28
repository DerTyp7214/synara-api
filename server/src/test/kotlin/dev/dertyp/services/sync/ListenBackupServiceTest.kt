package dev.dertyp.services.sync

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.ListenBackupConfig
import dev.dertyp.db.*
import dev.dertyp.listenbackup.ListenBackupBatch
import dev.dertyp.listenbackup.ListenBackupProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class ListenBackupServiceTest {
    private lateinit var database: Database
    private lateinit var service: ListenBackupService
    private val received = CopyOnWriteArrayList<ListenBackupBatch>()
    private val receivedKeys = CopyOnWriteArrayList<String?>()
    private var failNext = false
    private val json = Json { ignoreUnknownKeys = true }

    private fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "listen_backup_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                SongTable, SongVariantTable,
                ListenBrainzUserTable,
                ListenTable,
                ListenBackupConfigTable,
            )
        }
        val engine = MockEngine { request ->
            receivedKeys.add(request.headers[ListenBackupProtocol.KEY_HEADER])
            when {
                failNext -> {
                    failNext = false
                    respond("boom", HttpStatusCode.InternalServerError)
                }
                request.url.encodedPath.endsWith(ListenBackupProtocol.LISTENS_PATH) -> {
                    val body = (request.body as TextContent).text
                    val batch = json.decodeFromString<ListenBackupBatch>(body)
                    received.add(batch)
                    respond(
                        """{"received":${batch.listens.size}}""",
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith(ListenBackupProtocol.HEALTH_PATH) -> respond(
                    """{"ok":true,"listenCount":3}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.endsWith(ListenBackupProtocol.STATUS_PATH) -> respond(
                    """{"serverId":null,"listenCount":3,"lastReceivedAt":null,"maxUpdatedAt":null}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> respond("not found", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        service = ListenBackupService().apply { httpClient = client }
    }

    @AfterEach
    fun tearDown() {
        received.clear()
        receivedKeys.clear()
        failNext = false
        TestDatabase.cleanUp()
    }

    private fun insertUser(): UUID = transaction(database) {
        val uid = UUID.randomUUID()
        UserTable.insert {
            it[id] = uid
            it[username] = "user_$uid"
            it[passwordHash] = "x"
        }
        uid
    }

    private fun insertListen(userId: UUID, source: ListenSource, updatedAt: Long, msPlayed: Long? = 200_000L): UUID = transaction(database) {
        val id = UUID.randomUUID()
        ListenTable.insert {
            it[ListenTable.id] = id
            it[ListenTable.userId] = userId
            it[listenedAt] = updatedAt
            it[listenSource] = source
            it[ListenTable.msPlayed] = msPlayed
            it[ListenTable.updatedAt] = updatedAt
            it[trackName] = "Track $updatedAt"
        }
        id
    }

    private fun receivedIds(): List<UUID> = received.flatMap { batch -> batch.listens.map { it.id } }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `sync is skipped until enabled and configured`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = insertUser()
        insertListen(user, ListenSource.LOCAL, 1000L)

        assertEquals("disabled", service.sync { _, _ -> }["skipped"])
        service.updateConfig(ListenBackupConfig(enabled = false, url = ""))
        assertEquals("disabled", service.sync { _, _ -> }["skipped"])
        assertTrue(received.isEmpty())

        val state = service.getState()
        assertEquals(1L, state.pendingCount)
        assertFalse(state.hasKey)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `sync pushes only local listens and advances the cursor`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = insertUser()
        val local1 = insertListen(user, ListenSource.LOCAL, 1000L)
        val local2 = insertListen(user, ListenSource.LOCAL, 2000L)
        insertListen(user, ListenSource.LISTENBRAINZ, 3000L)

        service.updateConfig(ListenBackupConfig(enabled = true, url = "http://backup.local:8082/", key = "secret"))
        val result = service.sync { _, _ -> }

        assertEquals(2, result["sent"])
        assertEquals(setOf(local1, local2), receivedIds().toSet())
        assertTrue(receivedKeys.all { it == "secret" })
        val serverId = service.getState().serverId
        assertTrue(received.all { it.serverId == serverId })
        assertEquals(200_000L, received.first().listens.first().msPlayed)

        val state = service.getState()
        assertEquals(2000L, state.lastSyncedUpdatedAt)
        assertEquals(0L, state.pendingCount)
        assertEquals(2, state.lastSyncedCount)
        assertNull(state.lastError)
        assertNotNull(state.lastSyncAt)
        assertTrue(state.hasKey)
        assertNull(state.config.key)

        received.clear()
        assertEquals(0, service.sync { _, _ -> }["sent"])
        assertTrue(received.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `locally updated listens are pushed again`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = insertUser()
        val local1 = insertListen(user, ListenSource.LOCAL, 1000L)
        insertListen(user, ListenSource.LOCAL, 2000L)
        service.updateConfig(ListenBackupConfig(enabled = true, url = "http://backup.local:8082"))
        service.sync { _, _ -> }
        received.clear()

        transaction(database) {
            ListenTable.update({ ListenTable.id eq local1 }) {
                it[msPlayed] = 999L
                it[updatedAt] = 5000L
            }
        }

        assertEquals(1L, service.getState().pendingCount)
        assertEquals(1, service.sync { _, _ -> }["sent"])
        assertEquals(listOf(local1), receivedIds())
        assertEquals(999L, received.single().listens.single().msPlayed)
        assertEquals(5000L, service.getState().lastSyncedUpdatedAt)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resetCursor pushes everything again and batches respect batchSize`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = insertUser()
        val ids = (1..5).map { insertListen(user, ListenSource.LOCAL, it * 1000L) }
        val sameStamp = (1..3).map { insertListen(user, ListenSource.LOCAL, 9000L) }
        service.updateConfig(ListenBackupConfig(enabled = true, url = "http://backup.local:8082", batchSize = 2))

        assertEquals(8, service.sync { _, _ -> }["sent"])
        assertEquals((ids + sameStamp).toSet(), receivedIds().toSet())
        assertEquals(8, receivedIds().size)
        assertTrue(received.all { it.listens.size <= 2 })
        received.clear()

        service.resetCursor()
        assertEquals(0L, service.getState().lastSyncedUpdatedAt)
        assertEquals(8L, service.getState().pendingCount)
        assertEquals(8, service.sync { _, _ -> }["sent"])
        assertEquals((ids + sameStamp).toSet(), receivedIds().toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `receiver failure is recorded and cursor is kept`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val user = insertUser()
        insertListen(user, ListenSource.LOCAL, 1000L)
        service.updateConfig(ListenBackupConfig(enabled = true, url = "http://backup.local:8082"))

        failNext = true
        assertThrows<IllegalStateException> { service.sync { _, _ -> } }
        val failed = service.getState()
        assertTrue(failed.lastError!!.contains("500"))
        assertEquals(0L, failed.lastSyncedUpdatedAt)
        assertEquals(1L, failed.pendingCount)

        assertEquals(1, service.sync { _, _ -> }["sent"])
        assertNull(service.getState().lastError)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateConfig validates input and keeps the key when null`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assertThrows<IllegalArgumentException> { service.updateConfig(ListenBackupConfig(enabled = true, url = "")) }
        assertThrows<IllegalArgumentException> { service.updateConfig(ListenBackupConfig(url = "ftp://x", batchSize = 10)) }
        assertThrows<IllegalArgumentException> { service.updateConfig(ListenBackupConfig(url = "http://x", batchSize = 0)) }

        service.updateConfig(ListenBackupConfig(enabled = true, url = "http://x", key = "k1", batchSize = 10))
        assertTrue(service.getState().hasKey)
        service.updateConfig(ListenBackupConfig(enabled = true, url = "http://y", key = null, batchSize = 10))
        assertTrue(service.getState().hasKey)
        assertEquals("http://y", service.getState().config.url)
        service.updateConfig(ListenBackupConfig(enabled = false, url = "http://y", key = "", batchSize = 10))
        assertFalse(service.getState().hasKey)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `testConnection reports remote count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assertFalse(service.testConnection(null).ok)

        val ok = service.testConnection(ListenBackupConfig(url = "http://backup.local:8082", key = "abc"))
        assertTrue(ok.ok)
        assertEquals(3L, ok.remoteListenCount)
        assertEquals(listOf(null, "abc"), receivedKeys)

        failNext = true
        val failed = service.testConnection(ListenBackupConfig(url = "http://backup.local:8082"))
        assertFalse(failed.ok)
        assertTrue(failed.message!!.contains("500"))
    }
}
