package dev.dertyp.services.ui

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.User
import dev.dertyp.services.IUiService
import dev.dertyp.ui.IntakeItem
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiIntakeResult
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHomeLayout
import dev.dertyp.ui.UiHookEvent
import dev.dertyp.ui.UiHookHandler
import dev.dertyp.ui.UiHookHandlerInfo
import dev.dertyp.ui.UiHookKind
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSlotRender
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcUiService(
    private val user: User,
    private val client: ClientInfo,
    private val call: ApplicationCall?,
    private val uiService: UiService,
) : IUiService {
    override suspend fun listContributions(kind: UiContributionKind?, slot: String?): List<UiContributionInfo> =
        uiService.list(user, client, kind, slot)

    override suspend fun renderSlot(slot: String, context: UiContext): UiSlotRender =
        uiService.renderSlot(user, client, slot, context, call)

    override suspend fun render(contributionId: String, context: UiContext): UiRender =
        uiService.render(user, client, contributionId, context, call)

    override fun subscribe(contributionId: String, entityId: UUID?): Flow<UiRender> =
        uiService.subscribe(user, client, contributionId, entityId, call)

    override fun subscribeLive(contributionId: String, key: String, entityId: UUID?): Flow<UiLiveUpdate> =
        uiService.subscribeLive(user, client, contributionId, key, entityId, call)

    override suspend fun invoke(contributionId: String, actionId: String, payload: UiInvokePayload): UiInvokeResult =
        uiService.invoke(user, client, contributionId, actionId, payload, call)

    override suspend fun dispatchHook(event: UiHookEvent): List<UiHookHandler> =
        uiService.dispatchHook(user, client, event, call)

    override suspend fun listHookHandlers(kind: UiHookKind?): List<UiHookHandlerInfo> =
        uiService.listHookHandlers(user, client, kind)

    override suspend fun intake(items: List<IntakeItem>, resolverId: String?): UiIntakeResult =
        uiService.intake(user, client, items, resolverId)

    override suspend fun resolveIntake(items: List<IntakeItem>): List<UiHookHandler> =
        uiService.resolveIntake(user, client, items)

    override suspend fun getHomeCards(): UiHomeLayout = uiService.homeLayout(user, client)

    override suspend fun setHomeCardPinned(contributionId: String, pinned: Boolean): UiHomeLayout =
        uiService.setHomeCardPinned(user, client, contributionId, pinned)

    override suspend fun setHomeCardOrder(contributionIds: List<String>): UiHomeLayout =
        uiService.setHomeCardOrder(user, client, contributionIds)

    override fun getHomeCardsFlow(): Flow<UiHomeLayout> = uiService.homeLayoutFlow(user, client)
}
