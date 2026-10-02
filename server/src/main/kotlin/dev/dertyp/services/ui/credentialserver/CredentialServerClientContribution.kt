package dev.dertyp.services.ui.credentialserver

import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.credentials.ClientSummary
import dev.dertyp.credentials.GrantSpec
import dev.dertyp.credentials.UpdateClientRequest
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PARAM_ID
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PREFIX
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow

class CredentialServerClientContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.CLIENT,
    kind = UiContributionKind.PAGE,
    titleKey = "credentialserver.client.title",
    icon = UiIcon(UiIconName.USER),
    order = 72,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val clientId = requireNotNull(scope.context.params[PARAM_ID]?.takeIf { it.isNotBlank() }) { "Missing client id" }
        if (!ui.isAdmin()) return UiComponent.EmptyState(scope.t("credentials.adminRequired"), icon = UiIcon(UiIconName.WARNING))
        val client = runCatchingCancellable { ui.admin.getClient(clientId) }.getOrElse { error ->
            return if (error.isNotFound()) {
                UiComponent.EmptyState(scope.t("$PREFIX.client.notFound"), icon = UiIcon(UiIconName.WARNING))
            } else {
                UiComponent.Text(scope.t("$PREFIX.loadFailed", "reason" to scope.errorText(error)), UiTextStyle.BODY, UiTone.ERROR)
            }
        }
        val credentials = runCatchingCancellable { ui.admin.listCredentials() }.getOrDefault(emptyList()).sortedBy { it.name }
        val params = mapOf(PARAM_ID to UiValue.of(client.id))
        val children = mutableListOf<UiComponent>()
        ui.reveal.peek(scope.user.id, client.id)?.let { secret ->
            children += UiComponent.Card(
                title = scope.t("$PREFIX.client.secretTitle"),
                icon = UiIcon(UiIconName.KEY),
                tone = UiTone.WARNING,
                children = listOf(
                    UiComponent.Text(secret, UiTextStyle.CODE),
                    UiComponent.Text(scope.t("$PREFIX.client.secretHint"), UiTextStyle.CAPTION, UiTone.MUTED),
                    UiComponent.Button(
                        scope.t("$PREFIX.client.hideSecret"),
                        UiAction.Invoke(id, ACTION_HIDE_SECRET, params = params),
                        UiButtonStyle.SECONDARY,
                        UiIcon(UiIconName.CLOSE),
                    ),
                ),
            )
        }
        children += header(scope, client)
        val grants = client.grants.associateBy { it.name }
        val grantFields = credentials.flatMap { credential ->
            val grant = grants[credential.name]
            val label = ui.credentialText(scope, credential.name).label
            buildList {
                add(UiComponent.Switch(grantKey(credential.name), label, grant != null, helper = "${credential.name} · ${scope.kindText(credential.kind)}"))
                if (writeBackCapable(credential.kind)) {
                    add(UiComponent.Switch(writeBackKey(credential.name), scope.t("$PREFIX.client.writeBack", "name" to label), grant?.writeBack == true))
                }
            }
        }
        children += UiComponent.Form(
            id = FORM_CLIENT,
            submit = UiAction.Invoke(id, ACTION_SAVE, params = params, formId = FORM_CLIENT),
            submitLabel = scope.t("$PREFIX.save"),
            children = buildList {
                add(UiComponent.Switch(FIELD_ENABLED, scope.t("$PREFIX.client.enabled"), client.enabled))
                add(
                    UiComponent.Section(
                        title = scope.t("$PREFIX.client.grants"),
                        children = grantFields.ifEmpty { listOf(UiComponent.Text(scope.t("$PREFIX.noCredentials"), UiTextStyle.CAPTION, UiTone.MUTED)) },
                    ),
                )
            },
        )
        children += UiComponent.Divider
        children += UiComponent.Column(
            listOf(
                UiComponent.Button(
                    scope.t("$PREFIX.client.rotate"),
                    UiAction.Invoke(id, ACTION_ROTATE, params = params, confirmText = scope.t("$PREFIX.client.rotateConfirm")),
                    UiButtonStyle.SECONDARY,
                    UiIcon(UiIconName.SYNC),
                ),
                UiComponent.Button(
                    scope.t("$PREFIX.client.revoke"),
                    UiAction.Invoke(id, ACTION_REVOKE, params = params, confirmText = scope.t("$PREFIX.client.revokeConfirm")),
                    UiButtonStyle.SECONDARY,
                    UiIcon(UiIconName.CLOSE),
                ),
                UiComponent.Button(
                    scope.t("$PREFIX.client.delete"),
                    UiAction.Invoke(id, ACTION_DELETE, params = params, confirmText = scope.t("$PREFIX.client.deleteConfirm")),
                    UiButtonStyle.DESTRUCTIVE,
                    UiIcon(UiIconName.CLOSE),
                ),
            ),
            spacing = UiSpacing.SMALL,
            align = UiAlign.START,
        )
        return UiComponent.Column(children)
    }

    private suspend fun header(scope: UiRenderScope, client: ClientSummary): UiComponent {
        val thisServer = client.clientId == ui.connection().clientId
        val badges = listOfNotNull(
            UiComponent.Badge(scope.t(if (client.enabled) "$PREFIX.enabled" else "$PREFIX.disabled"), if (client.enabled) UiTone.SUCCESS else UiTone.MUTED),
            UiComponent.Badge(scope.t("$PREFIX.thisServer"), UiTone.PRIMARY).takeIf { thisServer },
        )
        return UiComponent.Column(
            listOf(
                UiComponent.Text(client.name, UiTextStyle.TITLE),
                UiComponent.Row(badges, spacing = UiSpacing.SMALL),
                UiComponent.Text(scope.t("$PREFIX.client.clientId", "clientId" to client.clientId), UiTextStyle.CAPTION, UiTone.MUTED),
                UiComponent.Text(
                    client.lastTokenAt?.let { scope.t("$PREFIX.client.lastToken", "time" to formatTime(scope, it)) } ?: scope.t("$PREFIX.client.neverUsed"),
                    UiTextStyle.CAPTION,
                    UiTone.MUTED,
                ),
            ),
            spacing = UiSpacing.SMALL,
        )
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        if (actionId !in ACTIONS) return super.invoke(scope, actionId, values)
        val clientId = values.text(PARAM_ID).ifEmpty { scope.context.params[PARAM_ID].orEmpty() }
        if (clientId.isEmpty()) return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("$PREFIX.error.noClient"))
        return when (actionId) {
            ACTION_SAVE -> save(scope, clientId, values)
            ACTION_ROTATE -> {
                val rotated = ui.admin.rotateSecret(clientId)
                ui.reveal.put(scope.user.id, rotated.client.id, rotated.clientSecret)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.client.rotated"), refresh = true)
            }
            ACTION_HIDE_SECRET -> {
                ui.reveal.remove(scope.user.id, clientId)
                UiInvokeResult(UiInvokeStatus.OK, refresh = true)
            }
            ACTION_REVOKE -> {
                ui.admin.revokeTokens(clientId)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.client.revoked"), refresh = true)
            }
            else -> {
                ui.admin.deleteClient(clientId)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.client.deleted"), next = UiAction.OpenPage(CredentialServerPages.CLIENTS))
            }
        }
    }

    private suspend fun save(scope: UiRenderScope, clientId: String, values: Map<String, UiValue>): UiInvokeResult {
        val client = ui.admin.getClient(clientId)
        val credentials = ui.admin.listCredentials()
        val enabled = values[FIELD_ENABLED]?.flag ?: client.enabled
        if (enabled != client.enabled) ui.admin.updateClient(clientId, UpdateClientRequest(enabled = enabled))
        val grants = credentials.filter { values[grantKey(it.name)]?.flag == true }.map {
            GrantSpec(it.name, writeBack = writeBackCapable(it.kind) && values[writeBackKey(it.name)]?.flag == true)
        }
        val current = client.grants.map { GrantSpec(it.name, it.writeBack) }.toSet()
        if (grants.toSet() != current) ui.admin.setGrants(clientId, grants)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("$PREFIX.saved"), refresh = true)
    }

    companion object {
        const val FORM_CLIENT = "credentialServerClient"
        const val FIELD_ENABLED = "enabled"
        const val FIELD_GRANT_PREFIX = "grant:"
        const val FIELD_WRITE_BACK_PREFIX = "writeBack:"
        const val ACTION_SAVE = "save"
        const val ACTION_ROTATE = "rotate"
        const val ACTION_REVOKE = "revoke"
        const val ACTION_DELETE = "delete"
        const val ACTION_HIDE_SECRET = "hideSecret"
        private val ACTIONS = setOf(ACTION_SAVE, ACTION_ROTATE, ACTION_HIDE_SECRET, ACTION_REVOKE, ACTION_DELETE)

        fun grantKey(name: String) = "$FIELD_GRANT_PREFIX$name"

        fun writeBackKey(name: String) = "$FIELD_WRITE_BACK_PREFIX$name"
    }
}
