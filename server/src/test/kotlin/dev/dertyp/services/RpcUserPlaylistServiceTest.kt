package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.PlaylistAccess
import dev.dertyp.data.PlaylistShare
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
    private val writer = User(id = UUID.randomUUID(), username = "writer")
    private val reader = User(id = UUID.randomUUID(), username = "reader")
    private val admin = User(id = UUID.randomUUID(), username = "admin", isAdmin = true)
    private val playlistId = UUID.randomUUID()
    private val publicId = UUID.randomUUID()
    private val userPlaylistService = mockk<UserPlaylistService>()

    private val privatePlaylist = UserPlaylist(
        id = playlistId,
        name = "Mine",
        songs = emptyList(),
        creator = owner.id,
        description = "",
        shares = listOf(PlaylistShare(writer.id, PlaylistAccess.WRITE), PlaylistShare(reader.id, PlaylistAccess.READ)),
    )
    private val publicPlaylist = privatePlaylist.copy(id = publicId, isPublic = true)

    private fun page(vararg playlists: UserPlaylist) =
        PaginatedResponse(data = playlists.toList(), page = 0, total = playlists.size, pageSize = 10)

    private fun denied(block: suspend () -> Unit) {
        assertThrows(UnauthorizedException::class.java) { runBlocking { block() } }
    }

    init {
        coEvery { userPlaylistService.byId(playlistId) } returns privatePlaylist
        coEvery { userPlaylistService.byId(publicId) } returns publicPlaylist
        coEvery { userPlaylistService.accessOf(playlistId, owner.id) } returns UserPlaylistPermission.OWNER
        coEvery { userPlaylistService.accessOf(playlistId, writer.id) } returns UserPlaylistPermission.WRITE
        coEvery { userPlaylistService.accessOf(playlistId, reader.id) } returns UserPlaylistPermission.READ
        coEvery { userPlaylistService.accessOf(playlistId, stranger.id) } returns UserPlaylistPermission.NONE
        coEvery { userPlaylistService.accessOf(publicId, owner.id) } returns UserPlaylistPermission.OWNER
        coEvery { userPlaylistService.accessOf(publicId, writer.id) } returns UserPlaylistPermission.READ
        coEvery { userPlaylistService.accessOf(publicId, stranger.id) } returns UserPlaylistPermission.READ
        coEvery { userPlaylistService.addUserPlaylistToPlaylist(any(), any()) } returns Unit
        coEvery { userPlaylistService.addToPlaylist(any(), any()) } returns Unit
        coEvery { userPlaylistService.addAlbumToPlaylist(any(), any()) } returns Unit
        coEvery { userPlaylistService.addPlaylistToPlaylist(any(), any()) } returns Unit
        coEvery { userPlaylistService.removeFromPlaylist(any(), any()) } returns 1
        coEvery { userPlaylistService.setPublic(any(), any()) } returns true
        coEvery { userPlaylistService.setShare(any(), any(), any()) } returns Unit
        coEvery { userPlaylistService.removeShare(any(), any()) } returns true
        coEvery { userPlaylistService.transferOwnership(any(), any()) } returns true
        coEvery { userPlaylistService.byId(playlistId, any()) } returns privatePlaylist
        coEvery { userPlaylistService.byId(publicId, any()) } returns publicPlaylist
        coEvery { userPlaylistService.byIds(any(), any()) } returns listOf(privatePlaylist, publicPlaylist)
        coEvery { userPlaylistService.allPlaylists(any(), any(), any(), any()) } returns page(publicPlaylist)
        coEvery { userPlaylistService.rankedSearch(any(), any(), any(), any(), any()) } returns page(publicPlaylist)
        coEvery { userPlaylistService.byColor(any(), any(), any(), any(), any(), any()) } returns page(publicPlaylist)
        coEvery { userPlaylistService.sharedPlaylists(any<UUID>(), any(), any()) } returns page(privatePlaylist)
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

    @Test
    fun `the owner can use every action`() = runBlocking {
        val service = RpcUserPlaylistService(owner, userPlaylistService)
        val songs = listOf(UUID.randomUUID())

        service.addSongsToPlaylist(playlistId, songs)
        assertEquals(1, service.removeFromPlaylist(playlistId, songs))
        assertTrue(service.setPlaylistImage(playlistId, null))
        assertTrue(service.setPublic(playlistId, true))
        service.setShare(playlistId, stranger.id, PlaylistAccess.READ)
        assertTrue(service.removeShare(playlistId, reader.id))
        assertTrue(service.transferOwnership(playlistId, writer.id))
        assertTrue(service.delete(playlistId))

        coVerify(exactly = 1) { userPlaylistService.setPublic(playlistId, true) }
        coVerify(exactly = 1) { userPlaylistService.setShare(playlistId, stranger.id, PlaylistAccess.READ) }
        coVerify(exactly = 1) { userPlaylistService.removeShare(playlistId, reader.id) }
        coVerify(exactly = 1) { userPlaylistService.transferOwnership(playlistId, writer.id) }
    }

    @Test
    fun `a write share can add and remove songs only`() = runBlocking {
        val service = RpcUserPlaylistService(writer, userPlaylistService)
        val songs = listOf(UUID.randomUUID())

        service.addSongsToPlaylist(playlistId, songs)
        service.addToPlaylist(playlistId, listOf(0L to songs.single()))
        service.addAlbumToPlaylist(playlistId, UUID.randomUUID())
        service.addPlaylistToPlaylist(playlistId, UUID.randomUUID())
        assertEquals(1, service.removeFromPlaylist(playlistId, songs))

        denied { service.setPlaylistImage(playlistId, null) }
        denied { service.delete(playlistId) }
        denied { service.setPublic(playlistId, true) }
        denied { service.setShare(playlistId, stranger.id, PlaylistAccess.READ) }
        denied { service.removeShare(playlistId, reader.id) }
        denied { service.transferOwnership(playlistId, writer.id) }
        coVerify(exactly = 0) { userPlaylistService.setPlaylistImage(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.delete(any()) }
        coVerify(exactly = 0) { userPlaylistService.setPublic(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.setShare(any(), any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.transferOwnership(any(), any()) }
    }

    @Test
    fun `a read share cannot change the playlist`() {
        val service = RpcUserPlaylistService(reader, userPlaylistService)
        val songs = listOf(UUID.randomUUID())

        denied { service.addSongsToPlaylist(playlistId, songs) }
        denied { service.addToPlaylist(playlistId, listOf(0L to songs.single())) }
        denied { service.addAlbumToPlaylist(playlistId, UUID.randomUUID()) }
        denied { service.addPlaylistToPlaylist(playlistId, UUID.randomUUID()) }
        denied { service.removeFromPlaylist(playlistId, songs) }
        denied { service.setPlaylistImage(playlistId, null) }
        denied { service.delete(playlistId) }
        denied { service.setPublic(playlistId, true) }
        denied { service.setShare(playlistId, stranger.id, PlaylistAccess.READ) }
        denied { service.transferOwnership(playlistId, reader.id) }
        coVerify(exactly = 0) { userPlaylistService.addSongsToPlaylist(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.removeFromPlaylist(any(), any()) }
    }

    @Test
    fun `a stranger cannot change a public playlist`() {
        val service = RpcUserPlaylistService(stranger, userPlaylistService)
        val songs = listOf(UUID.randomUUID())

        denied { service.addSongsToPlaylist(publicId, songs) }
        denied { service.removeFromPlaylist(publicId, songs) }
        denied { service.setPlaylistImage(publicId, null) }
        denied { service.delete(publicId) }
        denied { service.setPublic(publicId, false) }
        denied { service.setShare(publicId, stranger.id, PlaylistAccess.WRITE) }
        denied { service.transferOwnership(publicId, stranger.id) }
        coVerify(exactly = 0) { userPlaylistService.addSongsToPlaylist(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.removeFromPlaylist(any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.setPublic(any(), any()) }
    }

    @Test
    fun `a stranger cannot change a private playlist`() {
        val service = RpcUserPlaylistService(stranger, userPlaylistService)
        val songs = listOf(UUID.randomUUID())

        denied { service.addSongsToPlaylist(playlistId, songs) }
        denied { service.removeFromPlaylist(playlistId, songs) }
        denied { service.setPlaylistImage(playlistId, null) }
        denied { service.delete(playlistId) }
        denied { service.setPublic(playlistId, true) }
        denied { service.setShare(playlistId, stranger.id, PlaylistAccess.READ) }
        denied { service.transferOwnership(playlistId, stranger.id) }
        coVerify(exactly = 0) { userPlaylistService.setShare(any(), any(), any()) }
        coVerify(exactly = 0) { userPlaylistService.transferOwnership(any(), any()) }
    }

    @Test
    fun `an admin without a share can use every action`() = runBlocking {
        val service = RpcUserPlaylistService(admin, userPlaylistService)
        val songs = listOf(UUID.randomUUID())

        service.addSongsToPlaylist(playlistId, songs)
        assertEquals(1, service.removeFromPlaylist(playlistId, songs))
        assertTrue(service.setPlaylistImage(playlistId, null))
        assertTrue(service.setPublic(playlistId, true))
        service.setShare(playlistId, stranger.id, PlaylistAccess.WRITE)
        assertTrue(service.removeShare(playlistId, reader.id))
        assertTrue(service.transferOwnership(playlistId, writer.id))
        assertTrue(service.delete(playlistId))
    }

    @Test
    fun `a user can remove their own share`() = runBlocking {
        assertTrue(RpcUserPlaylistService(reader, userPlaylistService).removeShare(playlistId, reader.id))
        assertTrue(RpcUserPlaylistService(writer, userPlaylistService).removeShare(playlistId, writer.id))
        coVerify(exactly = 1) { userPlaylistService.removeShare(playlistId, reader.id) }
        coVerify(exactly = 1) { userPlaylistService.removeShare(playlistId, writer.id) }
    }

    @Test
    fun `a stranger cannot remove the share of someone else`() {
        val service = RpcUserPlaylistService(stranger, userPlaylistService)

        denied { service.removeShare(playlistId, reader.id) }
        denied { service.removeShare(publicId, writer.id) }
        coVerify(exactly = 0) { userPlaylistService.removeShare(any(), any()) }
    }

    @Test
    fun `a playlist can only be added to another one when the source is readable`() = runBlocking {
        val unreadableId = UUID.randomUUID()
        coEvery { userPlaylistService.accessOf(unreadableId, writer.id) } returns UserPlaylistPermission.NONE
        coEvery { userPlaylistService.byId(unreadableId) } returns privatePlaylist.copy(id = unreadableId)
        val service = RpcUserPlaylistService(writer, userPlaylistService)

        denied { service.addUserPlaylistToPlaylist(playlistId, unreadableId) }
        coVerify(exactly = 0) { userPlaylistService.addUserPlaylistToPlaylist(any(), any()) }

        service.addUserPlaylistToPlaylist(playlistId, publicId)
        coVerify(exactly = 1) { userPlaylistService.addUserPlaylistToPlaylist(playlistId, publicId) }

        denied { RpcUserPlaylistService(reader, userPlaylistService).addUserPlaylistToPlaylist(playlistId, publicId) }

        RpcUserPlaylistService(admin, userPlaylistService).addUserPlaylistToPlaylist(playlistId, unreadableId)
        coVerify(exactly = 1) { userPlaylistService.addUserPlaylistToPlaylist(playlistId, unreadableId) }
    }

    @Test
    fun `lists and searches use the calling user as viewer, admins included`() = runBlocking {
        for (caller in listOf(owner, stranger, admin)) {
            val service = RpcUserPlaylistService(caller, userPlaylistService)
            service.allPlaylists(null, 0, 10)
            service.rankedSearch(null, 0, 10, "mix")
            service.byColor(null, 0, 10, 0x123456, 5)
            service.sharedPlaylists(0, 10)

            coVerify(exactly = 1) { userPlaylistService.allPlaylists(null, 0, 10, caller.id) }
            coVerify(exactly = 1) { userPlaylistService.rankedSearch(null, 0, 10, "mix", caller.id) }
            coVerify(exactly = 1) { userPlaylistService.byColor(null, 0, 10, 0x123456, 5, caller.id) }
            coVerify(exactly = 1) { userPlaylistService.sharedPlaylists(caller.id, 0, 10) }
        }
    }

    @Test
    fun `byId and byIds are scoped for users and unscoped for admins`() = runBlocking {
        RpcUserPlaylistService(stranger, userPlaylistService).apply {
            byId(publicId)
            byIds(listOf(playlistId, publicId))
        }
        coVerify(exactly = 1) { userPlaylistService.byId(publicId, stranger.id) }
        coVerify(exactly = 1) { userPlaylistService.byIds(listOf(playlistId, publicId), stranger.id) }

        RpcUserPlaylistService(admin, userPlaylistService).apply {
            byId(playlistId)
            byIds(listOf(playlistId, publicId))
        }
        coVerify(exactly = 1) { userPlaylistService.byId(playlistId, null) }
        coVerify(exactly = 1) { userPlaylistService.byIds(listOf(playlistId, publicId), null) }
    }

    @Test
    fun `shares are emptied for a caller who is neither owner, admin nor on the list`() = runBlocking {
        val seenByStranger = RpcUserPlaylistService(stranger, userPlaylistService)
        assertTrue(seenByStranger.byId(publicId)!!.shares.isEmpty())
        assertTrue(seenByStranger.byIds(listOf(playlistId, publicId)).all { it.shares.isEmpty() })
        assertTrue(seenByStranger.allPlaylists(null, 0, 10).data.all { it.shares.isEmpty() })
        assertTrue(seenByStranger.rankedSearch(null, 0, 10, "mix").data.all { it.shares.isEmpty() })
        assertTrue(seenByStranger.byColor(null, 0, 10, 0x123456, 5).data.all { it.shares.isEmpty() })
    }

    @Test
    fun `shares are kept for the owner, an admin and a user on the list`() = runBlocking {
        for (caller in listOf(owner, writer, reader, admin)) {
            val service = RpcUserPlaylistService(caller, userPlaylistService)
            assertEquals(2, service.byId(publicId)!!.shares.size)
            assertTrue(service.byIds(listOf(playlistId, publicId)).all { it.shares.size == 2 })
            assertTrue(service.allPlaylists(null, 0, 10).data.all { it.shares.size == 2 })
            assertTrue(service.sharedPlaylists(0, 10).data.all { it.shares.size == 2 })
        }
    }
}
