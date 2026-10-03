package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class RpcUserPlaylistServiceTest {
    private val owner = User(id = UUID.randomUUID(), username = "owner")
    private val stranger = User(id = UUID.randomUUID(), username = "stranger")
    private val admin = User(id = UUID.randomUUID(), username = "admin", isAdmin = true)
    private val playlistId = UUID.randomUUID()
    private val userPlaylistService = mockk<UserPlaylistService>()

    init {
        coEvery { userPlaylistService.byId(playlistId) } returns UserPlaylist(
            id = playlistId,
            name = "Mine",
            songs = emptyList(),
            creator = owner.id,
            description = "",
        )
        coEvery { userPlaylistService.delete(playlistId) } returns true
        coEvery { userPlaylistService.addSongsToPlaylist(playlistId, any()) } returns Unit
        coEvery { userPlaylistService.setPlaylistImage(playlistId, any()) } returns true
        coEvery { userPlaylistService.getOrAddPlaylist(any(), any(), any()) } returns UUID.randomUUID()
    }

    @Test
    fun `another user cannot change the playlist`() {
        val service = RpcUserPlaylistService(stranger, userPlaylistService)

        assertThrows(UnauthorizedException::class.java) { runBlocking { service.delete(playlistId) } }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking { service.addSongsToPlaylist(playlistId, listOf(UUID.randomUUID())) }
        }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking { service.setPlaylistImage(playlistId, null) }
        }
        coVerify(exactly = 0) { userPlaylistService.delete(any()) }
        coVerify(exactly = 0) { userPlaylistService.addSongsToPlaylist(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.setPlaylistImage(any(), any()) }
    }

    @Test
    fun `the owner can change the playlist`() = runBlocking {
        val service = RpcUserPlaylistService(owner, userPlaylistService)

        service.addSongsToPlaylist(playlistId, listOf(UUID.randomUUID()))
        assertTrue(service.delete(playlistId))
        coVerify(exactly = 1) { userPlaylistService.delete(playlistId) }
    }

    @Test
    fun `an admin can change another user's playlist`() = runBlocking {
        val service = RpcUserPlaylistService(admin, userPlaylistService)

        assertTrue(service.setPlaylistImage(playlistId, null))
        assertTrue(service.delete(playlistId))
    }

    @Test
    fun `playlists can only be created for the calling user unless admin`() {
        val playlist = InsertablePlaylist(name = "New", description = "")

        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                RpcUserPlaylistService(stranger, userPlaylistService).getOrAddPlaylist(
                    owner.id,
                    null,
                    playlist
                )
            }
        }
        runBlocking {
            RpcUserPlaylistService(owner, userPlaylistService).getOrAddPlaylist(owner.id, null, playlist)
            RpcUserPlaylistService(admin, userPlaylistService).getOrAddPlaylist(owner.id, null, playlist)
        }
        coVerify(exactly = 2) { userPlaylistService.getOrAddPlaylist(owner.id, null, playlist) }
    }

    @Test
    fun `an unknown playlist is passed through`() = runBlocking {
        val unknownId = UUID.randomUUID()
        coEvery { userPlaylistService.byId(unknownId) } returns null
        coEvery { userPlaylistService.delete(unknownId) } returns false

        assertEquals(false, RpcUserPlaylistService(stranger, userPlaylistService).delete(unknownId))
    }
}
