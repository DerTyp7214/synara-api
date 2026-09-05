package dev.dertyp.services.hue

import dev.dertyp.data.HueBridgeCandidate
import dev.dertyp.data.HueBridgeInfo
import dev.dertyp.data.HuePairingStatus
import dev.dertyp.data.HueStatus
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueUserLink
import dev.dertyp.data.User
import dev.dertyp.services.IHueService
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcHueService(
    private val user: User,
    private val service: HueService,
) : IHueService {
    override suspend fun discoverBridges(): List<HueBridgeCandidate> = service.discover(force = true)

    override suspend fun listBridges(): List<HueBridgeInfo> = service.listBridges()

    override fun startPairing(ip: String): Flow<HuePairingStatus> = service.startPairing(user.id, ip)

    override suspend fun removeBridge(bridgeId: UUID): Boolean = service.removeBridge(bridgeId)

    override suspend fun listTargets(bridgeId: UUID): List<HueTarget> = service.listTargets(bridgeId)

    override suspend fun getLinks(): List<HueUserLink> = service.getLinks(user.id)

    override suspend fun setLink(link: HueUserLink): HueUserLink = service.setLink(user.id, link)

    override suspend fun removeLink(bridgeId: UUID): Boolean = service.removeLink(user.id, bridgeId)

    override suspend fun test(bridgeId: UUID, targets: List<HueTarget>): Boolean = service.test(user.id, bridgeId, targets)

    override suspend fun status(): HueStatus = service.status(user.id)
}
