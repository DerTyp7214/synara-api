package dev.dertyp.services

import dev.dertyp.data.User
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class RpcReleaseServiceTest {
    private val releaseService = mockk<ReleaseService>()
    private val user = User(id = UUID.randomUUID(), username = "test", passwordHash = "", isAdmin = false)
    private val rpcService = RpcReleaseService(user, releaseService)

    @Test
    fun `followArtist should delegate to releaseService`() = runBlocking {
        val mbId = "mb-id"
        coEvery { releaseService.followArtist(user.id, mbId) } returns true
        
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
}
