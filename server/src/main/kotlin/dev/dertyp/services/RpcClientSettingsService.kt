package dev.dertyp.services

import dev.dertyp.data.ClientDevice
import dev.dertyp.data.ClientSetting
import dev.dertyp.data.ClientSettingScope
import dev.dertyp.data.ClientSettingWrite
import dev.dertyp.data.ClientSettingsChange
import dev.dertyp.data.ClientSettingsChanges
import dev.dertyp.data.ClientSettingsSnapshot
import dev.dertyp.data.ClientSettingsWriteResult
import dev.dertyp.data.User
import dev.dertyp.utils.LogParam
import kotlinx.coroutines.flow.Flow

class RpcClientSettingsService(
    private val user: User,
    private val clientSettingsService: ClientSettingsService
) : IClientSettingsService {
    override suspend fun getSettings(scope: ClientSettingScope, device: String?, includeDeleted: Boolean): List<ClientSetting> =
        clientSettingsService.getSettings(user.id, scope, device, includeDeleted)

    override suspend fun getSnapshot(deviceId: String): ClientSettingsSnapshot =
        clientSettingsService.getSnapshot(user.id, deviceId)

    override suspend fun getChanges(scope: ClientSettingScope, sinceVersion: Long, device: String?, limit: Int): ClientSettingsChanges =
        clientSettingsService.getChanges(user.id, scope, device, sinceVersion, limit)

    override suspend fun setSettings(
        @LogParam("size") entries: List<ClientSettingWrite>,
        scope: ClientSettingScope,
        device: String?,
        force: Boolean
    ): ClientSettingsWriteResult = clientSettingsService.setSettings(user.id, scope, device, entries, force)

    override suspend fun getHistory(scope: ClientSettingScope, key: String, device: String?, limit: Int): List<ClientSetting> =
        clientSettingsService.getHistory(user.id, scope, device, key, limit)

    override suspend fun restore(scope: ClientSettingScope, key: String, version: Long, device: String?, force: Boolean): ClientSettingsWriteResult =
        clientSettingsService.restore(user.id, scope, device, key, version, force)

    override fun observeSettings(): Flow<ClientSettingsChange> = clientSettingsService.observe(user.id)

    override suspend fun getDevices(): List<ClientDevice> = clientSettingsService.getDevices(user.id)

    override suspend fun registerDevice(deviceId: String, name: String, platform: String): ClientDevice =
        clientSettingsService.registerDevice(user.id, deviceId, name, platform)

    override suspend fun deleteDevice(deviceId: String) {
        clientSettingsService.deleteDevice(user.id, deviceId)
    }
}
