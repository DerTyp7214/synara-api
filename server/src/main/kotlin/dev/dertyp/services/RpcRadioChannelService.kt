package dev.dertyp.services

import dev.dertyp.data.InsertableRadioChannel
import dev.dertyp.data.RadioChannel
import dev.dertyp.data.RadioChannelItemType
import dev.dertyp.data.User
import java.util.UUID

class RpcRadioChannelService(
    private val user: User,
    private val radioChannelService: RadioChannelService,
    private val radioService: RadioService,
) : IRadioChannelService {
    override suspend fun listChannels(): List<RadioChannel> =
        radioChannelService.list(includeDisabled = user.isAdmin)

    override suspend fun getChannel(id: UUID): RadioChannel? =
        radioChannelService.byId(id)?.takeIf { it.enabled || user.isAdmin }

    override suspend fun startChannel(id: UUID): UUID {
        val channel = radioChannelService.byId(id)?.takeIf { it.enabled || user.isAdmin }
            ?: throw IllegalArgumentException("Unknown radio channel")
        return radioService.createChannelSession(user.id, channel.discovery) { exclude, limit ->
            radioChannelService.randomSongs(id, exclude, limit)
        }
    }

    override suspend fun createChannel(channel: InsertableRadioChannel): UUID =
        radioChannelService.create(channel, user.id)

    override suspend fun updateChannel(id: UUID, channel: InsertableRadioChannel): Boolean =
        radioChannelService.update(id, channel)

    override suspend fun deleteChannel(id: UUID): Boolean =
        radioChannelService.delete(id)

    override suspend fun setChannelImage(id: UUID, bytes: ByteArray) =
        radioChannelService.setImage(id, bytes)

    override suspend fun addChannelItem(id: UUID, type: RadioChannelItemType, itemId: UUID): Boolean =
        radioChannelService.addItem(id, type, itemId)

    override suspend fun removeChannelItem(id: UUID, type: RadioChannelItemType, itemId: UUID): Boolean =
        radioChannelService.removeItem(id, type, itemId)
}
