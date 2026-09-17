package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource.Companion.KEY_API_KEY
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource.Companion.KEY_API_SECRET
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiEmphasis
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PodcastIndexCredentialsContribution(
    private val credentials: PodcastIndexCredentialSource,
    private val settings: PluginSettings,
) : UiContribution(
    id = "podcastindex.credentials",
    kind = UiContributionKind.SLOT,
    slot = UiSlots.SETTINGS,
    titleKey = "podcastindex.credentials.title",
    descriptionKey = "podcastindex.credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 70,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = settings.changes().map { }

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val stored = credentials.stored()
        val fromEnvironment = credentials.fromEnvironment()
        val configured = stored || fromEnvironment != null
        val hint = when {
            stored -> scope.t("$PREFIX.storedHint")
            fromEnvironment != null -> scope.t("$PREFIX.envHint")
            else -> scope.t("$PREFIX.noneHint")
        }
        return UiComponent.Card(
            title = scope.t("$PREFIX.title"),
            subtitle = scope.t("$PREFIX.description"),
            icon = icon,
            children = listOf(
                UiComponent.Badge(
                    if (configured) scope.t("$PREFIX.configured") else scope.t("$PREFIX.missing"),
                    if (configured) UiTone.SUCCESS else UiTone.WARNING,
                ),
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
                            required = !stored,
                        ),
                        UiComponent.TextField(
                            key = KEY_API_SECRET,
                            label = scope.t("$PREFIX.apiSecret"),
                            secret = true,
                            required = !stored,
                        ),
                    ),
                ),
            ),
            actions = if (stored) {
                listOf(
                    UiComponent.Button(
                        label = scope.t("$PREFIX.clear"),
                        action = UiAction.Invoke(id, "clear"),
                        style = UiButtonStyle.DESTRUCTIVE,
                    )
                )
            } else {
                emptyList()
            },
        )
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult = when (actionId) {
        "save" -> save(scope, values)
        "clear" -> clear(scope)
        else -> super.invoke(scope, actionId, values)
    }

    private suspend fun save(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val apiKey = values[KEY_API_KEY]?.text?.trim().orEmpty()
        val apiSecret = values[KEY_API_SECRET]?.text?.trim().orEmpty()
        if (apiKey.isEmpty() && apiSecret.isEmpty()) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(KEY_API_KEY to scope.t("$PREFIX.error.apiKey")))
        }
        if (!credentials.stored()) {
            if (apiKey.isEmpty()) {
                return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(KEY_API_KEY to scope.t("$PREFIX.error.apiKey")))
            }
            if (apiSecret.isEmpty()) {
                return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(KEY_API_SECRET to scope.t("$PREFIX.error.apiSecret")))
            }
        }
        val updates = buildMap<String, String?> {
            if (apiKey.isNotEmpty()) put(KEY_API_KEY, apiKey)
            if (apiSecret.isNotEmpty()) put(KEY_API_SECRET, apiSecret)
        }
        settings.setAll(updates)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.saved"), refresh = true)
    }

    private suspend fun clear(scope: UiRenderScope): UiInvokeResult {
        settings.setAll(mapOf(KEY_API_KEY to null, KEY_API_SECRET to null))
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.cleared"), refresh = true)
    }

    companion object {
        const val ID = "podcastindex.credentials"

        private const val PREFIX = "podcastindex.credentials"
        private const val FORM_ID = "podcastindex"
    }
}
