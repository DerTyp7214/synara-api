package dev.dertyp.services.ui.credentialserver

import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiSlots
import kotlinx.coroutines.flow.Flow

class CredentialsEntryContribution(private val ui: CredentialServerUiContext) : UiContribution(
    id = CredentialServerPages.ENTRY,
    kind = UiContributionKind.SLOT,
    slot = UiSlots.SETTINGS,
    titleKey = "credentials.title",
    descriptionKey = "credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 70,
    access = UiAccess(requiresAdmin = true),
) {
    override fun changes(scope: UiRenderScope): Flow<Unit> = ui.changes()

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val entries = ui.localStore.entries
        val available = entries.count { ui.provider.isAvailable(it.name) }
        return UiComponent.ListItem(
            title = scope.t("credentials.title"),
            subtitle = scope.t("credentials.description"),
            icon = icon,
            trailing = scope.t(
                "credentials.summary",
                "count" to available.toString(),
                "total" to entries.size.toString()
            ),
            action = UiAction.OpenPage(CredentialServerPages.OVERVIEW),
        )
    }
}
