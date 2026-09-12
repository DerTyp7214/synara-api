package dev.dertyp.services

import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.data.User
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class RpcClientSettingsServiceTest {
    private val clientSettingsService = mockk<ClientSettingsService>(relaxed = true)
    private val user = User(UUID.randomUUID(), "user", passwordHash = "hash")
    private val service = RpcClientSettingsService(user, clientSettingsService)

    @Test
    fun `getSettings forwards the user id scope device and includeDeleted`() = runBlocking {
        service.getSettings(ClientSettingScope.DEVICE, "device-1", true)

        coVerify(exactly = 1) { clientSettingsService.getSettings(user.id, ClientSettingScope.DEVICE, "device-1", true) }
    }

    @Test
    fun `getSnapshot forwards the user id and device id`() = runBlocking {
        service.getSnapshot("device-1")

        coVerify(exactly = 1) { clientSettingsService.getSnapshot(user.id, "device-1") }
    }

    @Test
    fun `getChanges forwards the user id scope sinceVersion device and limit`() = runBlocking {
        service.getChanges(ClientSettingScope.SYNCED, 5, "device-1", 100)

        coVerify(exactly = 1) { clientSettingsService.getChanges(user.id, ClientSettingScope.SYNCED, "device-1", 5, 100) }
    }

    @Test
    fun `setSettings forwards entries scope device and force in the domain order`() = runBlocking {
        val entries = listOf(ClientSettingWrite("a", "1", 0))

        service.setSettings(entries, ClientSettingScope.DEVICE, "device-1", true)

        coVerify(exactly = 1) { clientSettingsService.setSettings(user.id, ClientSettingScope.DEVICE, "device-1", entries, true) }
    }

    @Test
    fun `getHistory forwards the user id scope key device and limit`() = runBlocking {
        service.getHistory(ClientSettingScope.SYNCED, "a", "device-1", 10)

        coVerify(exactly = 1) { clientSettingsService.getHistory(user.id, ClientSettingScope.SYNCED, "device-1", "a", 10) }
    }

    @Test
    fun `restore forwards the user id scope key version device and force`() = runBlocking {
        service.restore(ClientSettingScope.SYNCED, "a", 3, "device-1", true)

        coVerify(exactly = 1) { clientSettingsService.restore(user.id, ClientSettingScope.SYNCED, "device-1", "a", 3, true) }
    }

    @Test
    fun `observeSettings returns the flow of the calling user`() = runBlocking {
        val change = ClientSettingsChange(ClientSettingScope.SYNCED, null, 1, listOf("a"))
        every { clientSettingsService.observe(user.id) } returns flowOf(change)

        val result = service.observeSettings().toList()

        assertEquals(listOf(change), result)
    }

    @Test
    fun `getDevices forwards the user id`() = runBlocking {
        service.getDevices()

        coVerify(exactly = 1) { clientSettingsService.getDevices(user.id) }
    }

    @Test
    fun `registerDevice forwards the user id device id name and platform`() = runBlocking {
        service.registerDevice("device-1", "Phone", "android")

        coVerify(exactly = 1) { clientSettingsService.registerDevice(user.id, "device-1", "Phone", "android") }
    }

    @Test
    fun `deleteDevice forwards the user id and device id`() = runBlocking {
        service.deleteDevice("device-1")

        coVerify(exactly = 1) { clientSettingsService.deleteDevice(user.id, "device-1") }
    }
}
