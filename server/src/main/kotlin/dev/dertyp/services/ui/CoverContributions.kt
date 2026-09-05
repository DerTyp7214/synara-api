package dev.dertyp.services.ui

import dev.dertyp.data.CoverGenerationParams
import dev.dertyp.data.CoverStyle
import dev.dertyp.data.CoverTarget
import dev.dertyp.data.CoverTargetType
import dev.dertyp.data.ImageSource
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.cover.CoverGenerationService
import dev.dertyp.services.jobs.JobService
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiOption
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

abstract class CoverSlotContribution(
    id: String,
    slot: String,
    private val type: CoverTargetType,
    private val service: CoverGenerationService,
    private val jobService: JobService,
) : UiContribution(
    id = id,
    kind = UiContributionKind.SLOT,
    titleKey = "cover.title",
    slot = slot,
    descriptionKey = "cover.description",
    icon = UiIcon(UiIconName.IMAGE),
    order = 50,
) {
    override suspend fun render(scope: UiRenderScope): UiComponent {
        val target = target(scope) ?: return UiComponent.Spacer()
        val row = service.row(target) ?: return UiComponent.Spacer()
        if (!isOwner(scope, row)) {
            return UiComponent.Text(scope.t("cover.notOwner"), UiTextStyle.CAPTION, UiTone.MUTED)
        }

        val sourceBadge = when (row.imageSource) {
            ImageSource.USER -> UiComponent.Badge(scope.t("cover.source.user"), UiTone.PRIMARY)
            ImageSource.GENERATED -> UiComponent.Badge(scope.t("cover.source.generated"), UiTone.SUCCESS)
            null -> UiComponent.Badge(scope.t("cover.source.none"), UiTone.MUTED)
        }
        val header = UiComponent.Row(
            children = listOfNotNull(
                row.imageId?.let { UiComponent.Image(imageId = it, rounded = true) },
                sourceBadge,
            ),
        )

        val fields = ArrayList<UiComponent>()
        fields += UiComponent.Select(
            key = FIELD_STYLE,
            label = scope.t("cover.style"),
            value = (row.coverStyle ?: CoverStyle.AUTO).name,
            options = CoverStyle.entries.map { UiOption(it.name, scope.t("cover.style.${it.name}")) },
        )
        val packs = service.options().packs
        fields += UiComponent.Select(
            key = FIELD_PACK,
            label = scope.t("cover.pack"),
            value = "",
            options = listOf(UiOption("", scope.t("cover.pack.auto"))) + packs.map { UiOption(it.id, it.name) },
        )
        fields += UiComponent.Switch(FIELD_TITLE, scope.t("cover.includeTitle"), value = true)
        if (service.nsfwEnabled) fields += UiComponent.Switch(FIELD_NSFW, scope.t("cover.allowNsfw"), value = false)

        val form = UiComponent.Form(
            id = FORM_ID,
            children = fields,
            submit = UiAction.Invoke(id, ACTION_APPLY, formId = FORM_ID),
            submitLabel = scope.t("cover.generate"),
            actions = listOf(
                UiComponent.Button(scope.t("cover.shuffle"), UiAction.Invoke(id, ACTION_SHUFFLE, formId = FORM_ID), UiButtonStyle.TEXT, UiIcon(UiIconName.SYNC)),
            ),
        )

        val actions = if (row.imageSource == ImageSource.USER) listOf(
            UiComponent.Button(
                scope.t("cover.reset"),
                UiAction.Invoke(id, ACTION_RESET, confirmText = scope.t("cover.resetConfirm")),
                UiButtonStyle.TEXT,
                UiIcon(UiIconName.CLOSE),
            ),
        ) else emptyList()

        return UiComponent.Card(
            children = listOf(header, form),
            title = scope.t("cover.title"),
            icon = icon,
            actions = actions,
        )
    }

    override fun changes(scope: UiRenderScope): Flow<Unit> = jobService.changes

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        val target = target(scope) ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("cover.error.notFound"))
        val row = service.row(target) ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("cover.error.notFound"))
        if (!isOwner(scope, row)) return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("cover.notOwner"))
        return when (actionId) {
            ACTION_APPLY -> apply(scope, target, params(values))
            ACTION_SHUFFLE -> apply(scope, target, params(values).copy(seed = Random.nextLong()))
            ACTION_RESET -> {
                service.reset(target)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("cover.resetDone"), refresh = true)
            }
            else -> super.invoke(scope, actionId, values)
        }
    }

    private suspend fun apply(scope: UiRenderScope, target: CoverTarget, params: CoverGenerationParams): UiInvokeResult =
        try {
            service.apply(target, params)
            UiInvokeResult(UiInvokeStatus.OK, scope.t("cover.applied"), refresh = true)
        } catch (e: IllegalArgumentException) {
            UiInvokeResult(UiInvokeStatus.ERROR, scope.t("cover.error.failed", "reason" to (e.message ?: "")))
        }

    internal fun params(values: Map<String, UiValue>): CoverGenerationParams = CoverGenerationParams(
        style = values[FIELD_STYLE]?.text?.let { name -> CoverStyle.entries.firstOrNull { it.name == name } } ?: CoverStyle.AUTO,
        allowNsfw = values[FIELD_NSFW]?.flag ?: false,
        includeTitle = values[FIELD_TITLE]?.flag ?: true,
        pack = values[FIELD_PACK]?.text?.takeIf { it.isNotBlank() },
    )

    private fun target(scope: UiRenderScope): CoverTarget? = scope.context.entityId?.let { CoverTarget(type, it) }

    private fun isOwner(scope: UiRenderScope, row: CoverGenerationService.TargetRow): Boolean =
        scope.user.isAdmin || row.creator == scope.user.id

    companion object {
        const val FORM_ID = "cover"
        const val FIELD_STYLE = "style"
        const val FIELD_PACK = "pack"
        const val FIELD_TITLE = "includeTitle"
        const val FIELD_NSFW = "allowNsfw"
        const val ACTION_APPLY = "apply"
        const val ACTION_SHUFFLE = "shuffle"
        const val ACTION_RESET = "reset"
    }
}

class PlaylistCoverContribution(service: CoverGenerationService, jobService: JobService) :
    CoverSlotContribution("core.cover.playlist", UiSlots.PLAYLIST_DETAIL, CoverTargetType.PLAYLIST, service, jobService)

class CollectionCoverContribution(service: CoverGenerationService, jobService: JobService) :
    CoverSlotContribution("core.cover.collection", UiSlots.COLLECTION_DETAIL, CoverTargetType.COLLECTION, service, jobService)
