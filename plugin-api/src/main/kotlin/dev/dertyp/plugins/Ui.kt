package dev.dertyp.plugins

import dev.dertyp.data.UserCapability
import dev.dertyp.data.UserInfo
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiCardSize
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHookEvent
import dev.dertyp.ui.UiHookKind
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.flow.Flow

data class UiAccess(
    val requiresAdmin: Boolean = false,
    val capabilities: Set<UserCapability> = emptySet(),
) {
    fun allows(user: UserInfo): Boolean =
        (!requiresAdmin || user.isAdmin) && capabilities.all(user::hasCapability)
}

interface UiTranslator {
    val locale: String
    fun t(key: String, vararg args: Pair<String, String>): String
}

open class UiRenderScope(
    val user: UserInfo,
    val context: UiContext,
    val i18n: UiTranslator,
    val settings: PluginSettings,
    val clientSchemaVersion: Int,
) {
    fun t(key: String, vararg args: Pair<String, String>): String = i18n.t(key, *args)
}

data class UiHookOffer(
    val titleKey: String,
    val action: UiAction,
    val descriptionKey: String? = null,
    val icon: UiIcon? = null,
)

abstract class UiContribution(
    val id: String,
    val kind: UiContributionKind,
    val titleKey: String,
    val slot: String? = null,
    val descriptionKey: String? = null,
    val icon: UiIcon? = null,
    val order: Int = 0,
    val cardSize: UiCardSize = UiCardSize.MEDIUM,
    val access: UiAccess = UiAccess(),
    val hooks: Set<UiHookKind> = emptySet(),
) {
    abstract suspend fun render(scope: UiRenderScope): UiComponent

    open suspend fun toolbar(scope: UiRenderScope): List<UiComponent> = emptyList()

    open fun changes(scope: UiRenderScope): Flow<Unit>? = null

    open fun live(scope: UiRenderScope, key: String): Flow<UiLiveUpdate>? = null

    open suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult =
        UiInvokeResult(UiInvokeStatus.ERROR, "Unknown action: $actionId")

    open suspend fun onHook(scope: UiRenderScope, event: UiHookEvent): UiHookOffer? = null
}

fun interface UiRegistration {
    fun cancel()
}

interface UiRegistrar {
    fun register(contribution: UiContribution): UiRegistration
    fun invalidate(contributionId: String)
}

interface IUiPlugin {
    fun getUiContributions(): List<UiContribution>
}
