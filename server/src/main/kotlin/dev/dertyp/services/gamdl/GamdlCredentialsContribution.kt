package dev.dertyp.services.gamdl

import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.import.GamdlCredentials
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue

class GamdlCredentialsContribution(private val gamdlService: GamdlService) : UiContribution(
    id = "gamdl.credentials",
    kind = UiContributionKind.SLOT,
    slot = UiSlots.IMPORTER,
    titleKey = "gamdl.credentials.title",
    descriptionKey = "gamdl.credentials.description",
    icon = UiIcon(UiIconName.KEY),
    order = 50,
    access = UiAccess(requiresAdmin = true),
) {
    override suspend fun render(scope: UiRenderScope): UiComponent {
        val configured = gamdlService.tokenFileExists()
        return UiComponent.Card(
            title = scope.t("gamdl.credentials.title"),
            subtitle = scope.t("gamdl.credentials.description"),
            icon = icon,
            children = listOf(
                UiComponent.Badge(
                    if (configured) scope.t("gamdl.credentials.configured") else scope.t("gamdl.credentials.missing"),
                    if (configured) UiTone.SUCCESS else UiTone.WARNING,
                ),
                UiComponent.Form(
                    id = "gamdl",
                    submit = UiAction.Invoke(id, "save", formId = "gamdl"),
                    submitLabel = scope.t("gamdl.credentials.save"),
                    children = listOf(
                        UiComponent.TextField(
                            key = "cookiesTxt",
                            label = scope.t("gamdl.credentials.cookies"),
                            helper = scope.t("gamdl.credentials.cookiesHelper"),
                            multiline = true,
                            secret = true,
                            required = true,
                        ),
                        UiComponent.TextField(
                            key = "wvdBase64",
                            label = scope.t("gamdl.credentials.wvd"),
                            helper = scope.t("gamdl.credentials.wvdHelper"),
                            secret = true,
                        ),
                    ),
                ),
            ),
        )
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        if (actionId != "save") return super.invoke(scope, actionId, values)
        val cookies = values["cookiesTxt"]?.text?.trim().orEmpty()
        if (cookies.isEmpty()) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf("cookiesTxt" to scope.t("gamdl.credentials.error.cookies")))
        }
        val wvd = values["wvdBase64"]?.text?.trim()?.takeIf { it.isNotEmpty() }
        gamdlService.provideCredentials(GamdlCredentials(cookiesTxt = cookies, wvdBase64 = wvd))
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("gamdl.credentials.saved"), refresh = true)
    }
}
