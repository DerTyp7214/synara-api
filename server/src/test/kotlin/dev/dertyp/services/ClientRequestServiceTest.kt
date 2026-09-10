package dev.dertyp.services

import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClientRequestServiceTest {
    private val service = ClientRequestService()

    private fun buildUploadQueue(id: UUID, requestedAt: Long): ClientRequest =
        ClientRequest.UploadQueue(id, requestedAt, UUID.randomUUID())

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
        assertThrows<IllegalArgumentException> { service.complete(sessionId, requestId, ClientRequestStatus.UNREACHABLE) }
    }
}
