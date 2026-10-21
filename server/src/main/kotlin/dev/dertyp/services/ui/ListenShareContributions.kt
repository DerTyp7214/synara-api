package dev.dertyp.services.ui

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ilike
import dev.dertyp.data.UserProfile
import dev.dertyp.db.UserTable
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ListenShareLink
import dev.dertyp.services.ListenSharePair
import dev.dertyp.services.ListenShareService
import dev.dertyp.services.UserService
import dev.dertyp.ui.*
import org.jetbrains.exposed.v1.jdbc.andWhere
import java.util.UUID

private const val ENTRY_ID = "core.listenshare.entry"
private const val ADMIN_ENTRY_ID = "core.listenshare.admin.entry"
private const val ACTION_TOGGLE = "toggle"
private const val ACTION_UNLINK = "unlink"
private const val ACTION_CONNECT = "connect"
private const val ACTION_LINK = "link"
private const val FIELD_LINK_ID = "linkId"
private const val FIELD_USER_ID = "userId"
private const val FIELD_USER_A = "userA"
private const val FIELD_USER_B = "userB"
private const val FIELD_USE_FOR_RECOMMENDATIONS = "useForRecommendations"
private const val FORM_LINK = "link"
private const val FORM_TOGGLE_PREFIX = "toggle:"
private const val SEARCH_LIMIT = 20

private fun uuidOf(values: Map<String, UiValue>, key: String): PlatformUUID? =
    values[key]?.text?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun UserProfile.title(): String = displayName?.takeIf { it.isNotBlank() } ?: username

private fun imageOf(imageId: PlatformUUID?, blurHash: String?): UiImageRef? =
    imageId?.let { UiImageRef(it, blurHash) }

private fun UserProfile.subtitle(): String? = if (title() == username) null else username

private fun linkEditor(
    scope: UiRenderScope,
    contributionId: String,
    linkId: PlatformUUID,
    header: UiComponent,
    useForRecommendations: Boolean,
): UiComponent.Form {
    val formId = "$FORM_TOGGLE_PREFIX$linkId"
    val linkParam = mapOf(FIELD_LINK_ID to UiValue.of(linkId.toString()))
    return UiComponent.Form(
        id = formId,
        children = listOf(
            header,
            UiComponent.Switch(
                FIELD_USE_FOR_RECOMMENDATIONS,
                scope.t("listenShare.useForRecommendations"),
                useForRecommendations,
                helper = scope.t("listenShare.useForRecommendations.helper"),
            ),
        ),
        submit = UiAction.Invoke(contributionId, ACTION_TOGGLE, params = linkParam, formId = formId),
        submitLabel = scope.t("listenShare.save"),
        actions = listOf(
            UiComponent.Button(
                scope.t("listenShare.disconnect"),
                UiAction.Invoke(
                    contributionId,
                    ACTION_UNLINK,
                    params = linkParam,
                    confirmText = scope.t("listenShare.disconnectConfirm"),
                ),
                UiButtonStyle.TEXT,
                UiIcon(UiIconName.CLOSE),
            ),
        ),
    )
}

private suspend fun ListenShareService.toggle(
    scope: UiRenderScope,
    values: Map<String, UiValue>,
): UiInvokeResult {
    val linkId = uuidOf(values, FIELD_LINK_ID)
        ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("listenShare.error.noLink"))
    setUseForRecommendations(scope.user, linkId, values[FIELD_USE_FOR_RECOMMENDATIONS]?.flag ?: false)
    return UiInvokeResult(UiInvokeStatus.OK, scope.t("listenShare.saved"), refresh = true)
}

private suspend fun ListenShareService.disconnect(
    scope: UiRenderScope,
    values: Map<String, UiValue>,
): UiInvokeResult {
    val linkId = uuidOf(values, FIELD_LINK_ID)
        ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("listenShare.error.noLink"))
    unlink(scope.user, linkId)
    return UiInvokeResult(UiInvokeStatus.OK, scope.t("listenShare.disconnected"), refresh = true)
}

class ListenShareEntryContribution : UiContribution(
    id = ENTRY_ID,
    kind = UiContributionKind.SLOT,
    titleKey = "listenShare.title",
    slot = UiSlots.SETTINGS,
    descriptionKey = "listenShare.description",
    icon = UiIcon(UiIconName.LINK),
    order = 65,
) {
    override suspend fun render(scope: UiRenderScope): UiComponent = UiComponent.ListItem(
        title = scope.t("listenShare.title"),
        subtitle = scope.t("listenShare.description"),
        icon = icon,
        action = UiAction.OpenPage(ListenShareService.SETTINGS_CONTRIBUTION_ID),
    )
}

class ListenShareSettingsContribution(private val listenShare: ListenShareService) : UiContribution(
    id = ListenShareService.SETTINGS_CONTRIBUTION_ID,
    kind = UiContributionKind.PAGE,
    titleKey = "listenShare.title",
    descriptionKey = "listenShare.description",
    icon = UiIcon(UiIconName.LINK),
    order = 65,
) {
    override suspend fun render(scope: UiRenderScope): UiComponent {
        val links = listenShare.linksOf(scope.user.id)
        val candidates = listenShare.candidates(scope.user.id)
        if (links.isEmpty() && candidates.isEmpty()) {
            return UiComponent.EmptyState(
                title = scope.t("listenShare.empty"),
                description = scope.t("listenShare.empty.description"),
                icon = icon,
            )
        }
        val children = ArrayList<UiComponent>()
        if (links.isNotEmpty()) {
            children += UiComponent.Section(
                title = scope.t("listenShare.linked"),
                children = links.map { linkForm(scope, it) },
            )
        }
        if (candidates.isNotEmpty()) {
            children += UiComponent.Section(
                title = scope.t("listenShare.available"),
                children = candidates.map { candidate ->
                    UiComponent.Card(
                        title = candidate.title(),
                        subtitle = candidate.subtitle(),
                        icon = UiIcon(UiIconName.USER),
                        image = imageOf(candidate.profileImageId, candidate.blurHash),
                        children = emptyList(),
                        actions = listOf(
                            UiComponent.Button(
                                scope.t("listenShare.connect"),
                                UiAction.Invoke(
                                    id,
                                    ACTION_CONNECT,
                                    params = mapOf(FIELD_USER_ID to UiValue.of(candidate.id.toString())),
                                ),
                                UiButtonStyle.PRIMARY,
                                UiIcon(UiIconName.LINK),
                            ),
                        ),
                    )
                },
            )
        }
        return UiComponent.Column(children)
    }

    private fun linkForm(scope: UiRenderScope, link: ListenShareLink): UiComponent.Form = linkEditor(
        scope,
        id,
        link.id,
        UiComponent.ListItem(
            title = link.partner.title(),
            subtitle = link.partner.subtitle(),
            icon = UiIcon(UiIconName.USER),
            image = imageOf(link.partner.profileImageId, link.partner.blurHash),
        ),
        link.useForRecommendations,
    )

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult =
        when (actionId) {
            ACTION_TOGGLE -> listenShare.toggle(scope, values)
            ACTION_UNLINK -> listenShare.disconnect(scope, values)
            ACTION_CONNECT -> {
                val userId = uuidOf(values, FIELD_USER_ID)
                if (userId == null) UiInvokeResult(UiInvokeStatus.ERROR, scope.t("listenShare.error.noUser"))
                else {
                    listenShare.link(scope.user, scope.user.id, userId, useForRecommendations = false)
                    UiInvokeResult(UiInvokeStatus.OK, scope.t("listenShare.connected"), refresh = true)
                }
            }

            else -> super.invoke(scope, actionId, values)
        }
}

class ListenShareAdminEntryContribution : UiContribution(
    id = ADMIN_ENTRY_ID,
    kind = UiContributionKind.SLOT,
    titleKey = "listenShare.admin.title",
    slot = UiSlots.ADMIN_DASHBOARD,
    descriptionKey = "listenShare.admin.description",
    icon = UiIcon(UiIconName.LINK),
    order = 65,
    access = UiAccess(requiresAdmin = true),
) {
    override suspend fun render(scope: UiRenderScope): UiComponent = UiComponent.ListItem(
        title = scope.t("listenShare.admin.title"),
        subtitle = scope.t("listenShare.admin.description"),
        icon = icon,
        action = UiAction.OpenPage(ListenShareService.ADMIN_CONTRIBUTION_ID),
    )
}

class ListenShareAdminContribution(
    private val listenShare: ListenShareService,
    private val userService: UserService,
) : UiContribution(
    id = ListenShareService.ADMIN_CONTRIBUTION_ID,
    kind = UiContributionKind.PAGE,
    titleKey = "listenShare.admin.title",
    descriptionKey = "listenShare.admin.description",
    icon = UiIcon(UiIconName.LINK),
    order = 65,
    access = UiAccess(requiresAdmin = true),
) {
    override suspend fun render(scope: UiRenderScope): UiComponent {
        val children = ArrayList<UiComponent>()
        val links = listenShare.allLinks()
        if (links.isEmpty()) {
            children += UiComponent.Text(scope.t("listenShare.admin.none"), UiTextStyle.BODY, UiTone.MUTED)
        } else {
            children += UiComponent.Section(
                title = scope.t("listenShare.admin.links"),
                children = links.map { linkForm(scope, it) },
            )
        }
        children += UiComponent.Form(
            id = FORM_LINK,
            children = listOf(
                UiComponent.SearchField(
                    FIELD_USER_A,
                    scope.t("listenShare.admin.userA"),
                    placeholder = scope.t("listenShare.admin.userPlaceholder"),
                    required = true,
                ),
                UiComponent.SearchField(
                    FIELD_USER_B,
                    scope.t("listenShare.admin.userB"),
                    placeholder = scope.t("listenShare.admin.userPlaceholder"),
                    required = true,
                ),
                UiComponent.Switch(
                    FIELD_USE_FOR_RECOMMENDATIONS,
                    scope.t("listenShare.useForRecommendations"),
                    false,
                    helper = scope.t("listenShare.useForRecommendations.helper"),
                ),
            ),
            submit = UiAction.Invoke(id, ACTION_LINK, formId = FORM_LINK),
            submitLabel = scope.t("listenShare.admin.link"),
        )
        return UiComponent.Column(children)
    }

    private fun linkForm(scope: UiRenderScope, pair: ListenSharePair): UiComponent.Form = linkEditor(
        scope,
        id,
        pair.id,
        UiComponent.ListItem(
            title = "${pair.userA.username} / ${pair.userB.username}",
            icon = UiIcon(UiIconName.LINK),
        ),
        pair.useForRecommendations,
    )

    override suspend fun search(scope: UiRenderScope, key: String, query: String): List<UiOption> {
        if (key != FIELD_USER_A && key != FIELD_USER_B) return emptyList()
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return userService.queryUser {
            andWhere { UserTable.username ilike "%$term%" }
                .orderBy(UserTable.username)
                .limit(SEARCH_LIMIT)
        }.map { UiOption(it.id.toString(), it.username, UiIcon(UiIconName.USER), imageOf(it.profileImageId, it.blurHash)) }
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult =
        when (actionId) {
            ACTION_TOGGLE -> listenShare.toggle(scope, values)
            ACTION_UNLINK -> listenShare.disconnect(scope, values)
            ACTION_LINK -> link(scope, values)
            else -> super.invoke(scope, actionId, values)
        }

    private suspend fun link(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val userA = uuidOf(values, FIELD_USER_A)
        val userB = uuidOf(values, FIELD_USER_B)
        val errors = buildMap {
            if (userA == null) put(FIELD_USER_A, scope.t("listenShare.error.noUser"))
            if (userB == null) put(FIELD_USER_B, scope.t("listenShare.error.noUser"))
        }
        if (userA == null || userB == null) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = errors)
        }
        listenShare.link(
            scope.user,
            userA,
            userB,
            values[FIELD_USE_FOR_RECOMMENDATIONS]?.flag ?: false,
        )
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("listenShare.connected"), refresh = true)
    }
}
