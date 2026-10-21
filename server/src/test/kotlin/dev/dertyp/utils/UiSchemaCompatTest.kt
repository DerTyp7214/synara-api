package dev.dertyp.utils

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.User
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.IUiService
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.RpcUiService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.ui.UiService
import dev.dertyp.services.ui.UserHomeCardService
import dev.dertyp.ui.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.UUID

private val avatar = UiImageRef(UUID.fromString("00000000-0000-0000-0000-000000000042"), "hash")

class UiSchemaCompatTest {

    private val futureSchema = UiSchema.introducedIn + (UiComponent.Table::class to 2)
    private val rule = UiSchemaCompat(futureSchema)

    private val tree = UiComponent.Card(
        title = "c",
        children = listOf(
            UiComponent.Section(
                title = "s",
                children = listOf(
                    UiComponent.Table(listOf("a"), emptyList()),
                    UiComponent.Text("keep"),
                ),
            ),
            UiComponent.Native("portal", fallback = UiComponent.Table(emptyList(), emptyList())),
            UiComponent.Live("log", UiComponent.Table(emptyList(), emptyList())),
            UiComponent.Form(
                "f",
                listOf(UiComponent.TextField("k", "l", toolbar = listOf(UiComponent.Table(emptyList(), emptyList())))),
                UiAction.Invoke("c", "a"),
                "s",
                actions = listOf(UiComponent.Table(emptyList(), emptyList()))
            ),
        ),
        actions = listOf(UiComponent.Table(emptyList(), emptyList())),
    )

    @Test
    fun `components newer than the client become fallback, nesting is preserved`() = runBlocking {
        val shaped = rule.downgrade(tree, 1) as UiComponent.Card
        val section = shaped.children[0] as UiComponent.Section
        assertEquals(UiComponent.Fallback(), section.children[0])
        assertEquals(UiComponent.Text("keep"), section.children[1])
        assertEquals(UiComponent.Fallback(), (shaped.children[1] as UiComponent.Native).fallback)
        assertEquals(UiComponent.Fallback(), (shaped.children[2] as UiComponent.Live).child)
        val form = shaped.children[3] as UiComponent.Form
        assertEquals(listOf(UiComponent.Fallback()), form.actions)
        assertEquals(listOf(UiComponent.Fallback()), (form.children.single() as UiComponent.TextField).toolbar)
        assertEquals(listOf(UiComponent.Fallback()), shaped.actions)
        assertEquals(tree, rule.downgrade(tree, 2))
    }

    @Test
    fun `a versioned field without a downgrade is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UiSchemaCompat(fieldIntroducedIn = UiSchema.fieldIntroducedIn + (UiComponent.Text::class to mapOf("tone" to 4)))
        }
    }

    @Test
    fun `images are removed for clients below the image version and icons stay`() = runBlocking {
        val icon = UiIcon(UiIconName.USER)
        val item = UiComponent.ListItem("Alice", icon = icon, image = avatar)
        val select = UiComponent.Select("s", "S", options = listOf(UiOption("u1", "Alice", icon, avatar)))
        val card = UiComponent.Card(listOf(item), title = "Alice", icon = icon, image = avatar)
        val nested = UiComponent.Column(
            listOf(
                UiComponent.Section(title = "s", children = listOf(item)),
                UiComponent.Form("f", listOf(select), UiAction.Invoke("c", "a"), "Save"),
                card,
            ),
        )

        val shaped = rule.downgrade(nested, 2) as UiComponent.Column
        assertEquals(item.copy(image = null), (shaped.children[0] as UiComponent.Section).children.single())
        val shapedSelect = (shaped.children[1] as UiComponent.Form).children.single() as UiComponent.Select
        assertEquals(listOf(UiOption("u1", "Alice", icon)), shapedSelect.options)
        assertEquals(UiComponent.Card(listOf(item.copy(image = null)), title = "Alice", icon = icon), shaped.children[2])
    }

    @Test
    fun `images are kept for clients with the image version`() = runBlocking {
        val item = UiComponent.ListItem("Alice", icon = UiIcon(UiIconName.USER), image = avatar)
        val select = UiComponent.Select("s", "S", options = listOf(UiOption("u1", "Alice", image = avatar)))
        val search = UiComponent.SearchField("u", "U", UiOption("u1", "Alice", image = avatar))
        val column = UiComponent.Column(listOf(item, select, search))
        assertEquals(column, rule.downgrade(column, 3))
    }

    @Test
    fun `client without schema header sees only fallbacks`() = runBlocking {
        assertEquals(UiComponent.Fallback(), rule.downgrade(tree, UiSchemaVersion.NONE))
    }

    @Test
    fun `file fields become multi-line text fields for clients without file field support`() = runBlocking {
        val current = UiSchemaCompat()
        val text = UiComponent.FileField(
            "pem",
            "Certificate",
            accept = listOf(".pem"),
            value = "-----BEGIN",
            helper = "PEM file",
            error = "invalid",
            required = true
        )
        val key = UiComponent.FileField(
            "p8",
            "Key",
            accept = listOf(".p8"),
            binary = true,
            secret = true,
            helper = "Apple key",
            enabled = false
        )
        val form = UiComponent.Form("f", listOf(text, key), UiAction.Invoke("c", "save"), "Save")

        val shaped = current.downgrade(form, 1) as UiComponent.Form
        assertEquals(
            UiComponent.TextField(
                "pem",
                "Certificate",
                value = "-----BEGIN",
                multiline = true,
                helper = "PEM file",
                error = "invalid",
                required = true
            ),
            shaped.children[0],
        )
        assertEquals(
            UiComponent.TextField(
                "p8",
                "Key",
                secret = true,
                multiline = true,
                helper = "Apple key ${UiSchemaCompat.BASE64_HINT}",
                enabled = false
            ),
            shaped.children[1],
        )
        assertEquals(
            UiSchemaCompat.BASE64_HINT,
            (current.downgrade(UiComponent.FileField("k", "l", binary = true), 1) as UiComponent.TextField).helper
        )
        assertEquals(form, current.downgrade(form, 2))
        assertEquals(UiComponent.Fallback(), current.downgrade(text, UiSchemaVersion.NONE))
    }

    @Test
    fun `number and file field toolbars are shaped and survive the file field downgrade`() = runBlocking {
        val done = UiComponent.Button("Done", UiAction.DismissKeyboard)
        val table = UiComponent.Table(emptyList(), emptyList())
        val number = UiComponent.NumberField("n", "Number", toolbar = listOf(done, table))
        val file = UiComponent.FileField("f", "File", toolbar = listOf(done, table))

        assertEquals(number.copy(toolbar = listOf(done, UiComponent.Fallback())), rule.downgrade(number, 1))
        assertEquals(
            UiComponent.TextField("f", "File", multiline = true, toolbar = listOf(done, UiComponent.Fallback())),
            rule.downgrade(file, 1),
        )
        val tableLater = UiSchemaCompat(UiSchema.introducedIn + (UiComponent.Table::class to 3))
        assertEquals(file.copy(toolbar = listOf(done, UiComponent.Fallback())), tableLater.downgrade(file, 2))
        assertEquals(file, rule.downgrade(file, 2))
    }

    @Test
    fun `clients on schema version 1 are shaped by the default rule`() = runBlocking {
        val field = UiComponent.FileField("k", "l")
        val old = ClientInfo(ApiVersion.CURRENT, uiSchemaVersion = 1)
        assertTrue(UiSchemaCompat().isActive(old))
        assertEquals(UiComponent.TextField("k", "l", multiline = true), UiSchemaCompat().shapeUiComponent(field, CompatScope(old)))
        assertFalse(
            UiSchemaCompat().isActive(
                ClientInfo(
                    ApiVersion.CURRENT,
                    uiSchemaVersion = UiSchemaVersion.CURRENT
                )
            )
        )
    }

    interface UiApi {
        suspend fun render(): UiRender
        suspend fun slot(): UiSlotRender
        fun flow(): Flow<UiRender>
        fun live(): Flow<UiLiveUpdate>
    }

    private val render = UiRender("x", tree, toolbar = listOf(UiComponent.Table(emptyList(), emptyList())))
    private val fake = object : UiApi {
        override suspend fun render() = render
        override suspend fun slot() = UiSlotRender("library", listOf(render))
        override fun flow() = flowOf(render)
        override fun live() = flowOf<UiLiveUpdate>(
            UiLiveUpdate.Replace(UiComponent.Table(emptyList(), emptyList())),
            UiLiveUpdate.AppendLines(listOf("l"))
        )
    }

    @Test
    fun `response shaper downgrades renders, slots, toolbars and flows`() = runBlocking {
        val client = ClientInfo(ApiVersion.CURRENT, uiSchemaVersion = 1)
        val shaper = ResponseShaper(client, listOf(rule))
        assertFalse(shaper.isNoop)
        val wrapped = fake.withClientCompat(UiApi::class.java, shaper)

        val shapedRender = wrapped.render()
        assertEquals(UiComponent.Fallback(), (shapedRender.root as UiComponent.Card).actions.single())
        assertEquals(listOf(UiComponent.Fallback()), shapedRender.toolbar)
        assertEquals(1, shapedRender.schemaVersion)
        assertEquals(shapedRender.root, wrapped.slot().items.single().root)
        assertEquals(shapedRender.root, wrapped.flow().toList().single().root)
        assertEquals(
            listOf(UiLiveUpdate.Replace(UiComponent.Fallback()), UiLiveUpdate.AppendLines(listOf("l"))),
            wrapped.live().toList()
        )
    }

    @Test
    fun `current clients are not shaped`() {
        val client = ClientInfo(ApiVersion.CURRENT, uiSchemaVersion = UiSchemaVersion.CURRENT)
        val shaper = ResponseShaper(client, listOf(UiSchemaCompat()))
        assertTrue(shaper.isNoop)
        assertSame(fake, fake.withClientCompat(UiApi::class.java, shaper))
    }

    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val uiService =
        UiService(registry, translations, PluginSettingsService(), UserHomeCardService(), IntakeService(translations))
    private val user = User(UUID.randomUUID(), "user")
    private val legacy = ClientInfo(ApiVersion.CURRENT, uiSchemaVersion = 2, locale = "en")
    private val current = ClientInfo(ApiVersion.CURRENT, uiSchemaVersion = UiSchemaVersion.CURRENT, locale = "en")
    private val alice = UiOption("u1", "Alice")

    private class Picker(id: String, kind: UiContributionKind, private val inForm: Boolean = true) :
        UiContribution(id, kind, "importer.title", if (kind == UiContributionKind.PAGE) null else UiSlots.SETTINGS) {
        val searches = mutableListOf<Triple<String, String, UiContext>>()
        val invoked = mutableListOf<String>()

        override suspend fun render(scope: UiRenderScope): UiComponent {
            val field = UiComponent.SearchField("user", "User", scope.context.params["selected"]?.let { UiOption(it, "Alice", image = avatar) })
            return if (inForm) UiComponent.Form("link", listOf(field), UiAction.Invoke(id, "save", formId = "link"), "Save")
            else UiComponent.Column(listOf(field))
        }

        override suspend fun search(scope: UiRenderScope, key: String, query: String): List<UiOption> {
            searches += Triple(key, query, scope.context)
            return listOf(UiOption("u2", "Bob", image = avatar), UiOption("u1", "Alice", image = avatar), UiOption("u3", "Bobby")).filter {
                it.label.lowercase().startsWith(query.lowercase())
            }
        }

        override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
            invoked += actionId
            return super.invoke(scope, actionId, values)
        }
    }

    private val page = Picker("test.picker", UiContributionKind.PAGE)
    private val loose = Picker("test.loose", UiContributionKind.PAGE, inForm = false)
    private val slot = Picker("test.slot", UiContributionKind.SLOT)

    @BeforeEach
    fun startDi() {
        registry.register(page, "server")
        registry.register(loose, "server")
        registry.register(slot, "server")
        startKoin { modules(module { single { uiService }; single { translations } }) }
    }

    @AfterEach
    fun stopDi() {
        stopKoin()
    }

    private fun api(client: ClientInfo): IUiService = RpcUiService(user, client, null, uiService)
        .withClientCompat(IUiService::class.java, ResponseShaper(client, listOf(UiSchemaCompat()), user = user))

    private fun searchColumn(render: UiRender): List<UiComponent> =
        ((render.root as UiComponent.Form).children.single() as UiComponent.Column).children

    @Test
    fun `search fields become a query field, a search button and a select of the results for schema 2`() = runBlocking {
        val context = UiContext(params = mapOf("selected" to "u1", "user.query" to "bo", "tab" to "a"))

        val render = api(legacy).render("test.picker", context)

        val (query, button, select) = searchColumn(render)
        query as UiComponent.TextField
        assertEquals("user.query", query.key)
        assertEquals("User", query.label)
        assertEquals("bo", query.value)
        assertEquals(1, query.toolbar.size)
        assertEquals(
            UiAction.Invoke("test.picker", "ui.search.user", formId = "link"),
            (button as UiComponent.Button).action,
        )
        assertEquals("Search", button.label)
        assertEquals(
            UiComponent.Select(
                "user",
                "Results",
                value = "u1",
                options = listOf(alice, UiOption("u2", "Bob"), UiOption("u3", "Bobby")),
            ),
            select,
        )
        assertEquals(listOf(Triple("user", "bo", context)), page.searches)
        assertEquals(2, render.schemaVersion)
    }

    @Test
    fun `without a query the select only offers the selected option and nothing is searched`() = runBlocking {
        val render = api(legacy).render("test.picker", UiContext(params = mapOf("selected" to "u1")))

        val (query, _, select) = searchColumn(render)
        assertEquals(null, (query as UiComponent.TextField).value)
        assertEquals(listOf(alice), (select as UiComponent.Select).options)
        assertEquals("u1", select.value)

        val short = api(legacy).render("test.picker", UiContext(params = mapOf("user.query" to "b")))
        assertEquals(emptyList<UiOption>(), (searchColumn(short)[2] as UiComponent.Select).options)
        assertTrue(page.searches.isEmpty())
    }

    @Test
    fun `selected option found by the search is listed once`() = runBlocking {
        val render = api(legacy).render("test.picker", UiContext(params = mapOf("selected" to "u1", "user.query" to "al")))

        assertEquals(listOf(alice), (searchColumn(render)[2] as UiComponent.Select).options)
    }

    @Test
    fun `search fields that cannot be resubmitted fall back`() = runBlocking {
        val outsideForm = api(legacy).render("test.loose", UiContext())
        assertEquals(UiComponent.Fallback(), (outsideForm.root as UiComponent.Column).children.single())

        val inSlot = api(legacy).renderSlot(UiSlots.SETTINGS, UiContext()).items.single()
        assertEquals(UiComponent.Fallback(), (inSlot.root as UiComponent.Form).children.single())

        val withoutUser = RpcUiService(user, legacy, null, uiService)
            .withClientCompat(IUiService::class.java, ResponseShaper(legacy, listOf(UiSchemaCompat())))
            .render("test.picker", UiContext())
        assertEquals(UiComponent.Fallback(), (withoutUser.root as UiComponent.Form).children.single())
    }

    @Test
    fun `legacy search invokes reopen the page with the query merged into its params`() = runBlocking {
        val payload = UiInvokePayload(
            values = mapOf("user.query" to UiValue.of("ali"), "user" to UiValue.of("u1")),
            context = UiContext(params = mapOf("tab" to "a", "user.query" to "old")),
        )

        val result = api(legacy).invoke("test.picker", "ui.search.user", payload)

        assertEquals(
            UiInvokeResult(
                UiInvokeStatus.OK,
                next = UiAction.OpenPage("test.picker", mapOf("tab" to "a", "user.query" to "ali")),
            ),
            result,
        )
        assertTrue(page.invoked.isEmpty())
        api(legacy).invoke("test.picker", "save", payload)
        assertEquals(listOf("save"), page.invoked)
    }

    @Test
    fun `schema 3 clients get the search field and their invokes reach the contribution`() = runBlocking {
        val render = api(current).render("test.picker", UiContext(params = mapOf("selected" to "u1")))

        val field = (render.root as UiComponent.Form).children.single() as UiComponent.SearchField
        assertEquals(alice.copy(image = avatar), field.value)
        assertEquals(1, field.toolbar.size)

        val result = api(current).invoke("test.picker", "ui.search.user", UiInvokePayload())
        assertEquals(UiInvokeStatus.ERROR, result.status)
        assertEquals(listOf("ui.search.user"), page.invoked)
        assertTrue(page.searches.isEmpty())
    }
}
