package dev.dertyp.services.ui

import dev.dertyp.core.ClientInfo
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.plugins.UiTranslator
import dev.dertyp.services.Service
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHomeLayout
import dev.dertyp.ui.UiHookEvent
import dev.dertyp.ui.UiHookHandler
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.ui.UiSlotRender
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.supervisorScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ServerUiRenderScope(
    user: UserInfo,
    context: UiContext,
    i18n: UiTranslator,
    settings: PluginSettings,
    clientSchemaVersion: Int,
    val account: User,
    val client: ClientInfo,
    val call: ApplicationCall?,
) : UiRenderScope(user, context, i18n, settings, clientSchemaVersion)

class UiService(
    val registry: UiRegistry,
    private val translations: TranslationService,
    private val pluginSettings: PluginSettingsService,
    private val homeCards: UserHomeCardService,
) : Service() {
    private val revisions = ConcurrentHashMap<String, AtomicLong>()

    private fun allowed(registered: RegisteredContribution, user: User): Boolean =
        registered.contribution.access.allows(UserInfo.fromUser(user))

    private fun info(registered: RegisteredContribution, client: ClientInfo): UiContributionInfo {
        val contribution = registered.contribution
        val t = translations.translator(registered.source, client.locale)
        return UiContributionInfo(
            id = contribution.id,
            source = registered.source,
            kind = contribution.kind,
            slot = contribution.slot,
            title = t.t(contribution.titleKey),
            description = contribution.descriptionKey?.let { t.t(it) },
            icon = contribution.icon,
            order = contribution.order,
            live = true,
            cardSize = contribution.cardSize,
            requiresAdmin = contribution.access.requiresAdmin,
            requiredCapabilities = contribution.access.capabilities.toList(),
            hooks = contribution.hooks.toList(),
        )
    }

    private fun visible(user: User): List<RegisteredContribution> = registry.all().filter { allowed(it, user) }

    fun list(user: User, client: ClientInfo, kind: UiContributionKind? = null, slot: String? = null): List<UiContributionInfo> =
        visible(user)
            .filter { kind == null || it.contribution.kind == kind }
            .filter { slot == null || it.contribution.slot == slot }
            .map { info(it, client) }

    private fun require(id: String, user: User): RegisteredContribution {
        val registered = registry.get(id) ?: throw IllegalArgumentException("Unknown UI contribution: $id")
        if (!allowed(registered, user)) throw UnauthorizedException("User may not access UI contribution $id")
        return registered
    }

    fun scope(registered: RegisteredContribution, user: User, client: ClientInfo, context: UiContext, call: ApplicationCall?): ServerUiRenderScope =
        ServerUiRenderScope(
            user = UserInfo.fromUser(user),
            context = context,
            i18n = translations.translator(registered.source, client.locale),
            settings = pluginSettings.forPlugin(registered.source),
            clientSchemaVersion = client.uiSchemaVersion,
            account = user,
            client = client,
            call = call,
        )

    private suspend fun renderWith(registered: RegisteredContribution, scope: ServerUiRenderScope): UiRender {
        val contribution = registered.contribution
        return UiRender(
            contributionId = contribution.id,
            root = contribution.render(scope),
            title = scope.t(contribution.titleKey),
            schemaVersion = UiSchemaVersion.CURRENT,
            revision = revisions.getOrPut(contribution.id) { AtomicLong() }.incrementAndGet(),
            toolbar = if (contribution.kind == UiContributionKind.PAGE) contribution.toolbar(scope) else emptyList(),
        )
    }

    suspend fun render(user: User, client: ClientInfo, id: String, context: UiContext, call: ApplicationCall? = null): UiRender {
        val registered = require(id, user)
        return renderWith(registered, scope(registered, user, client, context, call))
    }

    suspend fun renderSlot(user: User, client: ClientInfo, slot: String, context: UiContext, call: ApplicationCall? = null): UiSlotRender {
        val items = registry.bySlot(slot).filter { allowed(it, user) }.mapNotNull { registered ->
            try {
                renderWith(registered, scope(registered, user, client, context, call))
            } catch (e: Exception) {
                logger.error("Failed to render UI contribution ${registered.contribution.id} in slot $slot", e)
                null
            }
        }
        return UiSlotRender(slot, items)
    }

    fun subscribe(user: User, client: ClientInfo, id: String, entityId: UUID? = null, call: ApplicationCall? = null): Flow<UiRender> = flow {
        val registered = require(id, user)
        val context = UiContext(entityId = entityId)
        val scope = scope(registered, user, client, context, call)
        val changes = registered.contribution.changes(scope) ?: emptyFlow()
        val invalidations = registry.invalidations.filter { it == id }.map { }
        emitAll(
            merge(changes, invalidations)
                .onStart { emit(Unit) }
                .map { renderWith(registered, scope) }
                .distinctUntilChangedBy { it.root to it.toolbar }
        )
    }

    fun subscribeLive(user: User, client: ClientInfo, id: String, key: String, entityId: UUID? = null, call: ApplicationCall? = null): Flow<UiLiveUpdate> = flow {
        val registered = require(id, user)
        val scope = scope(registered, user, client, UiContext(entityId = entityId), call)
        val updates = registered.contribution.live(scope, key) ?: throw IllegalArgumentException("Unknown live key '$key' for UI contribution $id")
        emitAll(updates)
    }

    suspend fun invoke(user: User, client: ClientInfo, id: String, actionId: String, payload: UiInvokePayload, call: ApplicationCall? = null): UiInvokeResult {
        val registered = require(id, user)
        val scope = scope(registered, user, client, payload.context, call)
        val result = try {
            registered.contribution.invoke(scope, actionId, payload.values)
        } catch (e: IllegalArgumentException) {
            UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, e.message)
        } catch (e: UnauthorizedException) {
            UiInvokeResult(UiInvokeStatus.UNAUTHORIZED, e.message)
        } catch (e: Exception) {
            logger.error("UI action $actionId of $id failed", e)
            UiInvokeResult(UiInvokeStatus.ERROR, e.message ?: e::class.simpleName)
        }
        if (result.refresh) registry.invalidate(id)
        return result
    }

    suspend fun dispatchHook(user: User, client: ClientInfo, event: UiHookEvent, call: ApplicationCall? = null): List<UiHookHandler> {
        val candidates = visible(user).filter { event.kind in it.contribution.hooks }
        val offers = supervisorScope {
            candidates.map { registered ->
                async {
                    try {
                        registered.contribution.onHook(scope(registered, user, client, UiContext(), call), event)?.let { registered to it }
                    } catch (e: Exception) {
                        logger.error("UI hook ${event.kind} of ${registered.contribution.id} failed", e)
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        return offers.map { (registered, offer) ->
            val t = translations.translator(registered.source, client.locale)
            UiHookHandler(
                contributionId = registered.contribution.id,
                source = registered.source,
                title = t.t(offer.titleKey),
                description = offer.descriptionKey?.let { t.t(it) },
                icon = offer.icon,
                action = offer.action,
            )
        }
    }

    private fun homeCardInfos(user: User, client: ClientInfo) = list(user, client, kind = UiContributionKind.HOME_CARD)

    suspend fun homeLayout(user: User, client: ClientInfo): UiHomeLayout = homeCards.layoutFor(user.id, homeCardInfos(user, client))

    suspend fun setHomeCardPinned(user: User, client: ClientInfo, id: String, pinned: Boolean): UiHomeLayout {
        val registered = require(id, user)
        require(registered.contribution.kind == UiContributionKind.HOME_CARD) { "$id is not a home card" }
        homeCards.setPinned(user.id, id, pinned)
        return homeLayout(user, client)
    }

    suspend fun setHomeCardOrder(user: User, client: ClientInfo, ids: List<String>): UiHomeLayout {
        ids.forEach { require(it, user) }
        homeCards.setOrder(user.id, ids)
        return homeLayout(user, client)
    }

    fun homeLayoutFlow(user: User, client: ClientInfo): Flow<UiHomeLayout> = homeCards.layoutFlow(user.id) { homeCardInfos(user, client) }

    fun contributionsOf(registeredContribution: UiContribution): RegisteredContribution? = registry.get(registeredContribution.id)
}
