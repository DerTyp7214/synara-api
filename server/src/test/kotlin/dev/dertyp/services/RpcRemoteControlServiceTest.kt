package dev.dertyp.services

import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class RpcRemoteControlServiceTest {
    private val remoteControlService = mockk<RemoteControlService>(relaxed = true)
    private val user = User(UUID.randomUUID(), "user", passwordHash = "hash")
    private val sessionId = UUID.randomUUID()
    private val service = RpcRemoteControlService(user, sessionId, remoteControlService)

    private fun status(positionMs: Long) = RemotePlaybackStatus(
        songId = UUID.randomUUID(),
        isPlaying = true,
        positionMs = positionMs,
        durationMs = 1000,
        shuffleMode = false,
        repeatMode = RepeatMode.ALL,
        volume = 0.4f,
    )

    @Test
    fun `reportStatus forwards the caller session and the status`() = runBlocking {
        val reported = status(10)

        service.reportStatus(reported)

        coVerify(exactly = 1) { remoteControlService.reportStatus(sessionId, reported) }
    }

    @Test
    fun `getStatus forwards the user id and the target session`() = runBlocking {
        val targetSessionId = UUID.randomUUID()
        val stored = status(20)
        every { remoteControlService.getStatus(user.id, targetSessionId) } returns stored

        assertEquals(stored, service.getStatus(targetSessionId))

        verify(exactly = 1) { remoteControlService.getStatus(user.id, targetSessionId) }
    }

    @Test
    fun `observeStatus forwards the user id and the target session`() {
        val targetSessionId = UUID.randomUUID()
        val stored = status(30)
        every { remoteControlService.observeStatus(user.id, targetSessionId) } returns flowOf(stored)

        service.observeStatus(targetSessionId)

        verify(exactly = 1) { remoteControlService.observeStatus(user.id, targetSessionId) }
    }

    @Test
    fun `sendCommand forwards the user id the caller session the target session and the command`() = runBlocking {
        val targetSessionId = UUID.randomUUID()
        val command = PlaybackCommand.SetRepeat(RepeatMode.ONE)
        coEvery {
            remoteControlService.sendCommand(user.id, sessionId, targetSessionId, command, any())
        } returns ClientRequestStatus.COMPLETED

        assertEquals(ClientRequestStatus.COMPLETED, service.sendCommand(targetSessionId, command))

        coVerify(exactly = 1) {
            remoteControlService.sendCommand(user.id, sessionId, targetSessionId, command, any())
        }
    }

    @Test
    fun `reportStatus without a session throws IllegalStateException`() {
        val withoutSession = RpcRemoteControlService(user, null, remoteControlService)

        assertThrows<IllegalStateException> {
            runBlocking { withoutSession.reportStatus(status(40)) }
        }
    }

    @Test
    fun `sendCommand without a session throws IllegalStateException`() {
        val withoutSession = RpcRemoteControlService(user, null, remoteControlService)

        assertThrows<IllegalStateException> {
            runBlocking { withoutSession.sendCommand(UUID.randomUUID(), PlaybackCommand.TogglePlayPause) }
        }
    }
}
