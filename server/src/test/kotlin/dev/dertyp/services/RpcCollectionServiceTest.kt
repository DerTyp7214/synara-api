package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.CollectionItemType
import dev.dertyp.data.InsertableCollection
import dev.dertyp.data.MediaCollection
import dev.dertyp.data.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class RpcCollectionServiceTest {
    private val owner = User(id = UUID.randomUUID(), username = "owner")
    private val stranger = User(id = UUID.randomUUID(), username = "stranger")
    private val admin = User(id = UUID.randomUUID(), username = "admin", isAdmin = true)
    private val collectionId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()
    private val update = InsertableCollection(name = "Renamed")
    private val collectionService = mockk<CollectionService>()

    init {
        coEvery { collectionService.byId(collectionId) } returns MediaCollection(
            id = collectionId,
            name = "Mine",
            creator = owner.id,
        )
        coEvery { collectionService.updateCollection(collectionId, any()) } returns true
        coEvery { collectionService.addItem(collectionId, any(), any()) } returns true
        coEvery { collectionService.removeItem(collectionId, any(), any()) } returns true
        coEvery { collectionService.setCollectionImage(collectionId, any()) } returns true
        coEvery { collectionService.delete(collectionId) } returns true
    }

    @Test
    fun `another user cannot change the collection`() {
        val service = RpcCollectionService(stranger, collectionService)

        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                service.updateCollection(
                    collectionId,
                    update
                )
            }
        }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking { service.addItem(collectionId, CollectionItemType.SONG, itemId) }
        }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking { service.removeItem(collectionId, CollectionItemType.SONG, itemId) }
        }
        assertThrows(UnauthorizedException::class.java) {
            runBlocking {
                service.setCollectionImage(
                    collectionId,
                    null
                )
            }
        }
        assertThrows(UnauthorizedException::class.java) { runBlocking { service.delete(collectionId) } }
        coVerify(exactly = 0) { collectionService.updateCollection(any(), any()) }
        coVerify(exactly = 0) { collectionService.addItem(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.removeItem(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setCollectionImage(any(), any()) }
        coVerify(exactly = 0) { collectionService.delete(any()) }
    }

    @Test
    fun `the owner can change the collection`() = runBlocking {
        val service = RpcCollectionService(owner, collectionService)

        assertTrue(service.updateCollection(collectionId, update))
        assertTrue(service.addItem(collectionId, CollectionItemType.SONG, itemId))
        assertTrue(service.removeItem(collectionId, CollectionItemType.SONG, itemId))
        assertTrue(service.setCollectionImage(collectionId, null))
        assertTrue(service.delete(collectionId))
    }

    @Test
    fun `an admin can change another user's collection`() = runBlocking {
        val service = RpcCollectionService(admin, collectionService)

        assertTrue(service.addItem(collectionId, CollectionItemType.ALBUM, itemId))
        assertTrue(service.delete(collectionId))
    }
}
