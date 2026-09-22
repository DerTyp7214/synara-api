package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.User
import dev.dertyp.db.SessionTable
import dev.dertyp.db.UserTable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class RpcPlaybackServiceTest {
    private lateinit var database: Database
    private lateinit var sessionService: SessionService
    private val playbackService = mockk<PlaybackService>(relaxed = true)

    @BeforeEach
    fun setUp() {
        database = TestDatabase.connect(DbDialect.SQLITE, "rpc_playback_test")
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

    private fun playbackState(positionMs: Long) = PlaybackState(
        queue = emptyList(),
        currentIndex = 0,
        isPlaying = true,
        positionMs = positionMs,
        shuffleMode = false,
        repeatMode = RepeatMode.OFF,
    )

    private fun serviceFor(userId: UUID) =
        RpcPlaybackService(User(userId, "user_$userId", passwordHash = "hash"), sessionService, playbackService)

    @Test
    fun `getPlaybackState of an own session is forwarded`() = runBlocking {
        val userId = UUID.randomUUID()
        insertUser(userId)
        val sessionId = sessionService.createSession(userId, "agent", "127.0.0.1")
        val state = playbackState(100)
        every { playbackService.getPlaybackState(sessionId) } returns state

        assertEquals(state, serviceFor(userId).getPlaybackState(sessionId))

        verify(exactly = 1) { playbackService.getPlaybackState(sessionId) }
    }

    @Test
    fun `setPlaybackState of an own session is forwarded`() = runBlocking {
        val userId = UUID.randomUUID()
        insertUser(userId)
        val sessionId = sessionService.createSession(userId, "agent", "127.0.0.1")
        val state = playbackState(200)
        coEvery { playbackService.setPlaybackState(sessionId, state) } returns true

        assertTrue(serviceFor(userId).setPlaybackState(sessionId, state))

        coVerify(exactly = 1) { playbackService.setPlaybackState(sessionId, state) }
    }

    @Test
    fun `observePlaybackState of an own session is forwarded`() = runBlocking {
        val userId = UUID.randomUUID()
        insertUser(userId)
        val sessionId = sessionService.createSession(userId, "agent", "127.0.0.1")
        val state = playbackState(300)
        every { playbackService.observePlaybackState(sessionId) } returns flowOf(state)

        assertEquals(state, serviceFor(userId).observePlaybackState(sessionId).first())

        verify(exactly = 1) { playbackService.observePlaybackState(sessionId) }
    }

    @Test
    fun `getPlaybackState of another user's session throws UnauthorizedException`() = runBlocking<Unit> {
        val callerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        insertUser(callerId)
        insertUser(otherUserId)
        val sessionId = sessionService.createSession(otherUserId, "agent", "127.0.0.1")

        assertThrows<UnauthorizedException> {
            runBlocking { serviceFor(callerId).getPlaybackState(sessionId) }
        }
    }

    @Test
    fun `setPlaybackState of another user's session throws UnauthorizedException`() = runBlocking<Unit> {
        val callerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        insertUser(callerId)
        insertUser(otherUserId)
        val sessionId = sessionService.createSession(otherUserId, "agent", "127.0.0.1")

        assertThrows<UnauthorizedException> {
            runBlocking { serviceFor(callerId).setPlaybackState(sessionId, playbackState(400)) }
        }
        coVerify(exactly = 0) { playbackService.setPlaybackState(sessionId, any()) }
    }

    @Test
    fun `observePlaybackState of another user's session throws UnauthorizedException`() = runBlocking<Unit> {
        val callerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        insertUser(callerId)
        insertUser(otherUserId)
        val sessionId = sessionService.createSession(otherUserId, "agent", "127.0.0.1")

        assertThrows<UnauthorizedException> {
            runBlocking { serviceFor(callerId).observePlaybackState(sessionId).first() }
        }
    }
}
