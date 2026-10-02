package dev.dertyp.services.ui.credentialserver

import dev.dertyp.core.runCatchingCancellable
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ui.credentialserver.CredentialServerPages.PREFIX
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow

class CredentialsOverviewContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.OVERVIEW,
    kind = UiContributionKind.PAGE,
    titleKey = "credentials.title",
    descriptionKey = "credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 70,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val connection = ui.connection()
        val status = ui.linkStatus()
        val admin = status == CredentialServerLinkStatus.CONNECTED && connection.adminConfigured && ui.isAdmin()
        val children = mutableListOf<UiComponent>(
            UiComponent.ListItem(
                title = scope.t("$PREFIX.nav.server"),
                icon = UiIcon(UiIconName.PLUG),
                trailing = scope.linkStatusText(status),
                action = UiAction.OpenPage(CredentialServerPages.SERVER),
            ),
        )
        if (admin) {
            children += UiComponent.ListItem(
                title = scope.t("$PREFIX.nav.clients"),
                icon = UiIcon(UiIconName.USER),
                trailing = runCatchingCancellable { ui.admin.listClients() }.getOrNull()?.size?.toString(),
                action = UiAction.OpenPage(CredentialServerPages.CLIENTS),
            )
        }
        children += localSection(scope)
        return UiComponent.Column(children)
    }

    private suspend fun localSection(scope: UiRenderScope): UiComponent {
        val rows = ui.localStore.entries.map { entry ->
            UiComponent.ListItem(
                title = scope.t("credentials.name.${entry.name}"),
                subtitle = scope.t("credentials.about.${entry.name}"),
                icon = UiIcon(UiIconName.KEY),
                trailing = scope.localStateText(ui.localState(entry.name)),
                action = UiAction.OpenPage(CredentialServerPages.LOCAL, mapOf(CredentialServerPages.PARAM_NAME to entry.name)),
            )
        }
        return UiComponent.Section(
            title = scope.t("credentials.local"),
            children = listOf(UiComponent.Text(scope.t("credentials.localHint"), UiTextStyle.CAPTION, UiTone.MUTED)) + rows,
        )
    }
}

internal fun Map<String, UiValue>.text(key: String): String = this[key]?.text?.trim().orEmpty()
