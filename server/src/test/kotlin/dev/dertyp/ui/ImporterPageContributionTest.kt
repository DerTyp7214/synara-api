package dev.dertyp.ui

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.UserService
import dev.dertyp.services.import.FavouriteImportQueueEntry
import dev.dertyp.services.import.FinishedImportQueueEntry
import dev.dertyp.services.import.ImportBackend
import dev.dertyp.services.import.ImportFavType
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterCapability
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.import.ProcessExecutionResult
import dev.dertyp.services.import.Type
import dev.dertyp.services.import.UrlImportQueueEntry
import dev.dertyp.plugins.JobInfo
import dev.dertyp.plugins.JobStatus
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.jobs.JobService
import dev.dertyp.services.ui.ImporterHomeCardContribution
import dev.dertyp.services.ui.ImporterLibraryEntryContribution
import dev.dertyp.services.ui.ImporterPageContribution
import dev.dertyp.services.ui.ImporterQueuePageContribution
import dev.dertyp.services.ui.ImporterSettingsPageContribution
import dev.dertyp.services.ui.ImporterState
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.ServerUiRenderScope
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.ui.UiService
import dev.dertyp.services.ui.UserHomeCardService
import dev.dertyp.ui.UiIntakeCodeKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ImporterPageContributionTest {
    private val account = User(UUID.randomUUID(), "imp", displayName = "Importer Ann", passwordHash = "", capabilities = listOf(UserCapability.IMPORT))
    private val client = ClientInfo(ApiVersion.CURRENT, UiSchemaVersion.CURRENT, "en")

    private fun importer(id: String, name: String, authorized: Boolean, capabilities: Set<ImporterCapability> = emptySet(), tidal: Boolean = false): IImporter =
        mockk(relaxed = true) {
            every { this@mockk.id } returns id
            every { this@mockk.name } returns name
            every { enabled } returns true
            every { tokenFileExists() } returns authorized
            every { this@mockk.capabilities } returns capabilities
            every { canHandle(any()) } returns tidal
        }

    private val tidal = importer("tidal", "Tidal", authorized = true, tidal = true)
    private val gamdl = importer("gamdl", "gamdl (Apple Music)", authorized = false, capabilities = setOf(ImporterCapability.IMPORT_SONG, ImporterCapability.CREDENTIALS))
    private val youtube = importer("youtube", "YouTube", authorized = false)

    private val logFlow = MutableSharedFlow<String?>()
    private val queueChanges = MutableSharedFlow<Unit>()
    private val currentEntry = UrlImportQueueEntry(mutableListOf("https://tidal.com/browse/track/1", "https://tidal.com/browse/track/2"), byUser = account.id, type = Type.SONG)
    private val currentFinished = FinishedImportQueueEntry(currentEntry, ProcessExecutionResult.EMPTY, mutableListOf("Let us check the token", "", "Fetching metadata…", "Downloading 1/2"))

    private val pendingEntries = listOf(
        FavouriteImportQueueEntry(ImportFavType.tracks, byUser = account.id),
        UrlImportQueueEntry(mutableListOf("https://tidal.com/browse/album/9"), byUser = null, type = Type.ALBUM),
    )
    private fun job(entry: dev.dertyp.services.import.ImportQueueEntry, status: JobStatus) = ImportService.ImportJob(
        JobInfo(UUID.randomUUID(), ImportService.JOB_KIND, "server", "t", "s", entry.byUser, status, if (status == JobStatus.RUNNING) 0.5 else null, null, 0, null, null),
        entry,
    )
    private val pluginManager = mockk<PluginManager> { every { getAllImporters() } returns listOf(tidal, gamdl, youtube) }
    private val importService = mockk<ImportService>(relaxed = true) {
        every { this@mockk.pluginManager } returns this@ImporterPageContributionTest.pluginManager
        every { log } returns logFlow
        every { queueChanges } returns this@ImporterPageContributionTest.queueChanges
        every { currentImport } returns currentFinished
        every { currentImport(any()) } returns currentEntry
        coEvery { importQueue(any()) } returns pendingEntries
        every { importJobs(any()) } answers { listOf(job(currentEntry, JobStatus.RUNNING)) + pendingEntries.map { job(it, JobStatus.PENDING) } }
    }
    private val importerProxy = mockk<ImporterProxy> {
        every { defaultService } returns ImportBackend("tidal")
        coEvery { resolveImporter(any(), any()) } answers { if (firstArg<String>().contains("tidal.com")) tidal to firstArg() else null }
    }
    private val userService = mockk<UserService> { coEvery { findUserById(account.id) } returns account }

    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val uiService = UiService(registry, translations, PluginSettingsService(), UserHomeCardService(), IntakeService(translations))
    private val intakeService = mockk<IntakeService>()
    private val jobService = JobService()
    private val state = ImporterState(importService, importerProxy, intakeService, jobService)
    private val page = ImporterPageContribution(state, uiService)
    private val settingsPage = ImporterSettingsPageContribution(state, uiService)
    private val queuePage = ImporterQueuePageContribution(state, userService)

    private val settings = mockk<PluginSettings>(relaxed = true)

    private fun scope(params: Map<String, String> = emptyMap(), user: User = account) = ServerUiRenderScope(
        user = UserInfo.fromUser(user),
        context = UiContext(params = params),
        i18n = translations.translator(UiRegistry.SERVER_SOURCE, "en"),
        settings = settings,
        clientSchemaVersion = UiSchemaVersion.CURRENT,
        account = user,
        client = client,
        call = null,
    )

    private fun UiComponent.flatten(): List<UiComponent> = listOf(this) + when (this) {
        is UiComponent.Column -> children.flatMap { it.flatten() }
        is UiComponent.Row -> children.flatMap { it.flatten() }
        is UiComponent.Grid -> children.flatMap { it.flatten() }
        is UiComponent.Card -> (children + actions).flatMap { it.flatten() }
        is UiComponent.Section -> children.flatMap { it.flatten() }
        is UiComponent.Form -> (children + actions).flatMap { it.flatten() }
        is UiComponent.Live -> child.flatten()
        is UiComponent.TextField -> toolbar.flatMap { it.flatten() }
        else -> emptyList()
    }

    @Test
    fun `main page is the ios layout - import block and logs block only`() = runBlocking {
        val root = page.render(scope(mapOf("input" to "https://x"))) as UiComponent.Column
        assertEquals(2, root.children.size)

        val importBlock = root.children[0] as UiComponent.Column
        assertEquals("Import URLs", (importBlock.children[0] as UiComponent.Text).text)
        assertEquals("Enter one URL per line to import them.", (importBlock.children[1] as UiComponent.Text).text)
        val form = importBlock.children[2] as UiComponent.Form
        assertEquals("Import", form.submitLabel)
        val field = form.children.single() as UiComponent.TextField
        assertEquals("https://x", field.value)
        assertTrue(field.multiline && field.required)
        assertEquals(UiTextKind.MULTILINE_URLS, field.kind)
        val done = field.toolbar.single() as UiComponent.Button
        assertEquals(UiAction.DismissKeyboard, done.action)
        assertEquals(UiPortals.BARCODE_SCANNER, (form.actions.single() as UiComponent.Native).name)
        assertEquals(mapOf("target" to "input"), (form.actions.single() as UiComponent.Native).params)

        val logsBlock = root.children[1] as UiComponent.Column
        assertEquals("Logs", (logsBlock.children[0] as UiComponent.Text).text)
        val live = logsBlock.children[1] as UiComponent.Live
        assertEquals("log", live.key)
        val log = live.child as UiComponent.Log
        assertEquals(500, log.maxLines)
        assertEquals(listOf("Fetching metadata…", "Downloading 1/2"), log.lines)

        val all = root.flatten()
        assertTrue(all.none { it is UiComponent.Select })
        assertTrue(all.none { it is UiComponent.Section })
        assertTrue(all.none { it is UiComponent.Card })
    }

    @Test
    fun `login card appears only when the default importer is unauthorized`() = runBlocking {
        every { tidal.tokenFileExists() } returns false
        val root = page.render(scope()) as UiComponent.Column
        assertEquals(3, root.children.size)
        val card = root.children[0] as UiComponent.Card
        assertEquals("Login Required", card.title)
        assertEquals("Please log in to use the importer.", card.subtitle)
        assertEquals(UiTone.WARNING, card.tone)
        val login = card.actions.single() as UiComponent.Button
        assertEquals("Login", login.label)
        assertEquals("tidal", ((login.action as UiAction.Invoke).params["importer"])?.text)
    }

    @Test
    fun `toolbar is queue, sync favorites and settings`() = runBlocking {
        val toolbar = page.toolbar(scope()).map { it as UiComponent.Button }
        assertEquals(listOf("Queue", "Sync Favorites", "Importer settings"), toolbar.map { it.label })
        assertEquals(UiAction.OpenPage(ImporterQueuePageContribution.ID, modal = true), toolbar[0].action)
        assertEquals("Are you sure you want to synchronize your favorites?", (toolbar[1].action as UiAction.Invoke).confirmText)
        assertEquals(UiAction.OpenPage(ImporterSettingsPageContribution.ID, modal = true), toolbar[2].action)

        every { tidal.canHandle(any()) } returns false
        assertEquals(listOf("Queue", "Importer settings"), page.toolbar(scope()).map { (it as UiComponent.Button).label })
    }

    @Test
    fun `settings page manages auth and hides slot items the user may not see`() = runBlocking {
        registry.register(object : UiContribution("x.section", UiContributionKind.SLOT, "importer.title", UiSlots.IMPORTER) {
            override suspend fun render(scope: UiRenderScope) = UiComponent.Badge("inlined")
        }, "x")
        registry.register(object : UiContribution("x.admin", UiContributionKind.SLOT, "importer.title", UiSlots.IMPORTER, access = UiAccess(requiresAdmin = true)) {
            override suspend fun render(scope: UiRenderScope) = UiComponent.Badge("hidden")
        }, "x")

        val root = settingsPage.render(scope()) as UiComponent.Column
        val importers = root.children[0] as UiComponent.Section
        assertEquals("Importers", importers.title)
        val all = importers.flatten()
        assertEquals("Authorized", all.filterIsInstance<UiComponent.ListItem>().first { it.title == "Tidal" }.subtitle)
        val logins = all.filterIsInstance<UiComponent.Button>().filter { (it.action as? UiAction.Invoke)?.actionId == "login" }
        assertEquals(listOf("youtube"), logins.map { (it.action as UiAction.Invoke).params["importer"]?.text })

        val inlined = root.children.drop(1).map { it as UiComponent.Section }
        assertEquals(1, inlined.size)
        assertTrue(inlined.single().collapsible && inlined.single().collapsed)
        assertEquals("inlined", (inlined.single().children.single() as UiComponent.Badge).text)
    }

    @Test
    fun `import goes through the intake and maps its result`() = runBlocking {
        val empty = page.invoke(scope(), "import", mapOf("input" to UiValue.of("  \n")))
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, empty.status)
        assertNotNull(empty.fieldErrors["input"])

        val items = listOf(IntakeItem.Url("https://tidal.com/browse/album/1"), IntakeItem.Code(UiIntakeCodeKind.ISRC, "USRC17607839"))
        coEvery { intakeService.submit(items, null, account, "en") } returns UiIntakeResult(UiIntakeStatus.OK, accepted = 2)
        val ok = page.invoke(scope(), "import", mapOf("input" to UiValue.of("https://tidal.com/browse/album/1\nUSRC17607839")))
        assertEquals(UiInvokeStatus.OK, ok.status)
        assertEquals("2 items queued", ok.message)
        assertTrue(ok.refresh)

        val handler = UiHookHandler("import.gamdl", "import.gamdl", "server", "Import with gamdl", null, null, UiAction.Intake(items, "import.gamdl"))
        coEvery { intakeService.submit(items, null, account, "en") } returns UiIntakeResult(UiIntakeStatus.NEEDS_CHOICE, handlers = listOf(handler))
        val choice = page.invoke(scope(), "import", mapOf("input" to UiValue.of("https://tidal.com/browse/album/1\nUSRC17607839")))
        assertEquals(UiInvokeStatus.OK, choice.status)
        assertEquals(listOf(UiMenuItem("Import with gamdl", handler.action, id = "import.gamdl")), (choice.next as UiAction.OpenMenu).items)

        coEvery { intakeService.submit(items, null, account, "en") } returns UiIntakeResult(UiIntakeStatus.UNHANDLED, rejected = items)
        val unhandled = page.invoke(scope(), "import", mapOf("input" to UiValue.of("https://tidal.com/browse/album/1\nUSRC17607839")))
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, unhandled.status)
        assertTrue(unhandled.fieldErrors["input"]!!.contains("USRC17607839"))
        coVerify(exactly = 0) { importService.addToQueue(*anyVararg()) }
    }

    @Test
    fun `live log streams filtered lines and keeps them across imports`() = runBlocking {
        assertNull(page.live(scope(), "nope"))
        val updates = mutableListOf<UiLiveUpdate>()
        val job = launch { page.live(scope(), "log")!!.take(2).toList(updates) }
        while (logFlow.subscriptionCount.value < 2) yield()
        logFlow.emit("Let us check the token")
        logFlow.emit("   ")
        logFlow.emit(null)
        logFlow.emit("Downloading 2/2")
        while (updates.isEmpty()) yield()
        every { importService.currentImport } returns null
        every { importService.currentImport(any()) } returns null
        queueChanges.emit(Unit)
        logFlow.emit("Done")
        job.join()
        assertEquals(listOf(UiLiveUpdate.AppendLines(listOf("Downloading 2/2")), UiLiveUpdate.AppendLines(listOf("Done"))), updates)

        while (state.logLines().size < 4) yield()
        assertEquals(listOf("Fetching metadata…", "Downloading 1/2", "Downloading 2/2", "Done"), state.logLines())
        val log = ((page.render(scope()) as UiComponent.Column).children[1] as UiComponent.Column).children[1] as UiComponent.Live
        assertEquals(listOf("Fetching metadata…", "Downloading 1/2", "Downloading 2/2", "Done"), (log.child as UiComponent.Log).lines)
    }

    @Test
    fun `queue page mirrors the ios sheet`() = runBlocking {
        val root = queuePage.render(scope()) as UiComponent.Column
        val stats = root.children[0].flatten().filterIsInstance<UiComponent.Stat>()
        assertEquals(listOf("Total URLs" to "3", "Importing" to "2"), stats.map { it.label to it.value })
        assertEquals("Currently Importing", (root.children[1] as UiComponent.Text).text)
        val current = root.children[2] as UiComponent.Card
        assertEquals("URLs", (current.flatten().filterIsInstance<UiComponent.Text>().first()).text)
        val menu = (current.flatten().filterIsInstance<UiComponent.ListItem>().single().action as UiAction.OpenMenu)
        assertEquals(currentEntry.urls, menu.items.map { it.label })
        assertEquals(UiAction.OpenUrl(currentEntry.urls[0]), menu.items[0].action)
        val badges = current.flatten().filterIsInstance<UiComponent.Badge>()
        assertEquals(listOf("TRACK", "Importer Ann"), badges.map { it.text })
        assertEquals(0.5, current.flatten().filterIsInstance<UiComponent.Progress>().single().value)
        val cancel = current.actions.single() as UiComponent.Button
        assertEquals("cancel", (cancel.action as UiAction.Invoke).actionId)
        assertEquals(UiIcon(UiIconName.USER), badges[1].icon)

        assertEquals("Pending Imports", (root.children[3] as UiComponent.Text).text)
        val favorites = root.children[4] as UiComponent.Card
        assertEquals("Favorites", favorites.flatten().filterIsInstance<UiComponent.Text>().first().text)
        assertEquals("tracks", favorites.flatten().filterIsInstance<UiComponent.Text>()[1].text)
        val album = root.children[5] as UiComponent.Card
        assertEquals(listOf("ALBUM"), album.flatten().filterIsInstance<UiComponent.Badge>().map { it.text })
    }

    @Test
    fun `queue page shows the empty state`() = runBlocking {
        every { importService.importJobs(any()) } returns emptyList()
        val root = queuePage.render(scope()) as UiComponent.EmptyState
        assertEquals("Queue is empty", root.title)
        assertEquals("No tasks are currently importing or pending.", root.description)
        assertEquals(UiIcon(UiIconName.QUEUE), root.icon)
    }

    @Test
    fun `share hooks offer to open the importer with the text prefilled`() = runBlocking {
        val offer = page.onHook(scope(), UiHookEvent.ShareUrl("https://tidal.com/browse/album/1"))!!
        assertEquals("importer.hook.open", offer.titleKey)
        assertEquals(UiAction.OpenPage("core.importer", mapOf("input" to "https://tidal.com/browse/album/1")), offer.action)
        assertNull(page.onHook(scope(), UiHookEvent.ShareText("   ")))
    }

    @Test
    fun `home card shows queue stats and opens the page`() = runBlocking {
        val card = ImporterHomeCardContribution(state)
        assertEquals(UiContributionKind.HOME_CARD, card.kind)
        val root = card.render(scope()) as UiComponent.Card
        assertEquals("Importer", root.title)
        assertEquals(listOf("Pending" to "2", "Importing" to "1"), root.flatten().filterIsInstance<UiComponent.Stat>().map { it.label to it.value })
        assertEquals(currentEntry.urls.joinToString(", "), root.flatten().filterIsInstance<UiComponent.ListItem>().single().title)
        assertEquals(UiAction.OpenPage(ImporterPageContribution.ID), (root.actions[0] as UiComponent.Button).action)

        every { importService.currentImport(any()) } returns null
        coEvery { importService.importQueue(any()) } returns emptyList()
        val idle = card.render(scope()) as UiComponent.Card
        assertTrue(idle.flatten().none { it is UiComponent.Progress })
        assertEquals("Queue is empty", idle.flatten().filterIsInstance<UiComponent.Text>().single().text)
    }

    @Test
    fun `library entry opens the page`() = runBlocking {
        val tile = ImporterLibraryEntryContribution().render(scope()) as UiComponent.Tile
        assertEquals("Importer", tile.title)
        assertEquals(UiAction.OpenPage(ImporterPageContribution.ID), tile.action)
    }
}
