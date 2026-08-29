package dev.dertyp.ui

import dev.dertyp.core.ClientInfo
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.User
import dev.dertyp.data.UserCapability
import dev.dertyp.plugins.UiAccess
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiHookOffer
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.ui.UiService
import dev.dertyp.services.ui.UserHomeCardService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class UiServiceTest {
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val service = UiService(registry, translations, PluginSettingsService(), UserHomeCardService())

    private val admin = User(UUID.randomUUID(), "admin", passwordHash = "", isAdmin = true)
    private val importer = User(UUID.randomUUID(), "importer", passwordHash = "", capabilities = listOf(UserCapability.IMPORT))
    private val plain = User(UUID.randomUUID(), "plain", passwordHash = "")
    private val client = ClientInfo(ApiVersion.CURRENT, UiSchemaVersion.CURRENT, "de")

    private open class Fake(
        id: String,
        access: UiAccess = UiAccess(),
        kind: UiContributionKind = UiContributionKind.SLOT,
        slot: String? = UiSlots.SETTINGS,
        hooks: Set<UiHookKind> = emptySet(),
        order: Int = 0,
    ) : UiContribution(id, kind, "importer.title", slot, order = order, access = access, hooks = hooks) {
        var invoked = mutableListOf<Pair<String, Map<String, UiValue>>>()
        val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        var renders = 0

        override suspend fun render(scope: UiRenderScope): UiComponent {
            renders++
            return UiComponent.Text("${scope.t("importer.title")} #$renders ${scope.i18n.locale}")
        }

        override fun changes(scope: UiRenderScope): Flow<Unit> = ticks

        override fun live(scope: UiRenderScope, key: String): Flow<UiLiveUpdate>? =
            if (key == "lines") ticks.map { UiLiveUpdate.AppendLines(listOf(scope.i18n.locale)) } else null

        override suspend fun invoke(scope: UiRenderScope, actionId: String, values: Map<String, UiValue>): UiInvokeResult {
            invoked += actionId to values
            return when (actionId) {
                "ok" -> UiInvokeResult(UiInvokeStatus.OK, refresh = true)
                "bad" -> throw IllegalArgumentException("bad input")
                "boom" -> throw IllegalStateException("boom")
                else -> super.invoke(scope, actionId, values)
            }
        }
    }

    private val adminOnly = Fake("core.admin", UiAccess(requiresAdmin = true))
    private val importOnly = Fake("core.import", UiAccess(capabilities = setOf(UserCapability.IMPORT)), order = 2)
    private val open = Fake("core.open", order = 1)

    init {
        registry.register(adminOnly, "server")
        registry.register(importOnly, "server")
        registry.register(open, "plugin")
    }

    @Test
    fun `list filters by access, kind and slot and translates titles`() = runBlocking {
        assertEquals(listOf("core.admin", "core.open", "core.import"), service.list(admin, client).map { it.id })
        assertEquals(listOf("core.open", "core.import"), service.list(importer, client).map { it.id })
        assertEquals(listOf("core.open"), service.list(plain, client).map { it.id })
        assertTrue(service.list(admin, client, slot = UiSlots.LIBRARY).isEmpty())
        assertTrue(service.list(admin, client, kind = UiContributionKind.PAGE).isEmpty())
        val info = service.list(plain, client).single()
        assertEquals("Importer", info.title)
        assertEquals("plugin", info.source)
        assertTrue(info.live)
    }

    @Test
    fun `render, invoke and subscribe enforce per contribution access`() = runBlocking {
        assertThrows<UnauthorizedException> { service.render(plain, client, "core.admin", UiContext()) }
        assertThrows<UnauthorizedException> { service.invoke(plain, client, "core.import", "ok", UiInvokePayload()) }
        assertThrows<UnauthorizedException> { service.subscribe(plain, client, "core.admin").toList() }
        assertThrows<IllegalArgumentException> { service.render(admin, client, "core.missing", UiContext()) }
        val render = service.render(importer, client, "core.import", UiContext())
        assertEquals("core.import", render.contributionId)
        assertEquals(UiSchemaVersion.CURRENT, render.schemaVersion)
        assertTrue((render.root as UiComponent.Text).text.endsWith("de"))
    }

    @Test
    fun `invoke dispatches to the contribution and maps exceptions`() = runBlocking {
        val payload = UiInvokePayload(mapOf("k" to UiValue.of("v")))
        assertEquals(UiInvokeStatus.OK, service.invoke(admin, client, "core.open", "ok", payload).status)
        assertEquals("ok" to mapOf("k" to UiValue.of("v")), open.invoked.last())
        val bad = service.invoke(admin, client, "core.open", "bad", payload)
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, bad.status)
        assertEquals("bad input", bad.message)
        assertEquals(UiInvokeStatus.ERROR, service.invoke(admin, client, "core.open", "boom", payload).status)
        assertEquals(UiInvokeStatus.ERROR, service.invoke(admin, client, "core.open", "unknown", payload).status)
    }

    @Test
    fun `subscribe emits immediately and again on ticks and invalidations`() = runBlocking {
        val emissions = mutableListOf<UiRender>()
        val job = launch { service.subscribe(admin, client, "core.open").take(3).toList(emissions) }
        while (emissions.isEmpty() || open.ticks.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        open.ticks.emit(Unit)
        while (emissions.size < 2) kotlinx.coroutines.yield()
        service.invoke(admin, client, "core.open", "ok", UiInvokePayload())
        job.join()
        assertEquals(3, emissions.size)
        assertTrue(emissions.map { it.revision }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `subscribeLive enforces access, rejects unknown keys and streams updates`() = runBlocking {
        assertThrows<UnauthorizedException> { service.subscribeLive(plain, client, "core.admin", "lines").toList() }
        assertThrows<IllegalArgumentException> { service.subscribeLive(admin, client, "core.open", "nope").toList() }
        val updates = mutableListOf<UiLiveUpdate>()
        val job = launch { service.subscribeLive(admin, client, "core.open", "lines").take(1).toList(updates) }
        while (open.ticks.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        open.ticks.emit(Unit)
        job.join()
        assertEquals(listOf(UiLiveUpdate.AppendLines(listOf("de"))), updates)
    }

    @Test
    fun `renderSlot renders visible contributions in order`() = runBlocking {
        assertEquals(listOf("core.open", "core.import"), service.renderSlot(importer, client, UiSlots.SETTINGS, UiContext()).items.map { it.contributionId })
        assertEquals(listOf("core.open"), service.renderSlot(plain, client, UiSlots.SETTINGS, UiContext()).items.map { it.contributionId })
    }

    @Test
    fun `dispatchHook returns every offering contribution in order and tolerates failures`() = runBlocking {
        class Hooked(id: String, order: Int, private val offer: UiHookOffer?, private val fail: Boolean = false, access: UiAccess = UiAccess()) :
            Fake(id, access, UiContributionKind.PAGE, null, setOf(UiHookKind.SHARE_URL), order) {
            override suspend fun onHook(scope: UiRenderScope, event: UiHookEvent): UiHookOffer? {
                if (fail) throw IllegalStateException("nope")
                return offer
            }
        }
        registry.register(Hooked("hook.second", 20, UiHookOffer("importer.hook.import", UiAction.OpenPage("hook.second"))), "b")
        registry.register(Hooked("hook.first", 10, UiHookOffer("importer.hook.search", UiAction.OpenNative("externalSearch"), icon = "search")), "a")
        registry.register(Hooked("hook.declines", 5, null), "c")
        registry.register(Hooked("hook.fails", 1, UiHookOffer("x", UiAction.Refresh), fail = true), "d")
        registry.register(Hooked("hook.admin", 0, UiHookOffer("x", UiAction.Refresh), access = UiAccess(requiresAdmin = true)), "e")

        val handlers = service.dispatchHook(plain, client, UiHookEvent.ShareUrl("https://tidal.com/x"))
        assertEquals(listOf("hook.first", "hook.second"), handlers.map { it.contributionId })
        assertEquals("Katalog durchsuchen", handlers[0].title)
        assertEquals("search", handlers[0].icon)
        assertEquals(UiAction.OpenPage("hook.second"), handlers[1].action)
        assertTrue(service.dispatchHook(plain, client, UiHookEvent.ShareText("hello")).isEmpty())
        assertEquals("hook.admin", service.dispatchHook(admin, client, UiHookEvent.ShareUrl("u")).first().contributionId)
    }

    @Test
    fun `registry rejects invalid ids and duplicate registrations`() {
        assertThrows<IllegalArgumentException> { registry.register(Fake("Bad Id"), "x") }
        registry.register(Fake("core.open"), "other")
        assertEquals("plugin", registry.get("core.open")!!.source)
        assertNull(registry.get("nope"))
    }
}
