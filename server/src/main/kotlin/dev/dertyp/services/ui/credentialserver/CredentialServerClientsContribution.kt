package dev.dertyp.services.ui.credentialserver

import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.credentials.CreateClientRequest
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PREFIX
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow

class CredentialServerClientsContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.CLIENTS,
    kind = UiContributionKind.PAGE,
    titleKey = "credentialserver.clients.title",
    icon = UiIcon(UiIconName.USER),
    order = 71,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        if (!ui.isAdmin()) return UiComponent.EmptyState(scope.t("credentials.adminRequired"), icon = UiIcon(UiIconName.WARNING))
        val clients = runCatchingCancellable { ui.admin.listClients() }.getOrElse {
            return UiComponent.Text(scope.t("$PREFIX.loadFailed", "reason" to scope.errorText(it)), UiTextStyle.BODY, UiTone.ERROR)
        }
        val connection = ui.connection()
        val clientItems = clients.sortedBy { it.name.lowercase() }.map { client ->
            val state = scope.t(if (client.enabled) "$PREFIX.enabled" else "$PREFIX.disabled")
            UiComponent.ListItem(
                title = client.name,
                subtitle = listOfNotNull(
                    client.clientId,
                    scope.t("$PREFIX.thisServer").takeIf { client.clientId == connection.clientId },
                ).joinToString(" · "),
                icon = UiIcon(UiIconName.USER),
                trailing = "$state · ${scope.t("$PREFIX.grantCount", "count" to client.grants.size.toString())}",
                action = UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf(CredentialServerPages.PARAM_ID to client.id)),
            )
        }
        val children = buildList {
            if (clientItems.isEmpty()) add(UiComponent.Text(scope.t("$PREFIX.noClients"), UiTextStyle.CAPTION, UiTone.MUTED))
            addAll(clientItems)
            add(
                UiComponent.Form(
                    id = FORM_CREATE_CLIENT,
                    submit = UiAction.Invoke(id, ACTION_CREATE_CLIENT, formId = FORM_CREATE_CLIENT),
                    submitLabel = scope.t("$PREFIX.createClient"),
                    children = listOf(UiComponent.TextField(FIELD_CLIENT_NAME, scope.t("$PREFIX.clientName"), required = true)),
                ),
            )
        }
        return UiComponent.Column(children)
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult = when (actionId) {
        ACTION_CREATE_CLIENT -> createClient(scope, values)
        else -> super.invoke(scope, actionId, values)
    }

    private suspend fun createClient(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val name = values.text(FIELD_CLIENT_NAME)
        if (name.isEmpty()) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(FIELD_CLIENT_NAME to scope.t("$PREFIX.error.clientName")))
        }
        val created = ui.admin.createClient(CreateClientRequest(name))
        ui.reveal.put(scope.user.id, created.client.id, created.clientSecret)
        return UiInvokeResult(
            UiInvokeStatus.OK,
            scope.t("$PREFIX.clientCreated", "name" to created.client.name),
            refresh = true,
            next = UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf(CredentialServerPages.PARAM_ID to created.client.id)),
        )
    }

    companion object {
        const val FORM_CREATE_CLIENT = "credentialServerCreateClient"
        const val FIELD_CLIENT_NAME = "clientName"
        const val ACTION_CREATE_CLIENT = "createClient"
    }
}
