package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.RepeatMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlServiceTest {
    private val clientRequestService = ClientRequestService()
    private val service = RemoteControlService(clientRequestService)

    private fun TestScope.connect(
        userId: UUID,
        sessionId: UUID,
        deviceName: String,
        capabilities: Set<ClientCapability>,
        received: Channel<ClientRequest>? = null,
    ): Job = launch(UnconfinedTestDispatcher(testScheduler)) {
        val description = ClientDescription(
            deviceName = deviceName,
            platform = "Test",
            deviceId = null,
            capabilities = capabilities,
        )
        clientRequestService.connect(userId, sessionId, description).collect { received?.send(it) }
    }

    private fun status(positionMs: Long = 1000, volume: Float? = null) = RemotePlaybackStatus(
        songId = UUID.randomUUID(),
        isPlaying = true,
        positionMs = positionMs,
        durationMs = 200_000,
        shuffleMode = false,
        repeatMode = RepeatMode.OFF,
        volume = volume,
    )

    @Test
    fun `reportStatus stamps reportedAt and getStatus returns the last status`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = connect(userId, sessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL))
        val before = System.currentTimeMillis()

        service.reportStatus(sessionId, status(positionMs = 4200))

        val stored = service.getStatus(userId, sessionId)
        assertNotNull(stored)
        assertEquals(4200L, stored!!.positionMs)
        assertTrue(stored.reportedAt >= before)
        job.cancelAndJoin()
    }

    @Test
    fun `observeStatus replays the last status to a late subscriber and keeps emitting`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = connect(userId, sessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL))
        service.reportStatus(sessionId, status(positionMs = 1))

        val received = Channel<RemotePlaybackStatus>(Channel.UNLIMITED)
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeStatus(userId, sessionId).collect { received.send(it) }
        }

        assertEquals(1L, received.receive().positionMs)
        service.reportStatus(sessionId, status(positionMs = 2))
        assertEquals(2L, received.receive().positionMs)

        watcher.cancel()
        job.cancelAndJoin()
    }

    @Test
    fun `reportStatus from a session without the remote control capability is rejected`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = connect(userId, sessionId, "Desk", setOf(ClientCapability.QUEUE_SYNC))

        assertThrows<IllegalArgumentException> {
            runBlocking { service.reportStatus(sessionId, status()) }
        }
        job.cancelAndJoin()
    }

    @Test
    fun `reportStatus from a session that is not connected is rejected`() = runTest {
        assertThrows<IllegalArgumentException> {
            runBlocking { service.reportStatus(UUID.randomUUID(), status()) }
        }
    }

    @Test
    fun `sendCommand delivers a ControlPlayback with the requester session and device name`() = runTest {
        val userId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()
        val requesterSessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val target = connect(userId, targetSessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL), received)
        val requester = connect(userId, requesterSessionId, "Phone", setOf(ClientCapability.REMOTE_CONTROL))

        val statusDeferred = async {
            service.sendCommand(userId, requesterSessionId, targetSessionId, PlaybackCommand.Pause)
        }
        val request = received.receive() as ClientRequest.ControlPlayback

        assertEquals(requesterSessionId, request.requestedBySessionId)
        assertEquals("Phone", request.requestedByDeviceName)
        assertEquals(PlaybackCommand.Pause, request.command)
        assertTrue(clientRequestService.complete(targetSessionId, request.id, ClientRequestStatus.COMPLETED))
        assertEquals(ClientRequestStatus.COMPLETED, statusDeferred.await())

        target.cancel()
        requester.cancel()
    }

    @Test
    fun `sendCommand delivers a PlayQueueItem with its queue id and version`() = runTest {
        val userId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()
        val requesterSessionId = UUID.randomUUID()
        val received = Channel<ClientRequest>(Channel.UNLIMITED)
        val target = connect(userId, targetSessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL), received)
        val requester = connect(userId, requesterSessionId, "Phone", setOf(ClientCapability.REMOTE_CONTROL))

        val statusDeferred = async {
            service.sendCommand(
                userId,
                requesterSessionId,
                targetSessionId,
                PlaybackCommand.PlayQueueItem(queueId = 42, queueVersion = 7),
            )
        }
        val request = received.receive() as ClientRequest.ControlPlayback

        assertEquals(PlaybackCommand.PlayQueueItem(queueId = 42, queueVersion = 7), request.command)
        assertTrue(clientRequestService.complete(targetSessionId, request.id, ClientRequestStatus.COMPLETED))
        assertEquals(ClientRequestStatus.COMPLETED, statusDeferred.await())

        target.cancel()
        requester.cancel()
    }

    @Test
    fun `sendCommand times out when the target does not answer`() = runTest {
        val userId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()
        val requesterSessionId = UUID.randomUUID()
        val target = connect(userId, targetSessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL))
        val requester = connect(userId, requesterSessionId, "Phone", setOf(ClientCapability.REMOTE_CONTROL))

        val result = service.sendCommand(
            userId,
            requesterSessionId,
            targetSessionId,
            PlaybackCommand.SeekTo(5000),
            50.milliseconds,
        )

        assertEquals(ClientRequestStatus.TIMED_OUT, result)
        target.cancel()
        requester.cancel()
    }

    @Test
    fun `a target connected by another user is refused`() = runTest {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()
        val job = connect(otherUserId, targetSessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL))

        assertThrows<UnauthorizedException> { service.getStatus(userId, targetSessionId) }
        assertThrows<UnauthorizedException> {
            runBlocking { service.observeStatus(userId, targetSessionId).first() }
        }
        assertThrows<UnauthorizedException> {
            runBlocking { service.sendCommand(userId, UUID.randomUUID(), targetSessionId, PlaybackCommand.Play) }
        }
        job.cancelAndJoin()
    }

    @Test
    fun `a target that is not online is rejected`() = runTest {
        val userId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()

        assertThrows<IllegalArgumentException> { service.getStatus(userId, targetSessionId) }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendCommand(userId, UUID.randomUUID(), targetSessionId, PlaybackCommand.Play) }
        }
    }

    @Test
    fun `a target without the remote control capability is rejected`() = runTest {
        val userId = UUID.randomUUID()
        val targetSessionId = UUID.randomUUID()
        val job = connect(userId, targetSessionId, "Desk", setOf(ClientCapability.QUEUE_SYNC))

        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendCommand(userId, UUID.randomUUID(), targetSessionId, PlaybackCommand.Next) }
        }
        job.cancelAndJoin()
    }

    @Test
    fun `SetVolume needs the remote volume capability and a volume inside the unit range`() = runTest {
        val userId = UUID.randomUUID()
        val withoutVolume = UUID.randomUUID()
        val withVolume = UUID.randomUUID()
        val quiet = connect(userId, withoutVolume, "Phone", setOf(ClientCapability.REMOTE_CONTROL))
        val loud = connect(
            userId,
            withVolume,
            "Desk",
            setOf(ClientCapability.REMOTE_CONTROL, ClientCapability.REMOTE_VOLUME),
        )

        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendCommand(userId, UUID.randomUUID(), withoutVolume, PlaybackCommand.SetVolume(0.5f)) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.sendCommand(userId, UUID.randomUUID(), withVolume, PlaybackCommand.SetVolume(1.5f)) }
        }

        quiet.cancelAndJoin()
        loud.cancelAndJoin()
    }

    @Test
    fun `the reported status is dropped when the target disconnects`() = runTest {
        val userId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val job = connect(userId, sessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL))
        service.reportStatus(sessionId, status(positionMs = 7))
        assertNotNull(service.getStatus(userId, sessionId))

        job.cancelAndJoin()

        assertThrows<IllegalArgumentException> { service.getStatus(userId, sessionId) }

        val again = connect(userId, sessionId, "Desk", setOf(ClientCapability.REMOTE_CONTROL))
        assertNull(service.getStatus(userId, sessionId))
        again.cancelAndJoin()
    }
}
