package dev.dertyp.services.ui

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiHookOffer
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ISyncService
import dev.dertyp.services.UserService
import dev.dertyp.services.import.FavouriteImportQueueEntry
import dev.dertyp.services.import.ImportQueueEntry
import dev.dertyp.services.import.ImportRpcService
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterCapability
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.import.UrlImportQueueEntry
import dev.dertyp.services.sync.SyncService
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiAlign
import dev.dertyp.ui.UiButtonStyle
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiHookEvent
import dev.dertyp.ui.UiHookKind
import dev.dertyp.ui.UiInvokeResult
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiMenuItem
import dev.dertyp.ui.UiPortals
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiSpacing
import dev.dertyp.ui.UiTextKind
import dev.dertyp.ui.UiTextStyle
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class ImporterState(
    val importService: ImportService,
    val importerProxy: ImporterProxy,
) {
    private val authChangeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val authChanges: Flow<Unit> = authChangeFlow.asSharedFlow()

    fun enabledImporters(): List<IImporter> = importService.pluginManager.getAllImporters().filter { it.enabled }

    fun defaultImporter(): IImporter? = enabledImporters().firstOrNull { it.id == importerProxy.defaultService.id } ?: enabledImporters().firstOrNull()

    fun canLogin(importer: IImporter): Boolean = importer.capabilities.isEmpty() || ImporterCapability.LOGIN in importer.capabilities

    fun tidalAvailable(): Boolean = enabledImporters().any { it.canHandle("https://tidal.com/browse/track/1") }

    fun lineOk(line: String): Boolean = line.isNotBlank() && !line.startsWith("Let us check")

    fun currentLines(user: User?): List<String> {
        if (user != null && importService.currentImport(user) == null) return emptyList()
        return importService.currentImport?.logs?.filter(::lineOk)?.takeLast(MAX_LOG_LINES) ?: emptyList()
    }

    suspend fun login(scope: ServerUiRenderScope, importerId: String?): UiInvokeResult {
        val importer = (importerId?.let { id -> enabledImporters().firstOrNull { it.id == id } } ?: defaultImporter())
            ?: throw IllegalArgumentException(scope.t("importer.error.unknownBackend"))
        if (importer.tokenFileExists()) {
            authChangeFlow.tryEmit(Unit)
            return UiInvokeResult(UiInvokeStatus.OK, scope.t("importer.backends.authorized"), refresh = true)
        }

        val url = CompletableDeferred<String>()
        val job = ApplicationScope.scope.launch {
            try {
                importer.login(
                    aliveCheck = { isActive },
                    onLiveOutput = { log -> importer.extractLoginUrl(log)?.let { url.complete(it) } },
                )
            } finally {
                url.completeExceptionally(IllegalStateException("Login finished without a URL"))
                authChangeFlow.tryEmit(Unit)
            }
        }
        val loginUrl = withTimeoutOrNull(30.seconds) { runCatching { url.await() }.getOrNull() }
        if (loginUrl == null) {
            job.cancel()
            return UiInvokeResult(UiInvokeStatus.ERROR, scope.t("importer.error.loginUrl"))
        }
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("importer.backends.loginHint"), refresh = true, next = UiAction.OpenUrl(loginUrl))
    }

    suspend fun syncFavourites(scope: ServerUiRenderScope): UiInvokeResult {
        val call = scope.call ?: return UiInvokeResult(UiInvokeStatus.ERROR, NO_CALL)
        val sync = SyncService.getInstance(scope.account, call.application.environment, ISyncService.SyncServiceType.tidal)
        if (sync.getAccessToken() == null) {
            return UiInvokeResult(UiInvokeStatus.OK, scope.t("importer.favorites.authorize"), next = UiAction.OpenUrl(sync.buildAuthUrl(call)))
        }
        return try {
            importService.syncFavourites(call, ignoreService = true).invokeOnCompletion { authChangeFlow.tryEmit(Unit) }
            authChangeFlow.tryEmit(Unit)
            UiInvokeResult(UiInvokeStatus.OK, scope.t("importer.favorites.started"), refresh = true)
        } catch (e: IllegalStateException) {
            UiInvokeResult(UiInvokeStatus.ERROR, e.message)
        }
    }

    companion object {
        const val MAX_LOG_LINES = 500
        const val NO_CALL = "No request context"
    }
}

class ImporterLibraryEntryContribution : UiContribution(
    id = "core.importer.entry",
    kind = UiContributionKind.SLOT,
    slot = UiSlots.LIBRARY,
    titleKey = "importer.title",
    descriptionKey = "importer.subtitle",
    icon = "download",
    order = 100,
    access = UiAccess(capabilities = setOf(UserCapability.IMPORT)),
) {
    override suspend fun render(scope: UiRenderScope): UiComponent = UiComponent.Tile(
        title = scope.t("importer.title"),
        subtitle = scope.t("importer.subtitle"),
        icon = icon,
        action = UiAction.OpenPage(ImporterPageContribution.ID),
    )
}

class ImporterPageContribution(
    private val state: ImporterState,
    private val uiService: UiService,
) : UiContribution(
    id = ID,
    kind = UiContributionKind.PAGE,
    titleKey = "importer.title",
    descriptionKey = "importer.subtitle",
    icon = "download",
    access = UiAccess(capabilities = setOf(UserCapability.IMPORT)),
    hooks = setOf(UiHookKind.SHARE_URL, UiHookKind.SHARE_TEXT),
) {
    companion object {
        const val ID = "core.importer"
        const val PARAM_INPUT = "input"
        const val PARAM_IMPORTER = "importer"
        const val LIVE_LOG = "log"
    }

    private val importService get() = state.importService

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val server = scope as? ServerUiRenderScope
        val children = mutableListOf<UiComponent>()

        val default = state.defaultImporter()
        if (default != null && !default.tokenFileExists()) {
            children += UiComponent.Card(
                title = scope.t("importer.login.title"),
                subtitle = scope.t("importer.login.message"),
                icon = "login",
                tone = UiTone.WARNING,
                children = emptyList(),
                actions = listOf(
                    UiComponent.Button(
                        label = scope.t("importer.login.action"),
                        action = UiAction.Invoke(id, "login", params = mapOf(PARAM_IMPORTER to UiValue.of(default.id))),
                        style = UiButtonStyle.PRIMARY,
                        icon = "login",
                    ),
                ),
            )
        }

        children += UiComponent.Column(
            spacing = UiSpacing.SMALL,
            children = listOf(
                UiComponent.Text(scope.t("importer.input.title"), UiTextStyle.TITLE),
                UiComponent.Text(scope.t("importer.input.explanation"), UiTextStyle.CAPTION, UiTone.MUTED),
                UiComponent.Form(
                    id = "import",
                    submit = UiAction.Invoke(id, "import", formId = "import"),
                    submitLabel = scope.t("importer.import"),
                    children = listOf(
                        UiComponent.TextField(
                            key = PARAM_INPUT,
                            label = scope.t("importer.input.title"),
                            value = scope.context.params[PARAM_INPUT],
                            multiline = true,
                            required = true,
                            kind = UiTextKind.MULTILINE_URLS,
                            toolbar = listOf(
                                UiComponent.Button(scope.t("importer.done"), UiAction.DismissKeyboard, UiButtonStyle.TEXT, icon = "check"),
                            ),
                        ),
                    ),
                    actions = listOf(UiComponent.Native(UiPortals.BARCODE_SCANNER, params = mapOf("target" to PARAM_INPUT))),
                ),
            ),
        )

        children += UiComponent.Column(
            spacing = UiSpacing.SMALL,
            children = listOf(
                UiComponent.Text(scope.t("importer.logs"), UiTextStyle.TITLE),
                UiComponent.Live(LIVE_LOG, UiComponent.Log(state.currentLines(server?.account), ImporterState.MAX_LOG_LINES)),
            ),
        )

        return UiComponent.Column(children = children, spacing = UiSpacing.LARGE)
    }

    override suspend fun toolbar(scope: UiRenderScope): List<UiComponent> {
        val server = scope as? ServerUiRenderScope
        val items = mutableListOf<UiComponent>(
            UiComponent.Button(scope.t("importer.queue.title"), UiAction.OpenPage(ImporterQueuePageContribution.ID, modal = true), UiButtonStyle.TEXT, icon = "queue"),
        )
        if (state.tidalAvailable()) {
            val available = server?.call?.let { importService.syncFavouritesAvailable(it) } ?: true
            if (available) {
                items += UiComponent.Button(
                    label = scope.t("importer.favorites.title"),
                    action = UiAction.Invoke(id, "syncFavourites", confirmText = scope.t("importer.favorites.confirm")),
                    style = UiButtonStyle.TEXT,
                    icon = "sync",
                )
            }
        }
        val manageable = state.enabledImporters().any(state::canLogin) ||
            (server != null && uiService.list(server.account, server.client, slot = UiSlots.IMPORTER).isNotEmpty())
        if (manageable) {
            items += UiComponent.Button(scope.t("importer.settings.title"), UiAction.OpenPage(ImporterSettingsPageContribution.ID, modal = true), UiButtonStyle.TEXT, icon = "settings")
        }
        return items
    }

    override fun changes(scope: UiRenderScope): Flow<Unit> = merge(importService.queueChanges, state.authChanges)

    override fun live(scope: UiRenderScope, key: String): Flow<UiLiveUpdate>? {
        if (key != LIVE_LOG) return null
        val account = (scope as? ServerUiRenderScope)?.account
        return merge(
            importService.log.filterNotNull().filter(state::lineOk).map { UiLiveUpdate.AppendLines(listOf(it)) },
            importService.queueChanges.map { UiLiveUpdate.Replace(UiComponent.Log(state.currentLines(account), ImporterState.MAX_LOG_LINES)) },
        )
    }

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        val server = scope as? ServerUiRenderScope ?: return UiInvokeResult(UiInvokeStatus.ERROR, ImporterState.NO_CALL)
        return when (actionId) {
            "import" -> import(server, values)
            "login" -> state.login(server, values[PARAM_IMPORTER]?.text)
            "syncFavourites" -> state.syncFavourites(server)
            else -> super.invoke(scope, actionId, values)
        }
    }

    private suspend fun import(scope: ServerUiRenderScope, values: Map<String, UiValue>): UiInvokeResult {
        val lines = values[PARAM_INPUT]?.text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, fieldErrors = mapOf(PARAM_INPUT to scope.t("importer.error.empty")))
        }
        val call = scope.call ?: return UiInvokeResult(UiInvokeStatus.ERROR, ImporterState.NO_CALL)
        ImportRpcService(scope.account, call, importService, state.importerProxy).importUrls(lines)
        return UiInvokeResult(UiInvokeStatus.OK, scope.t("importer.queued", "count" to lines.size.toString()), refresh = true)
    }

    override suspend fun onHook(scope: UiRenderScope, event: UiHookEvent): UiHookOffer? {
        val text = when (event) {
            is UiHookEvent.ShareUrl -> event.url
            is UiHookEvent.ShareText -> event.text
        }.trim()
        if (text.isEmpty()) return null
        val url = text.lines().firstOrNull { it.contains("://") }?.trim()
        val resolved = url?.let { state.importerProxy.resolveImporter(it) }
        return if (resolved != null) {
            UiHookOffer(
                titleKey = "importer.hook.import",
                descriptionKey = "importer.hook.importDescription",
                icon = "download",
                action = UiAction.OpenPage(id, params = mapOf(PARAM_INPUT to text)),
            )
        } else {
            UiHookOffer(
                titleKey = "importer.hook.search",
                descriptionKey = "importer.hook.searchDescription",
                icon = "search",
                action = UiAction.OpenNative(UiPortals.EXTERNAL_SEARCH, params = mapOf("query" to text)),
            )
        }
    }
}

class ImporterSettingsPageContribution(
    private val state: ImporterState,
    private val uiService: UiService,
) : UiContribution(
    id = ID,
    kind = UiContributionKind.PAGE,
    titleKey = "importer.settings.title",
    icon = "settings",
    access = UiAccess(capabilities = setOf(UserCapability.IMPORT)),
) {
    companion object {
        const val ID = "core.importer.settings"
    }

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val server = scope as? ServerUiRenderScope
        val importers = state.enabledImporters()
        val children = mutableListOf<UiComponent>()

        children += UiComponent.Section(
            title = scope.t("importer.backends.title"),
            children = if (importers.isEmpty()) listOf(UiComponent.Text(scope.t("importer.backends.none"), tone = UiTone.WARNING))
            else importers.map { importer ->
                val authorized = importer.tokenFileExists()
                val statusText = if (authorized) scope.t("importer.backends.authorized") else scope.t("importer.backends.loginRequired")
                UiComponent.Row(
                    weights = listOf(1.0, 0.0, 0.0),
                    children = listOfNotNull(
                        UiComponent.ListItem(title = importer.name, subtitle = statusText, icon = "plug"),
                        UiComponent.Badge(statusText, if (authorized) UiTone.SUCCESS else UiTone.WARNING),
                        if (!authorized && state.canLogin(importer)) UiComponent.Button(
                            label = scope.t("importer.login.action"),
                            action = UiAction.Invoke(id, "login", params = mapOf(ImporterPageContribution.PARAM_IMPORTER to UiValue.of(importer.id))),
                            style = UiButtonStyle.PRIMARY,
                            icon = "login",
                        ) else null,
                    ),
                )
            },
        )

        if (server != null) {
            val slot = uiService.renderSlot(server.account, server.client, UiSlots.IMPORTER, scope.context, server.call)
            children += slot.items.map { item ->
                UiComponent.Section(title = item.title ?: item.contributionId, collapsible = true, collapsed = true, children = listOf(item.root))
            }
        }

        return UiComponent.Column(children = children, spacing = UiSpacing.LARGE)
    }

    override fun changes(scope: UiRenderScope): Flow<Unit> = state.authChanges

    override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
        val server = scope as? ServerUiRenderScope ?: return UiInvokeResult(UiInvokeStatus.ERROR, ImporterState.NO_CALL)
        return when (actionId) {
            "login" -> state.login(server, values[ImporterPageContribution.PARAM_IMPORTER]?.text)
            else -> super.invoke(scope, actionId, values)
        }
    }
}

class ImporterQueuePageContribution(
    private val state: ImporterState,
    private val userService: UserService,
) : UiContribution(
    id = ID,
    kind = UiContributionKind.PAGE,
    titleKey = "importer.queue.title",
    icon = "queue",
    access = UiAccess(capabilities = setOf(UserCapability.IMPORT)),
) {
    companion object {
        const val ID = "core.importer.queue"
    }

    private val importService get() = state.importService

    private fun urlCount(entry: ImportQueueEntry?): Int = (entry as? UrlImportQueueEntry)?.urls?.size ?: 0

    override suspend fun render(scope: UiRenderScope): UiComponent {
        val account = (scope as? ServerUiRenderScope)?.account
        val current = account?.let { importService.currentImport(it) }
        val queue = account?.let { importService.importQueue(it) } ?: emptyList()

        if (current == null && queue.isEmpty()) {
            return UiComponent.Column(
                align = UiAlign.CENTER,
                children = listOf(
                    UiComponent.Icon("queue", UiTone.MUTED),
                    UiComponent.Text(scope.t("importer.queue.empty.title"), UiTextStyle.TITLE),
                    UiComponent.Text(scope.t("importer.queue.empty.description"), UiTextStyle.CAPTION, UiTone.MUTED),
                ),
            )
        }

        val users = mutableMapOf<java.util.UUID, User?>()
        suspend fun userOf(entry: ImportQueueEntry): User? = entry.byUser?.let { id -> users.getOrPut(id) { userService.findUserById(id) } }

        val children = mutableListOf<UiComponent>()
        val total = urlCount(current) + queue.sumOf { urlCount(it) }
        if (total > 0) {
            children += UiComponent.Card(
                children = listOf(
                    UiComponent.Row(
                        children = listOf(
                            UiComponent.Stat(scope.t("importer.queue.totalUrls"), total.toString()),
                            UiComponent.Divider,
                            UiComponent.Stat(scope.t("importer.queue.importing"), urlCount(current).toString()),
                        ),
                    ),
                ),
            )
        }
        if (current != null) {
            children += UiComponent.Text(scope.t("importer.queue.current"), UiTextStyle.SUBTITLE, UiTone.MUTED)
            children += entryCard(current, userOf(current), scope)
        }
        if (queue.isNotEmpty()) {
            children += UiComponent.Text(scope.t("importer.queue.pending"), UiTextStyle.SUBTITLE, UiTone.MUTED)
            queue.forEach { children += entryCard(it, userOf(it), scope) }
        }
        return UiComponent.Column(children = children, spacing = UiSpacing.LARGE)
    }

    private fun entryCard(entry: ImportQueueEntry, user: User?, scope: UiRenderScope): UiComponent {
        val header: UiComponent
        val body: UiComponent
        when (entry) {
            is UrlImportQueueEntry -> {
                header = UiComponent.Row(children = listOf(UiComponent.Icon("link", UiTone.PRIMARY), UiComponent.Text(scope.t("importer.queue.type.urls"), UiTextStyle.SUBTITLE)))
                body = UiComponent.ListItem(
                    title = entry.urls.joinToString(", "),
                    action = UiAction.OpenMenu(
                        title = scope.t("importer.queue.type.urls"),
                        items = entry.urls.map { UiMenuItem(it, UiAction.OpenUrl(it), icon = "link") },
                    ),
                )
            }

            is FavouriteImportQueueEntry -> {
                header = UiComponent.Row(children = listOf(UiComponent.Icon("heart", UiTone.PRIMARY), UiComponent.Text(scope.t("importer.queue.type.favorites"), UiTextStyle.SUBTITLE)))
                body = UiComponent.Text(entry.favoriteType.name, UiTextStyle.CAPTION, UiTone.MUTED)
            }
        }
        val badges = listOfNotNull(
            entry.type?.let { UiComponent.Badge(it.value.uppercase(), UiTone.PRIMARY) },
            user?.let { UiComponent.Badge(it.displayName ?: it.username, UiTone.MUTED, icon = "user") },
        )
        return UiComponent.Card(children = listOfNotNull(header, body, if (badges.isNotEmpty()) UiComponent.Row(children = badges) else null))
    }

    override fun changes(scope: UiRenderScope): Flow<Unit> = importService.queueChanges
}
