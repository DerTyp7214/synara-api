package dev.dertyp.services.ui

import dev.dertyp.data.HueIntensity
import dev.dertyp.data.HueMotionMode
import dev.dertyp.data.HuePairingState
import dev.dertyp.data.HueStopMode
import dev.dertyp.data.HueTarget
import dev.dertyp.data.HueTargetType
import dev.dertyp.data.HueTransitionMode
import dev.dertyp.data.HueUserLink
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.hue.HueService
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiAlign
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiOption
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiSpacing
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class HueSettingsEntryContribution : UiContribution(
    id = "core.hue.entry",
    kind = UiContributionKind.SLOT,
    titleKey = "hue.title",
    slot = UiSlots.SETTINGS,
    descriptionKey = "hue.description",
    icon = UiIcon(UiIconName.PLUG),
    order = 60,
) {
    override suspend fun render(scope: UiRenderScope): UiComponent = UiComponent.ListItem(
        title = scope.t("hue.title"),
        subtitle = scope.t("hue.description"),
        icon = icon,
        action = UiAction.OpenPage(HueSettingsContribution.ID),
    )
}

class HueSettingsContribution(private val hue: HueService) : UiContribution(
    id = ID,
    kind = UiContributionKind.PAGE,
    titleKey = "hue.title",
    descriptionKey = "hue.description",
    icon = UiIcon(UiIconName.PLUG),
    order = 60,
) {
    override suspend fun render(scope: UiRenderScope): UiComponent {
        val bridges = hue.listBridges()
        val children = ArrayList<UiComponent>()

        hue.activePairings().forEach { session ->
            children += UiComponent.Live("$LIVE_PAIRING_PREFIX${session.ip}", pairingComponent(scope, session.state.value.state, session.state.value.message))
        }

        if (bridges.isEmpty()) {
            children += UiComponent.Text(scope.t("hue.noBridges"), UiTextStyle.BODY, UiTone.MUTED)
            val candidates = hue.cachedDiscovery().filter { !it.paired }
            if (candidates.isNotEmpty()) {
                children += UiComponent.Section(
                    title = scope.t("hue.discovered"),
                    children = candidates.map { candidate ->
                        UiComponent.ListItem(
                            title = candidate.name ?: candidate.ip,
                            subtitle = listOfNotNull(candidate.name?.let { candidate.ip }, candidate.bridgeId, candidate.modelId).joinToString(" · ").ifEmpty { null },
                            icon = UiIcon(UiIconName.PLUG),
                            action = UiAction.Invoke(id, ACTION_PAIR, params = mapOf(FIELD_IP to UiValue.of(candidate.ip))),
                        )
                    },
                )
            }
            children += UiComponent.Form(
                id = FORM_PAIR,
                children = listOf(UiComponent.TextField(FIELD_IP, scope.t("hue.ip"), placeholder = "192.168.1.2")),
                submit = UiAction.Invoke(id, ACTION_PAIR, formId = FORM_PAIR),
                submitLabel = scope.t("hue.pair"),
                actions = listOf(UiComponent.Button(scope.t("hue.discover"), UiAction.Invoke(id, ACTION_DISCOVER), UiButtonStyle.TEXT, UiIcon(UiIconName.SEARCH))),
            )
            return UiComponent.Column(children)
        }

        val links = hue.getLinks(scope.user.id).associateBy { it.bridgeId }
        bridges.forEach { bridge ->
            val link = links[bridge.id] ?: HueUserLink(bridgeId = bridge.id)
            val targets = runCatching { hue.listTargets(bridge.id) }
            val formId = "$FORM_LINK_PREFIX${bridge.id}"
            val fields = ArrayList<UiComponent>()
            fields += UiComponent.Row(
                listOfNotNull(
                    UiComponent.Badge(bridge.name, if (bridge.lastError == null) UiTone.SUCCESS else UiTone.WARNING, UiIcon(UiIconName.PLUG)),
                    UiComponent.Text(bridge.ip, UiTextStyle.CAPTION, UiTone.MUTED),
                ),
            )
            bridge.lastError?.let { fields += UiComponent.Text(scope.t("hue.bridge.unreachable", "reason" to it), UiTextStyle.CAPTION, UiTone.ERROR) }
            fields += UiComponent.Switch(FIELD_ENABLED, scope.t("hue.link.enabled"), link.enabled)
            targets.onSuccess { list ->
                val selected = link.targets.map { it.type to it.id }.toSet()
                fun section(key: String, type: HueTargetType) {
                    val items = list.filter { it.type == type }
                    if (items.isEmpty()) return
                    fields += UiComponent.Section(
                        title = scope.t(key),
                        collapsible = true,
                        collapsed = items.none { (it.type to it.id) in selected } && type == HueTargetType.LIGHT,
                        children = items.map { UiComponent.Switch(targetKey(it), it.name, (it.type to it.id) in selected) },
                    )
                }
                section("hue.targets.rooms", HueTargetType.ROOM)
                section("hue.targets.zones", HueTargetType.ZONE)
                section("hue.targets.lights", HueTargetType.LIGHT)
            }.onFailure {
                fields += UiComponent.Text(scope.t("hue.bridge.unreachable", "reason" to (it.message ?: "")), UiTextStyle.CAPTION, UiTone.ERROR)
            }
            fields += UiComponent.Select(FIELD_INTENSITY, scope.t("hue.intensity"), link.intensity.name, HueIntensity.entries.map { UiOption(it.name, scope.t("hue.intensity.${it.name}")) })
            fields += UiComponent.Select(FIELD_TRANSITION_MODE, scope.t("hue.transitionMode"), link.transitionMode.name, HueTransitionMode.entries.map { UiOption(it.name, scope.t("hue.transitionMode.${it.name}")) })
            fields += UiComponent.NumberField(FIELD_TRANSITION_MS, scope.t("hue.transitionMs"), link.transitionMs.toDouble(), min = 0.0, max = 5000.0, step = 50.0)
            fields += UiComponent.Select(FIELD_ON_STOP, scope.t("hue.onStop"), link.onStop.name, HueStopMode.entries.map { UiOption(it.name, scope.t("hue.onStop.${it.name}")) })
            fields += UiComponent.Select(FIELD_MOTION, scope.t("hue.motion"), link.motion.name, HueMotionMode.entries.map { UiOption(it.name, scope.t("hue.motion.${it.name}")) })

            val bridgeParam = mapOf(FIELD_BRIDGE to UiValue.of(bridge.id.toString()))
            children += UiComponent.Form(
                id = formId,
                children = fields,
                submit = UiAction.Invoke(id, ACTION_SAVE, params = bridgeParam, formId = formId),
                submitLabel = scope.t("hue.save"),
                actions = listOf(
                    UiComponent.Button(scope.t("hue.test"), UiAction.Invoke(id, ACTION_TEST, params = bridgeParam, formId = formId), UiButtonStyle.TEXT, UiIcon(UiIconName.PLAY)),
                ),
            )
        }

        val actions = ArrayList<UiComponent>()
        actions += UiComponent.Button(scope.t("hue.discover"), UiAction.Invoke(id, ACTION_DISCOVER), UiButtonStyle.TEXT, UiIcon(UiIconName.SEARCH))
        if (scope.user.isAdmin) bridges.forEach { bridge ->
            actions += UiComponent.Button(
                "${scope.t("hue.bridge.remove")}: ${bridge.name}",
                UiAction.Invoke(id, ACTION_REMOVE, params = mapOf(FIELD_BRIDGE to UiValue.of(bridge.id.toString())), confirmText = scope.t("hue.bridge.removeConfirm")),
                UiButtonStyle.TEXT,
                UiIcon(UiIconName.CLOSE),
            )
        }
        children += UiComponent.Divider
        children += UiComponent.Column(actions, spacing = UiSpacing.SMALL, align = UiAlign.START)
        return UiComponent.Column(children)
    }

    override fun changes(scope: UiRenderScope): Flow<Unit> = hue.changes

    override fun live(scope: UiRenderScope, key: String): Flow<UiLiveUpdate>? {
        if (!key.startsWith(LIVE_PAIRING_PREFIX)) return null
        val session = hue.pairingSession(key.removePrefix(LIVE_PAIRING_PREFIX)) ?: return null
        return session.state.map { UiLiveUpdate.Replace(pairingComponent(scope, it.state, it.message ?: it.bridge?.name)) }
    }

    private fun pairingComponent(scope: UiRenderScope, state: HuePairingState, detail: String?): UiComponent = when (state) {
        HuePairingState.CONNECTING -> UiComponent.Progress(null, scope.t("hue.pairing.connecting"))
        HuePairingState.WAITING_FOR_BUTTON -> UiComponent.Progress(null, scope.t("hue.pairing.pressButton"))
        HuePairingState.PAIRED -> UiComponent.Badge(scope.t("hue.pairing.paired", "name" to (detail ?: "")), UiTone.SUCCESS, UiIcon(UiIconName.CHECK))
        HuePairingState.TIMEOUT -> UiComponent.Badge(scope.t("hue.pairing.timeout"), UiTone.WARNING, UiIcon(UiIconName.WARNING))
        HuePairingState.ERROR -> UiComponent.Badge(scope.t("hue.pairing.error", "reason" to (detail ?: "")), UiTone.ERROR, UiIcon(UiIconName.ERROR))
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult = when (actionId) {
        ACTION_DISCOVER -> {
            val found = hue.discover(force = true)
            UiInvokeResult(UiInvokeStatus.OK, if (found.isEmpty()) scope.t("hue.noneFound") else null, refresh = true)
        }
        ACTION_PAIR -> {
            val ip = values[FIELD_IP]?.text?.trim().orEmpty()
            if (ip.isEmpty()) UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(FIELD_IP to scope.t("hue.ip")))
            else {
                hue.beginPairing(scope.user.id, ip)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("hue.pairing.started"), refresh = true)
            }
        }
        ACTION_SAVE -> save(scope, values)
        ACTION_TEST -> {
            val bridgeId = bridgeId(values) ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("hue.error.noBridge"))
            val targets = selectedTargets(bridgeId, values)
            if (targets.isEmpty()) UiInvokeResult(UiInvokeStatus.ERROR, scope.t("hue.error.noTargets"))
            else {
                hue.test(scope.user.id, bridgeId, targets)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("hue.tested"))
            }
        }
        ACTION_REMOVE -> {
            val bridgeId = bridgeId(values)
            if (!scope.user.isAdmin || bridgeId == null) UiInvokeResult(UiInvokeStatus.ERROR, scope.t("hue.error.noBridge"))
            else {
                hue.removeBridge(bridgeId)
                UiInvokeResult(UiInvokeStatus.OK, scope.t("hue.bridge.removed"), refresh = true)
            }
        }
        else -> super.invoke(scope, actionId, values)
    }

    private suspend fun save(scope: UiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val bridgeId = bridgeId(values) ?: return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("hue.error.noBridge"))
        val enabled = values[FIELD_ENABLED]?.flag ?: false
        val targets = selectedTargets(bridgeId, values)
        if (enabled && targets.isEmpty()) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, scope.t("hue.error.noTargets"), fieldErrors = mapOf(FIELD_ENABLED to scope.t("hue.error.noTargets")))
        }
        val link = HueUserLink(
            bridgeId = bridgeId,
            enabled = enabled,
            targets = targets,
            intensity = enumOr(values[FIELD_INTENSITY]?.text, HueIntensity.MEDIUM),
            transitionMode = enumOr(values[FIELD_TRANSITION_MODE]?.text, HueTransitionMode.FIXED),
            transitionMs = values[FIELD_TRANSITION_MS]?.number?.toInt()?.coerceIn(0, 5000) ?: 400,
            onStop = enumOr(values[FIELD_ON_STOP]?.text, HueStopMode.KEEP),
            motion = enumOr(values[FIELD_MOTION]?.text, HueMotionMode.OFF),
        )
        hue.setLink(scope.user.id, link)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("hue.saved"), refresh = true)
    }

    private suspend fun selectedTargets(bridgeId: UUID, values: Map<String, UiValue>): List<HueTarget> {
        val available = runCatching { hue.listTargets(bridgeId) }.getOrDefault(emptyList())
        return available.filter { values[targetKey(it)]?.flag == true }
    }

    private fun bridgeId(values: Map<String, UiValue>): UUID? =
        values[FIELD_BRIDGE]?.text?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun targetKey(target: HueTarget) = "$FIELD_TARGET_PREFIX${target.type.name}:${target.id}"

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        const val ID = "core.hue"
        const val FORM_PAIR = "huePair"
        const val FORM_LINK_PREFIX = "hueLink:"
        const val LIVE_PAIRING_PREFIX = "pairing:"
        const val FIELD_IP = "ip"
        const val FIELD_BRIDGE = "bridge"
        const val FIELD_ENABLED = "enabled"
        const val FIELD_TARGET_PREFIX = "target:"
        const val FIELD_INTENSITY = "intensity"
        const val FIELD_TRANSITION_MODE = "transitionMode"
        const val FIELD_TRANSITION_MS = "transitionMs"
        const val FIELD_ON_STOP = "onStop"
        const val FIELD_MOTION = "motion"
        const val ACTION_DISCOVER = "discover"
        const val ACTION_PAIR = "pair"
        const val ACTION_SAVE = "save"
        const val ACTION_TEST = "test"
        const val ACTION_REMOVE = "remove"
    }
}
