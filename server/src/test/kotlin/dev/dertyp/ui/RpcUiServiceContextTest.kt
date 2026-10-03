package dev.dertyp.ui

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.User
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.RpcUiService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.services.ui.UiService
import dev.dertyp.services.ui.UserHomeCardService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class RpcUiServiceContextTest {
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val uiService =
        UiService(registry, translations, PluginSettingsService(), UserHomeCardService(), IntakeService(translations))
    private val user = User(UUID.randomUUID(), "admin", passwordHash = "", isAdmin = true)
    private val client = ClientInfo(ApiVersion.CURRENT, UiSchemaVersion.CURRENT, "en")
    private val rpc = RpcUiService(user, client, null, uiService)

    private val entityId = UUID.randomUUID()
    private val context = UiContext(UiEntityType.ALBUM, entityId, mapOf("show" to "42", "tab" to "episodes"))

    private class ContextEcho : UiContribution("core.echo", UiContributionKind.PAGE, "echo.title", null) {
        override suspend fun render(scope: UiRenderScope): UiComponent = UiComponent.Text(describe(scope.context))

        override fun live(scope: UiRenderScope, key: String): Flow<UiLiveUpdate>? =
            flowOf(UiLiveUpdate.AppendLines(listOf("$key ${describe(scope.context)}")))

        private fun describe(context: UiContext): String =
            "${context.entityType} ${context.entityId} ${context.params.toSortedMap()}"
    }

    init {
        registry.register(ContextEcho(), "server")
    }

    private fun text(render: UiRender) = (render.root as UiComponent.Text).text

    @Test
    fun `subscribeWithContext renders with the full context including params`() = runBlocking {
        val render = rpc.subscribeWithContext("core.echo", context).first()
        assertEquals("ALBUM $entityId {show=42, tab=episodes}", text(render))
        assertEquals(text(rpc.render("core.echo", context)), text(render))
    }

    @Test
    fun `subscribeLiveWithContext passes the full context to the live node`() = runBlocking {
        val update = rpc.subscribeLiveWithContext("core.echo", "log", context).first()
        assertEquals(UiLiveUpdate.AppendLines(listOf("log ALBUM $entityId {show=42, tab=episodes}")), update)
    }

    @Test
    fun `subscribe and subscribeLive keep carrying only the entity id`() = runBlocking {
        assertEquals("null $entityId {}", text(rpc.subscribe("core.echo", entityId).first()))
        assertEquals("null null {}", text(rpc.subscribe("core.echo", null).first()))
        assertEquals(
            UiLiveUpdate.AppendLines(listOf("log null $entityId {}")),
            rpc.subscribeLive("core.echo", "log", entityId).first()
        )
    }
}
