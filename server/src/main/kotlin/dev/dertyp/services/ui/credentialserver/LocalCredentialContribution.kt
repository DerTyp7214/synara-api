package dev.dertyp.services.ui.credentialserver

import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.AppleDeveloperTokenSigner
import dev.dertyp.services.credentials.CredentialOrigin
import dev.dertyp.services.credentials.LocalCredentialEntry
import dev.dertyp.services.credentials.LocalCredentialStore
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PARAM_NAME
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow

enum class LocalCredentialState { STORED, ENVIRONMENT, UNREADABLE, NONE, DEFAULT, REMOTE }

suspend fun CredentialServerUiContext.localState(name: String): LocalCredentialState {
    if (provider.isManagedRemotely(name)) return LocalCredentialState.REMOTE
    return when (localStore.origin(name)) {
        CredentialOrigin.STORED -> LocalCredentialState.STORED
        CredentialOrigin.ENVIRONMENT -> LocalCredentialState.ENVIRONMENT
        CredentialOrigin.UNREADABLE -> LocalCredentialState.UNREADABLE
        CredentialOrigin.NONE -> if (name == CredentialNames.THEAUDIODB_API) LocalCredentialState.DEFAULT else LocalCredentialState.NONE
    }
}

internal fun UiRenderScope.localStateText(state: LocalCredentialState): String = t("credentials.origin.${state.name}")

internal fun localStateTone(state: LocalCredentialState): UiTone = when (state) {
    LocalCredentialState.STORED, LocalCredentialState.ENVIRONMENT, LocalCredentialState.DEFAULT -> UiTone.SUCCESS
    LocalCredentialState.REMOTE -> UiTone.PRIMARY
    LocalCredentialState.UNREADABLE -> UiTone.WARNING
    LocalCredentialState.NONE -> UiTone.MUTED
}

class LocalCredentialContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.LOCAL,
    kind = UiContributionKind.PAGE,
    titleKey = "credentials.localPage.title",
    icon = UiIcon(UiIconName.KEY),
    order = 70,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val name =
            requireNotNull(scope.context.params[PARAM_NAME]?.takeIf { it.isNotBlank() }) { "Missing credential name" }
        val entry = ui.localStore.entry(name)
            ?: return UiComponent.EmptyState(scope.t("credentials.notFound"), icon = UiIcon(UiIconName.WARNING))
        val state = ui.localState(name)
        val params = mapOf(PARAM_NAME to UiValue.of(name))
        val children = mutableListOf<UiComponent>(
            UiComponent.Text(scope.t("credentials.name.$name"), UiTextStyle.TITLE),
            UiComponent.Text(scope.t("credentials.about.$name"), UiTextStyle.CAPTION, UiTone.MUTED),
            UiComponent.Badge(
                scope.localStateText(state),
                localStateTone(state),
                UiIcon(if (state == LocalCredentialState.REMOTE) UiIconName.PLUG else UiIconName.KEY),
            ),
            UiComponent.Text(hint(scope, entry, state), UiTextStyle.CAPTION, UiTone.MUTED),
        )
        val buttons = mutableListOf(
            UiComponent.Button(
                scope.t("credentials.test"),
                UiAction.Invoke(id, ACTION_TEST, params = params),
                UiButtonStyle.SECONDARY,
                UiIcon(UiIconName.CHECK)
            ),
        )
        if (state != LocalCredentialState.REMOTE) {
            children += UiComponent.Form(
                id = FORM_LOCAL,
                submit = UiAction.Invoke(id, ACTION_SAVE, params = params, formId = FORM_LOCAL),
                submitLabel = scope.t("credentials.save"),
                children = fields(scope, entry, state == LocalCredentialState.STORED),
            )
            if (state == LocalCredentialState.STORED || state == LocalCredentialState.UNREADABLE) {
                buttons += UiComponent.Button(
                    scope.t("credentials.clear"),
                    UiAction.Invoke(
                        id,
                        ACTION_CLEAR,
                        params = params,
                        confirmText = scope.t("credentials.clearConfirm")
                    ),
                    UiButtonStyle.DESTRUCTIVE,
                    UiIcon(UiIconName.CLOSE),
                )
            }
        }
        children += UiComponent.Divider
        children += UiComponent.Column(buttons, spacing = UiSpacing.SMALL, align = UiAlign.START)
        return UiComponent.Column(children)
    }

    private fun hint(scope: UiRenderScope, entry: LocalCredentialEntry<*>, state: LocalCredentialState): String =
        when (state) {
            LocalCredentialState.UNREADABLE ->
                scope.t(if (entry.environment() != null) "credentials.hint.UNREADABLE_ENV" else "credentials.hint.UNREADABLE")

            else -> scope.t("credentials.hint.${state.name}")
        }

    private suspend fun fields(
        scope: UiRenderScope,
        entry: LocalCredentialEntry<*>,
        stored: Boolean
    ): List<UiComponent> {
        val keep = if (stored) scope.t("credentials.keepHint") else null
        val current = entry.current()
        return entry.fields.map { field ->
            val label = scope.t("credentials.field.${field.name}")
            when {
                field.file -> UiComponent.FileField(
                    key = field.name,
                    label = label,
                    accept = if (field.name == LocalCredentialStore.FIELD_P8) listOf(".p8") else emptyList(),
                    secret = true,
                    helper = keep ?: scope.t("credentials.field.${field.name}Helper"),
                    required = !stored,
                )

                field.secret -> UiComponent.TextField(
                    field.name,
                    label,
                    secret = true,
                    helper = keep,
                    required = !stored
                )

                else -> UiComponent.TextField(field.name, label, value = current?.get(field.name), required = !stored)
            }
        }
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        if (actionId !in ACTIONS) return super.invoke(scope, actionId, values)
        val name = values.text(PARAM_NAME).ifEmpty { scope.context.params[PARAM_NAME].orEmpty() }
        val entry =
            ui.localStore.entry(name) ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("credentials.notFound"))
        return when (actionId) {
            ACTION_SAVE -> save(scope, entry, values)
            ACTION_CLEAR -> {
                if (ui.provider.isManagedRemotely(name)) return UiInvokeResult(
                    UiInvokeStatus.ERROR,
                    scope.t("credentials.error.managed")
                )
                ui.localStore.clear(name)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("credentials.cleared"), refresh = true)
            }

            else -> test(scope, name)
        }
    }

    private suspend fun save(
        scope: UiRenderScope,
        entry: LocalCredentialEntry<*>,
        values: Map<String, UiValue>
    ): UiInvokeResult {
        if (ui.provider.isManagedRemotely(entry.name)) return UiInvokeResult(
            UiInvokeStatus.ERROR,
            scope.t("credentials.error.managed")
        )
        val stored = entry.origin() == CredentialOrigin.STORED
        val updates = entry.fields.mapNotNull { field ->
            values.text(field.name).takeIf { it.isNotEmpty() }?.let { field.name to it }
        }.toMap()
        val errors = mutableMapOf<String, String>()
        if (!stored) {
            entry.fields.filter { it.name !in updates }
                .forEach { errors[it.name] = scope.t("credentials.error.required") }
        }
        updates[LocalCredentialStore.FIELD_P8]?.let { pem ->
            if (!AppleDeveloperTokenSigner.isValidPrivateKey(pem)) errors[LocalCredentialStore.FIELD_P8] =
                scope.t("credentials.error.p8")
        }
        if (errors.isNotEmpty()) return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = errors)
        if (updates.isNotEmpty()) ui.localStore.store(entry.name, updates)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("credentials.saved"), refresh = true)
    }

    private suspend fun test(scope: UiRenderScope, name: String): UiInvokeResult {
        val resolved = runCatchingCancellable { ui.provider.resolve(name) }.getOrNull()
            ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("credentials.testFailed"), refresh = true)
        val expiry = resolved.expiresAt?.let { scope.t("credentials.expires", "time" to formatTime(scope, it)) }
        return UiInvokeResult(
            UiInvokeStatus.OK,
            listOfNotNull(scope.t("credentials.testOk"), expiry).joinToString(". "),
            refresh = true
        )
    }

    companion object {
        const val FORM_LOCAL = "localCredential"
        const val ACTION_SAVE = "save"
        const val ACTION_CLEAR = "clear"
        const val ACTION_TEST = "test"
        private val ACTIONS = setOf(ACTION_SAVE, ACTION_CLEAR, ACTION_TEST)
    }
}
