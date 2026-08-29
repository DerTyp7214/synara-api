package dev.dertyp.utils

import dev.dertyp.core.ClientInfo
import dev.dertyp.data.ApiVersion
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiLiveUpdate
import dev.dertyp.ui.UiRender
import dev.dertyp.ui.UiSchema
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.ui.UiSlotRender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
            UiComponent.Form("f", listOf(UiComponent.TextField("k", "l", toolbar = listOf(UiComponent.Table(emptyList(), emptyList())))), UiAction.Invoke("c", "a"), "s", actions = listOf(UiComponent.Table(emptyList(), emptyList()))),
        ),
        actions = listOf(UiComponent.Table(emptyList(), emptyList())),
    )

    @Test
    fun `components newer than the client become fallback, nesting is preserved`() {
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
    fun `client without schema header sees only fallbacks`() {
        assertEquals(UiComponent.Fallback(), rule.downgrade(tree, UiSchemaVersion.NONE))
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
        override fun live() = flowOf<UiLiveUpdate>(UiLiveUpdate.Replace(UiComponent.Table(emptyList(), emptyList())), UiLiveUpdate.AppendLines(listOf("l")))
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
        assertEquals(listOf(UiLiveUpdate.Replace(UiComponent.Fallback()), UiLiveUpdate.AppendLines(listOf("l"))), wrapped.live().toList())
    }

    @Test
    fun `current clients are not shaped`() {
        val client = ClientInfo(ApiVersion.CURRENT, uiSchemaVersion = UiSchemaVersion.CURRENT)
        val shaper = ResponseShaper(client, listOf(UiSchemaCompat()))
        assertTrue(shaper.isNoop)
        assertSame(fake, fake.withClientCompat(UiApi::class.java, shaper))
    }
}
