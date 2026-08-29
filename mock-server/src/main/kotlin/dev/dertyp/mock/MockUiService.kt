package dev.dertyp.mock

import dev.dertyp.data.UserCapability
import dev.dertyp.services.IUiService
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiAlign
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiCardSize
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiIcon
import dev.dertyp.ui.UiIconName
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiContributionInfo
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHomeCard
import dev.dertyp.ui.UiHomeLayout
import dev.dertyp.ui.UiHookEvent
import dev.dertyp.ui.UiHookHandler
import dev.dertyp.ui.UiHookKind
import dev.dertyp.ui.UiInvokePayload
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiMenuItem
import dev.dertyp.ui.UiPortals
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSlotRender
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiSpacing
import dev.dertyp.ui.UiTextKind
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MockUiService : IUiService {
    private val revision = AtomicLong()
    private val pinned = MutableStateFlow(listOf("core.importer.card"))
    private val queue = MutableStateFlow(listOf("https://tidal.com/browse/album/1234"))
    private val logLines = listOf("Fetching metadata…", "Resolving https://tidal.com/browse/track/98765", "Downloading 3/12", "Tagging track 3", "Downloading 4/12")

    private val contributions = listOf(
        UiContributionInfo("core.importer.entry", "server", UiContributionKind.SLOT, UiSlots.LIBRARY, "Importer", "Import music from streaming services", UiIcon(UiIconName.IMPORT), 100, true, requiredCapabilities = listOf(UserCapability.IMPORT)),
        UiContributionInfo("core.importer", "server", UiContributionKind.PAGE, null, "Importer", "Import music from streaming services", UiIcon(UiIconName.IMPORT), 0, true, requiredCapabilities = listOf(UserCapability.IMPORT), hooks = listOf(UiHookKind.SHARE_URL, UiHookKind.SHARE_TEXT)),
        UiContributionInfo("core.importer.settings", "server", UiContributionKind.PAGE, null, "Importer settings", null, UiIcon(UiIconName.SETTINGS), 0, true, requiredCapabilities = listOf(UserCapability.IMPORT)),
        UiContributionInfo("core.importer.queue", "server", UiContributionKind.PAGE, null, "Queue", null, UiIcon(UiIconName.QUEUE), 0, true, requiredCapabilities = listOf(UserCapability.IMPORT)),
        UiContributionInfo("core.importer.card", "server", UiContributionKind.HOME_CARD, null, "Importer", "Import music from streaming services", UiIcon(UiIconName.IMPORT), 50, true, UiCardSize.MEDIUM, requiredCapabilities = listOf(UserCapability.IMPORT)),
        UiContributionInfo("gamdl.credentials", "gamdl", UiContributionKind.SLOT, UiSlots.IMPORTER, "Apple Music credentials", null, UiIcon(UiIconName.KEY), 50, true, requiresAdmin = true),
    )

    private fun render(id: String, root: UiComponent) = UiRender(
        id,
        root,
        contributions.first { it.id == id }.title,
        revision = revision.incrementAndGet(),
        toolbar = if (id == "core.importer") listOf(
            UiComponent.Button("Queue", UiAction.OpenPage("core.importer.queue", modal = true), UiButtonStyle.TEXT, UiIcon(UiIconName.QUEUE)),
            UiComponent.Button("Sync Favorites", UiAction.Invoke("core.importer", "syncFavourites", confirmText = "Are you sure you want to synchronize your favorites?"), UiButtonStyle.TEXT, UiIcon(UiIconName.SYNC)),
            UiComponent.Button("Importer settings", UiAction.OpenPage("core.importer.settings", modal = true), UiButtonStyle.TEXT, UiIcon(UiIconName.SETTINGS)),
        ) else emptyList(),
    )

    private fun tree(id: String, context: UiContext): UiComponent = when (id) {
        "core.importer.entry" -> UiComponent.Tile("Importer", "Import music from streaming services", UiIcon(UiIconName.IMPORT), UiAction.OpenPage("core.importer"))
        "core.importer" -> importer(context)
        "core.importer.settings" -> importerSettings()
        "core.importer.queue" -> importerQueue()
        "core.importer.card" -> UiComponent.Card(
            title = "Importer",
            icon = UiIcon(UiIconName.IMPORT),
            children = listOf(
                UiComponent.Row(children = listOf(UiComponent.Stat("Pending", queue.value.size.toString(), icon = UiIcon(UiIconName.QUEUE)), UiComponent.Stat("Importing", "1", icon = UiIcon(UiIconName.IMPORT)))),
                UiComponent.ListItem("https://tidal.com/browse/track/98765", "Currently Importing", UiIcon(UiIconName.DOWNLOAD)),
                UiComponent.Progress(),
            ),
            actions = listOf(
                UiComponent.Button("Open importer", UiAction.OpenPage("core.importer"), UiButtonStyle.TEXT, UiIcon(UiIconName.IMPORT)),
                UiComponent.Button("Queue", UiAction.OpenPage("core.importer.queue", modal = true), UiButtonStyle.TEXT, UiIcon(UiIconName.QUEUE)),
            ),
        )

        "gamdl.credentials" -> UiComponent.Card(
            title = "Apple Music credentials",
            icon = UiIcon(UiIconName.KEY),
            children = listOf(
                UiComponent.Badge("Not configured", UiTone.WARNING),
                UiComponent.Form(
                    id = "gamdl",
                    submit = UiAction.Invoke(id, "save", formId = "gamdl"),
                    submitLabel = "Save credentials",
                    children = listOf(
                        UiComponent.TextField("cookiesTxt", "cookies.txt", multiline = true, secret = true, required = true),
                        UiComponent.TextField("wvdBase64", "Widevine device (.wvd, base64)", secret = true),
                    ),
                ),
            ),
        )

        else -> throw IllegalArgumentException("Unknown UI contribution: $id")
    }

    private fun importer(context: UiContext): UiComponent = UiComponent.Column(
        spacing = UiSpacing.LARGE,
        children = listOf(
            UiComponent.Column(
                spacing = UiSpacing.SMALL,
                children = listOf(
                    UiComponent.Text("Import URLs", UiTextStyle.TITLE),
                    UiComponent.Text("Enter one URL per line to import them.", UiTextStyle.CAPTION, UiTone.MUTED),
                    UiComponent.Form(
                        id = "import",
                        submit = UiAction.Invoke("core.importer", "import", formId = "import"),
                        submitLabel = "Import",
                        children = listOf(
                            UiComponent.TextField(
                                "input", "Import URLs", value = context.params["input"], multiline = true, required = true, kind = UiTextKind.MULTILINE_URLS,
                                toolbar = listOf(UiComponent.Button("Done", UiAction.DismissKeyboard, UiButtonStyle.TEXT, UiIcon(UiIconName.CHECK))),
                            ),
                        ),
                        actions = listOf(UiComponent.Native(UiPortals.BARCODE_SCANNER, mapOf("target" to "input"))),
                    ),
                ),
            ),
            UiComponent.Column(
                spacing = UiSpacing.SMALL,
                children = listOf(
                    UiComponent.Text("Logs", UiTextStyle.TITLE),
                    UiComponent.Live("log", UiComponent.Log(logLines, 500)),
                ),
            ),
        ),
    )

    private fun importerSettings(): UiComponent = UiComponent.Column(
        spacing = UiSpacing.LARGE,
        children = listOf(
            UiComponent.Section(
                title = "Importers",
                children = listOf(
                    UiComponent.Row(weights = listOf(1.0, 0.0), children = listOf(UiComponent.ListItem("Tidal", "Authorized", UiIcon(UiIconName.PLUG)), UiComponent.Badge("Authorized", UiTone.SUCCESS))),
                    UiComponent.Row(
                        weights = listOf(1.0, 0.0, 0.0),
                        children = listOf(
                            UiComponent.ListItem("YouTube", "Login required", UiIcon(UiIconName.PLUG)),
                            UiComponent.Badge("Login required", UiTone.WARNING),
                            UiComponent.Button("Login", UiAction.Invoke("core.importer.settings", "login", mapOf("importer" to UiValue.of("youtube"))), UiButtonStyle.PRIMARY, UiIcon(UiIconName.LOGIN)),
                        ),
                    ),
                ),
            ),
            UiComponent.Section(title = "Apple Music credentials", collapsible = true, collapsed = true, children = listOf(tree("gamdl.credentials", UiContext()))),
        ),
    )

    private fun importerQueue(): UiComponent {
        val current = listOf("https://tidal.com/browse/track/98765", "https://tidal.com/browse/track/98766")
        val pending = queue.value
        return UiComponent.Column(
            spacing = UiSpacing.LARGE,
            children = listOf(
                UiComponent.Card(children = listOf(UiComponent.Row(children = listOf(UiComponent.Stat("Total URLs", (current.size + pending.size).toString()), UiComponent.Divider, UiComponent.Stat("Importing", current.size.toString()))))),
                UiComponent.Text("Currently Importing", UiTextStyle.SUBTITLE, UiTone.MUTED),
                queueEntry(current),
                UiComponent.Text("Pending Imports", UiTextStyle.SUBTITLE, UiTone.MUTED),
            ) + pending.map { queueEntry(listOf(it)) },
        )
    }

    private fun queueEntry(urls: List<String>) = UiComponent.Card(
        children = listOf(
            UiComponent.Row(children = listOf(UiComponent.Icon(UiIcon(UiIconName.LINK), UiTone.PRIMARY), UiComponent.Text("URLs", UiTextStyle.SUBTITLE))),
            UiComponent.ListItem(urls.joinToString(", "), action = UiAction.OpenMenu(urls.map { UiMenuItem(it, UiAction.OpenUrl(it), UiIcon(UiIconName.LINK)) }, title = "URLs")),
            UiComponent.Row(children = listOf(UiComponent.Badge("TRACK", UiTone.PRIMARY), UiComponent.Badge("mock", UiTone.MUTED, UiIcon(UiIconName.USER)))),
        ),
    )

    override suspend fun listContributions(kind: UiContributionKind?, slot: String?): List<UiContributionInfo> =
        contributions.filter { (kind == null || it.kind == kind) && (slot == null || it.slot == slot) }

    override suspend fun renderSlot(slot: String, context: UiContext): UiSlotRender =
        UiSlotRender(slot, contributions.filter { it.slot == slot }.map { render(it.id, tree(it.id, context)) })

    override suspend fun render(contributionId: String, context: UiContext): UiRender = render(contributionId, tree(contributionId, context))

    override fun subscribe(contributionId: String, entityId: UUID?): Flow<UiRender> = flow {
        while (true) {
            emit(render(contributionId, UiContext(entityId = entityId)))
            delay(2.seconds)
        }
    }

    override fun subscribeLive(contributionId: String, key: String, entityId: UUID?): Flow<UiLiveUpdate> = flow {
        require(contributionId == "core.importer" && key == "log") { "Unknown live key '$key' for UI contribution $contributionId" }
        var index = 0
        while (true) {
            index++
            emit(UiLiveUpdate.AppendLines(listOf("Downloading ${index % 12 + 1}/12")))
            delay(500.milliseconds)
        }
    }

    override suspend fun invoke(contributionId: String, actionId: String, payload: UiInvokePayload): UiInvokeResult {
        val values = payload.values
        return when (contributionId to actionId) {
            "core.importer" to "import" -> {
                val lines = values["input"]?.text.orEmpty().lines().filter { it.isNotBlank() }
                if (lines.isEmpty()) UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf("input" to "Enter at least one URL or code."))
                else {
                    queue.value = queue.value + lines
                    UiInvokeResult(UiInvokeStatus.OK, "${lines.size} items queued", refresh = true)
                }
            }

            "core.importer" to "login", "core.importer.settings" to "login" -> UiInvokeResult(UiInvokeStatus.OK, "Complete the login in your browser, then come back.", refresh = true, next = UiAction.OpenUrl("https://example.org/login"))
            "core.importer" to "syncFavourites" -> UiInvokeResult(UiInvokeStatus.OK, "Favorites sync started", refresh = true)
            "gamdl.credentials" to "save" -> UiInvokeResult(UiInvokeStatus.OK, "Credentials saved", refresh = true)
            else -> UiInvokeResult(UiInvokeStatus.ERROR, "Unknown action: $actionId")
        }
    }

    override suspend fun dispatchHook(event: UiHookEvent): List<UiHookHandler> {
        val text = when (event) {
            is UiHookEvent.ShareUrl -> event.url
            is UiHookEvent.ShareText -> event.text
        }
        val handlers = mutableListOf<UiHookHandler>()
        if (text.contains("tidal.com") || text.contains("music.apple.com")) {
            handlers += UiHookHandler("core.importer", "server", "Import", "Add this link to the import queue", UiIcon(UiIconName.IMPORT), UiAction.OpenPage("core.importer", mapOf("input" to text)), confirmText = "Open the importer with this link?")
        }
        if (text.contains("music.apple.com")) {
            handlers += UiHookHandler("gamdl.credentials", "gamdl", "Import with gamdl", null, UiIcon(UiIconName.KEY), UiAction.OpenPage("core.importer", mapOf("input" to text)))
        }
        if (handlers.isEmpty() && !text.contains("://")) {
            handlers += UiHookHandler("core.importer", "server", "Search catalog", "Search the streaming catalog for this text", UiIcon(UiIconName.SEARCH), UiAction.OpenNative(UiPortals.EXTERNAL_SEARCH, mapOf("query" to text)))
        }
        return handlers
    }

    private fun layout(): UiHomeLayout {
        val cards = contributions.filter { it.kind == UiContributionKind.HOME_CARD }
        val pinnedIds = pinned.value
        val ordered = cards.sortedWith(compareBy({ it.id !in pinnedIds }, { pinnedIds.indexOf(it.id) }))
        return UiHomeLayout(ordered.mapIndexed { index, info -> UiHomeCard(info.id, info.id in pinnedIds, index, info.cardSize) })
    }

    override suspend fun getHomeCards(): UiHomeLayout = layout()

    override suspend fun setHomeCardPinned(contributionId: String, pinned: Boolean): UiHomeLayout {
        this.pinned.value = if (pinned) (this.pinned.value + contributionId).distinct() else this.pinned.value - contributionId
        return layout()
    }

    override suspend fun setHomeCardOrder(contributionIds: List<String>): UiHomeLayout {
        pinned.value = contributionIds
        return layout()
    }

    override fun getHomeCardsFlow(): Flow<UiHomeLayout> = flow {
        pinned.collect { emit(layout()) }
    }
}
