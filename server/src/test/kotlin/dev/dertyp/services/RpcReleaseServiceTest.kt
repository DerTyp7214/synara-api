package dev.dertyp.services

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.User
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class RpcReleaseServiceTest {
    private val releaseService = mockk<ReleaseService>()
    private val user = User(id = UUID.randomUUID(), username = "test", passwordHash = "", isAdmin = false)
    private val rpcService = RpcReleaseService(user, releaseService)

    @Test
    fun `followArtist should delegate to releaseService`() = runBlocking {
        val mbId = UUID.randomUUID()
        coEvery { releaseService.followArtist(user.id, mbId, HttpClientPriority.HIGH) } returns true
        
        val result = rpcService.followArtist(mbId)
        assertTrue(result)
    }

    @Test
    fun `unfollowArtist should delegate to releaseService`() = runBlocking {
        val artistId = UUID.randomUUID()
        coEvery { releaseService.unfollowArtist(user.id, artistId) } returns true

        val result = rpcService.unfollowArtist(artistId)
        assertTrue(result)
    }

    @Test
    fun `getReleaseImage should delegate to releaseService`() = runBlocking {
        val releaseId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { releaseService.getReleaseImage(releaseId, 250) } returns bytes

        assertArrayEquals(bytes, rpcService.getReleaseImage(releaseId, 250))
    }

    @Test
    fun `getReleaseImage should work without a user`() = runBlocking {
        val releaseId = UUID.randomUUID()
        val bytes = byteArrayOf(1, 2, 3)
        coEvery { releaseService.getReleaseImage(releaseId, 0) } returns bytes

        val anonymousService = RpcReleaseService(null, releaseService)
        assertArrayEquals(bytes, anonymousService.getReleaseImage(releaseId, 0))
    }

    @Test
    fun `user-scoped methods should throw without a user`() {
        val anonymousService = RpcReleaseService(null, releaseService)

        assertThrows<UnauthorizedException> { runBlocking { anonymousService.followArtist(UUID.randomUUID()) } }
        assertThrows<UnauthorizedException> { runBlocking { anonymousService.unfollowArtist(UUID.randomUUID()) } }
        assertThrows<UnauthorizedException> { runBlocking { anonymousService.getFollowedArtists() } }
        assertThrows<UnauthorizedException> { runBlocking { anonymousService.getRecentReleases(0, 10) } }
    }
}
