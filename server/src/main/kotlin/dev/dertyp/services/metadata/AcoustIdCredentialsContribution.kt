package dev.dertyp.services.metadata

import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.CredentialOrigin
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiAlign
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiEmphasis
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiSpacing
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val ACOUSTID_UI_SOURCE = "acoustid"

class AcoustIdCredentialsEntryContribution(
    private val credentials: AcoustIdCredentialSource,
    settingsService: PluginSettingsService,
) : UiContribution(
    id = "acoustid.credentials.entry",
    kind = UiContributionKind.SLOT,
    slot = UiSlots.SETTINGS,
    titleKey = "acoustid.credentials.title",
    descriptionKey = "acoustid.credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 71,
    access = UiAccess(requiresAdmin = true),
) {
    private val settings: PluginSettings = settingsService.forPlugin(UiRegistry.SERVER_SOURCE)

    override fun changes(scope: UiRenderScope): Flow<Unit> = settings.changes().map { }

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val trailing = when (credentials.origin()) {
            CredentialOrigin.STORED -> scope.t("$PREFIX.stored")
            CredentialOrigin.ENVIRONMENT -> scope.t("$PREFIX.fromEnvironment")
            CredentialOrigin.UNREADABLE -> scope.t("$PREFIX.unreadable")
            CredentialOrigin.NONE -> scope.t("$PREFIX.none")
        }
        return UiComponent.ListItem(
            title = scope.t("acoustid.credentials.title"),
            subtitle = scope.t("acoustid.credentials.description"),
            icon = icon,
            trailing = trailing,
            action = UiAction.OpenPage(AcoustIdCredentialsContribution.ID),
        )
    }

    companion object {
        private const val PREFIX = "acoustid.credentials"
    }
}

class AcoustIdCredentialsContribution(
    private val credentials: AcoustIdCredentialSource,
    settingsService: PluginSettingsService,
) : UiContribution(
    id = ID,
    kind = UiContributionKind.PAGE,
    titleKey = "acoustid.credentials.title",
    descriptionKey = "acoustid.credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 71,
    access = UiAccess(requiresAdmin = true),
) {
    private val settings: PluginSettings = settingsService.forPlugin(UiRegistry.SERVER_SOURCE)

    override fun changes(scope: UiRenderScope): Flow<Unit> = settings.changes().map { }

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val source = credentials.origin()
        val fromEnvironment = credentials.fromEnvironment()
        val hint = when (source) {
            CredentialOrigin.STORED -> scope.t("$PREFIX.storedHint")
            CredentialOrigin.ENVIRONMENT -> scope.t("$PREFIX.envHint")
            CredentialOrigin.UNREADABLE ->
                if (fromEnvironment != null) scope.t("$PREFIX.unreadableHintEnv") else scope.t("$PREFIX.unreadableHint")
            CredentialOrigin.NONE -> scope.t("$PREFIX.noneHint")
        }
        val badgeText = when (source) {
            CredentialOrigin.STORED -> scope.t("$PREFIX.stored")
            CredentialOrigin.ENVIRONMENT -> scope.t("$PREFIX.fromEnvironment")
            CredentialOrigin.UNREADABLE -> scope.t("$PREFIX.unreadable")
            CredentialOrigin.NONE -> scope.t("$PREFIX.none")
        }
        val badgeTone = when (source) {
            CredentialOrigin.STORED, CredentialOrigin.ENVIRONMENT -> UiTone.SUCCESS
            CredentialOrigin.UNREADABLE -> UiTone.WARNING
            CredentialOrigin.NONE -> UiTone.MUTED
        }
        val stored = source == CredentialOrigin.STORED
        val hasStoredRows = stored || source == CredentialOrigin.UNREADABLE
        val children = mutableListOf<UiComponent>(
            UiComponent.Badge(badgeText, badgeTone),
            UiComponent.Text(hint, style = UiTextStyle.CAPTION, emphasis = UiEmphasis.LOW),
            UiComponent.Form(
                id = FORM_ID,
                submit = UiAction.Invoke(id, "save", formId = FORM_ID),
                submitLabel = scope.t("$PREFIX.save"),
                children = listOf(
                    UiComponent.TextField(
                        key = KEY_API_KEY,
                        label = scope.t("$PREFIX.apiKey"),
                        helper = scope.t("$PREFIX.apiKeyHelper"),
                        secret = true,
                        required = false,
                    ),
                ),
            ),
        )
        if (hasStoredRows) {
            children += UiComponent.Divider
            children += UiComponent.Column(
                listOf(
                    UiComponent.Button(
                        label = scope.t("$PREFIX.clear"),
                        action = UiAction.Invoke(id, "clear"),
                        style = UiButtonStyle.DESTRUCTIVE,
                    ),
                ),
                spacing = UiSpacing.SMALL,
                align = UiAlign.START,
            )
        }
        return UiComponent.Column(children)
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult = when (actionId) {
        "save" -> save(scope, values)
        "clear" -> clear(scope)
        else -> super.invoke(scope, actionId, values)
    }

    private suspend fun save(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val apiKey = values[KEY_API_KEY]?.text?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            credentials.clear()
            return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.cleared"), refresh = true)
        }
        credentials.store(mapOf(KEY_API_KEY to apiKey))
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.saved"), refresh = true)
    }

    private suspend fun clear(scope: UiRenderScope): UiInvokeResult {
        credentials.clear()
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.cleared"), refresh = true)
    }

    companion object {
        const val ID = "acoustid.credentials"
        const val KEY_API_KEY = ACOUSTID_API_KEY_SETTING

        private const val PREFIX = "acoustid.credentials"
        private const val FORM_ID = "acoustid"
    }
}
