package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.RadioSeed
import dev.dertyp.data.RadioType
import dev.dertyp.data.User
import kotlinx.coroutines.flow.Flow

class RadioRpcService(
    private val user: User,
    private val radioService: RadioService,
) : IRadioService {
    override suspend fun createRadioSession(type: RadioType, seed: RadioSeed?): PlatformUUID =
        radioService.createSession(user.id, type, seed)

    override fun radioFlow(sessionId: PlatformUUID): Flow<PlatformUUID> =
        radioService.radioFlow(sessionId, user.id)
}
