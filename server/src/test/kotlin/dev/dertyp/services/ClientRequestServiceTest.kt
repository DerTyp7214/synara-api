package dev.dertyp.services

import dev.dertyp.core.ChangeNotifier
import dev.dertyp.data.ChangeTopic
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClientRequestServiceTest : KoinTest {
    private val changeNotifier = mockk<ChangeNotifier>(relaxed = true)
    private val service = ClientRequestService()

    @BeforeEach
    fun setUp() {
        startKoin { modules(module { single { changeNotifier } }) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    private fun assertOnlineDeviceChanges(userId: UUID, expected: Int) {
        verify(exactly = expected) { changeNotifier.notify(userId, ChangeTopic.ONLINE_DEVICES) }
    }

    private fun buildUploadQueue(id: UUID, requestedAt: Long): ClientRequest =
        ClientRequest.UploadQueue(id, requestedAt, UUID.randomUUID())

    private fun description(deviceName: String, vararg capabilities: ClientCapability) = ClientDescription(
        deviceName = deviceName,
        platform = "Test",
        deviceId = "device-$deviceName",
        capabilities = capabilities.toSet(),
    )

    private fun TestScope.connect(
        userId: UUID,
        sessionId: UUID,
        description: ClientDescription,
        received: Channel<ClientRequest>? = null,
    ): Job = launch(UnconfinedTestDispatcher(testScheduler)) {
        service.connect(userId, sessionId, description).collect { received?.send(it) }
    }

    @Test
    fun `a request without a listening session is unreachable`() = runTest {
        val status = service.request(UUID.randomUUID(), 5.seconds, ::buildUploadQueue)

        assertEquals(ClientRequestStatus.UNREACHABLE, status)
    }

    @Test
    fun `a delivered request completes with the status the client reports`() = runTest {
        val sessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeRequests(sessionId).collect { received.send(it) }
        }

        val statusDeferred = async { service.request(sessionId, 5.seconds, ::buildUploadQueue) }
        val request = received.receive()
        val completed = service.complete(sessionId, request.id, ClientRequestStatus.COMPLETED)

        assertTrue(completed)
        assertEquals(ClientRequestStatus.COMPLETED, statusDeferred.await())
        job.cancel()
    }

    @Test
    fun `a rejected request is reported as rejected`() = runTest {
        val sessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeRequests(sessionId).collect { received.send(it) }
        }

        val statusDeferred = async { service.request(sessionId, 5.seconds, ::buildUploadQueue) }
        val request = received.receive()
        service.complete(sessionId, request.id, ClientRequestStatus.REJECTED)

        assertEquals(ClientRequestStatus.REJECTED, statusDeferred.await())
        job.cancel()
    }

    @Test
    fun `a silent client times out and the pending request is removed`() = runTest {
        val sessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeRequests(sessionId).collect { received.send(it) }
        }

        val status = service.request(sessionId, 50.milliseconds, ::buildUploadQueue)
        val request = received.receive()

        assertEquals(ClientRequestStatus.TIMED_OUT, status)
        assertFalse(service.complete(sessionId, request.id, ClientRequestStatus.COMPLETED))
        job.cancel()
    }

    @Test
    fun `completing from another session is refused`() = runTest {
        val sessionId = UUID.randomUUID()
        val otherSessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeRequests(sessionId).collect { received.send(it) }
        }

        val statusDeferred = async { service.request(sessionId, 5.seconds, ::buildUploadQueue) }
        val request = received.receive()

        assertFalse(service.complete(otherSessionId, request.id, ClientRequestStatus.COMPLETED))
        service.complete(sessionId, request.id, ClientRequestStatus.COMPLETED)
        assertEquals(ClientRequestStatus.COMPLETED, statusDeferred.await())
        job.cancel()
    }

    @Test
    fun `complete rejects non-terminal statuses`() {
        val sessionId = UUID.randomUUID()
        val requestId = UUID.randomUUID()

        assertThrows<IllegalArgumentException> { service.complete(sessionId, requestId, ClientRequestStatus.TIMED_OUT) }
        assertThrows<IllegalArgumentException> {
            service.complete(
                sessionId,
                requestId,
                ClientRequestStatus.UNREACHABLE
            )
        }
    }

    @Test
    fun `a connected session is listed as an online device with its description`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val before = System.currentTimeMillis()
        val job = connect(userId, sessionId, description("Desk", ClientCapability.REMOTE_CONTROL))

        val device = service.onlineDevices(userId, sessionId).single()

        assertEquals(sessionId, device.sessionId)
        assertEquals("Desk", device.deviceName)
        assertEquals("Test", device.platform)
        assertEquals("device-Desk", device.deviceId)
        assertEquals(setOf(ClientCapability.REMOTE_CONTROL), device.capabilities)
        assertTrue(device.isCurrent)
        assertTrue(device.connectedAt >= before)
        assertFalse(service.onlineDevices(userId, UUID.randomUUID()).single().isCurrent)
        assertTrue(service.isReachable(sessionId))
        assertTrue(service.has(sessionId, ClientCapability.REMOTE_CONTROL))
        assertFalse(service.has(sessionId, ClientCapability.QUEUE_SYNC))
        assertEquals("Desk", service.deviceName(sessionId))
        assertEquals(userId, service.connection(sessionId)?.userId)
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling the connect collector removes the online device`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = connect(userId, sessionId, description("Desk"))
        assertEquals(1, service.onlineDevices(userId, null).size)

        job.cancelAndJoin()

        assertTrue(service.onlineDevices(userId, null).isEmpty())
        assertNull(service.connection(sessionId))
        assertNull(service.deviceName(sessionId))
        assertFalse(service.isReachable(sessionId))
        assertFalse(service.has(sessionId, ClientCapability.REMOTE_CONTROL))
    }

    @Test
    fun `an observeRequests subscriber is reachable but not listed as an online device`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeRequests(sessionId).collect { }
        }

        assertTrue(service.isReachable(sessionId))
        assertTrue(service.onlineDevices(userId, sessionId).isEmpty())
        assertNull(service.connection(sessionId))
        job.cancelAndJoin()
    }

    @Test
    fun `the connection of another user is not listed`() = runTest {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = connect(otherUserId, sessionId, description("Other"))

        assertTrue(service.onlineDevices(userId, null).isEmpty())
        assertEquals(1, service.onlineDevices(otherUserId, null).size)
        job.cancelAndJoin()
    }

    @Test
    fun `overlapping connect collectors keep the entry until both end and the later description wins`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val first = connect(userId, sessionId, description("Old", ClientCapability.QUEUE_SYNC))
        val second = connect(userId, sessionId, description("New", ClientCapability.REMOTE_CONTROL))

        assertEquals(1, service.onlineDevices(userId, null).size)
        assertEquals("New", service.deviceName(sessionId))

        first.cancelAndJoin()

        val device = service.onlineDevices(userId, null).single()
        assertEquals("New", device.deviceName)
        assertEquals(setOf(ClientCapability.REMOTE_CONTROL), device.capabilities)

        second.cancelAndJoin()

        assertTrue(service.onlineDevices(userId, null).isEmpty())
    }

    @Test
    fun `a request reaches a connected subscriber`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val job = connect(userId, sessionId, description("Desk"), received)

        val statusDeferred = async { service.request(sessionId, 5.seconds, ::buildUploadQueue) }
        val request = received.receive()
        service.complete(sessionId, request.id, ClientRequestStatus.COMPLETED)

        assertEquals(ClientRequestStatus.COMPLETED, statusDeferred.await())
        job.cancel()
    }

    @Test
    fun `the disconnect listener fires once when the last collector ends`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val disconnected = mutableListOf<UUID>()
        service.addDisconnectListener { disconnected += it }
        val first = connect(userId, sessionId, description("Old"))
        val second = connect(userId, sessionId, description("New"))

        first.cancelAndJoin()
        assertTrue(disconnected.isEmpty())

        second.cancelAndJoin()
        assertEquals(listOf(sessionId), disconnected)
    }

    @Test
    fun `online devices change when a session connects and when it disconnects`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        val job = connect(userId, sessionId, description("Desk"))
        assertOnlineDeviceChanges(userId, 1)

        job.cancelAndJoin()
        assertOnlineDeviceChanges(userId, 2)
    }

    @Test
    fun `an overlapping connect announces a new description and only the last disconnect is announced`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val first = connect(userId, sessionId, description("Old"))
        val second = connect(userId, sessionId, description("New"))
        assertOnlineDeviceChanges(userId, 2)

        first.cancelAndJoin()
        assertOnlineDeviceChanges(userId, 2)

        second.cancelAndJoin()
        assertOnlineDeviceChanges(userId, 3)
    }

    @Test
    fun `an overlapping connect with the same description is not announced`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val first = connect(userId, sessionId, description("Desk"))
        val second = connect(userId, sessionId, description("Desk"))
        assertOnlineDeviceChanges(userId, 1)

        first.cancelAndJoin()
        second.cancelAndJoin()
        assertOnlineDeviceChanges(userId, 2)
    }

    @Test
    fun `observing requests without a description does not change the online devices`() = runTest {
        val sessionId = UUID.randomUUID()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeRequests(sessionId).collect { }
        }
        job.cancelAndJoin()

        verify(exactly = 0) { changeNotifier.notify(any(), any()) }
    }
}
