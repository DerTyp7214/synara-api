package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.User
import dev.dertyp.db.SessionTable
import dev.dertyp.db.UserTable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class RpcQueueServiceTest {
    private lateinit var database: Database
    private lateinit var sessionService: SessionService
    private val queueService = mockk<QueueService>(relaxed = true)
    private val clientRequestService = mockk<ClientRequestService>(relaxed = true)

    @BeforeEach
    fun setUp() {
        database = TestDatabase.connect(DbDialect.SQLITE, "rpc_queue_test")
        transaction(database) { SchemaUtils.create(UserTable, SessionTable) }
        sessionService = SessionService()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun insertUser(userId: UUID) {
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "user_$userId"
                it[passwordHash] = "hash"
            }
        }
    }

    @Test
    fun `requestUploadFrom of another user's session throws UnauthorizedException`() = runBlocking<Unit> {
        val callerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        insertUser(callerId)
        insertUser(otherUserId)
        val targetSessionId = sessionService.createSession(otherUserId, "agent", "127.0.0.1")

        val user = User(callerId, "caller", passwordHash = "hash")
        val service = RpcQueueService(user, UUID.randomUUID(), queueService, sessionService, clientRequestService)

        assertThrows<UnauthorizedException> {
            runBlocking { service.requestUploadFrom(targetSessionId) }
        }
    }

    @Test
    fun `requestUploadFrom forwards UploadQueue carrying the caller session id`() = runBlocking {
        val callerId = UUID.randomUUID()
        insertUser(callerId)
        val targetSessionId = sessionService.createSession(callerId, "agent", "127.0.0.1")
        val callerSessionId = UUID.randomUUID()

        coEvery { queueService.deviceName(callerSessionId) } returns "Living Room"
        var captured: ClientRequest.UploadQueue? = null
        coEvery { clientRequestService.request(targetSessionId, 30.seconds, any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val build = thirdArg<(UUID, Long) -> ClientRequest>()
            captured = build(UUID.randomUUID(), 1L) as ClientRequest.UploadQueue
            ClientRequestStatus.COMPLETED
        }

        val user = User(callerId, "caller", passwordHash = "hash")
        val service = RpcQueueService(user, callerSessionId, queueService, sessionService, clientRequestService)

        val result = service.requestUploadFrom(targetSessionId)

        assertEquals(ClientRequestStatus.COMPLETED, result)
        assertEquals(callerSessionId, captured?.requestedBySessionId)
        assertEquals("Living Room", captured?.requestedByDeviceName)
    }

    @Test
    fun `commitUpload completes the client request only on Ok`() = runBlocking {
        val userId = UUID.randomUUID()
        val user = User(userId, "user", passwordHash = "hash")
        val sessionId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val meta = QueueMeta(currentIndex = 0, shuffleMode = false, repeatMode = RepeatMode.OFF)
        val info = QueueInfo(version = 1, modifiedAt = 0, currentIndex = 0, shuffleMode = false, repeatMode = RepeatMode.OFF, total = 0)
        val service = RpcQueueService(user, sessionId, queueService, sessionService, clientRequestService)

        coEvery { queueService.commitUpload(userId, uploadId, meta) } returns QueueWriteResult.Ok(info)
        service.commitUpload(uploadId, meta, requestId)
        coVerify(exactly = 1) { clientRequestService.complete(sessionId, requestId, ClientRequestStatus.COMPLETED) }

        coEvery { queueService.commitUpload(userId, uploadId, meta) } returns QueueWriteResult.Conflict(info)
        service.commitUpload(uploadId, meta, requestId)
        coVerify(exactly = 1) { clientRequestService.complete(any(), any(), any()) }
    }

    @Test
    fun `setSyncEnabled without a session throws IllegalStateException`() = runBlocking<Unit> {
        val user = User(UUID.randomUUID(), "user", passwordHash = "hash")
        val service = RpcQueueService(user, null, queueService, sessionService, clientRequestService)

        assertThrows<IllegalStateException> {
            runBlocking { service.setSyncEnabled(true, "device") }
        }
    }
}
