package dev.dertyp.ui

import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.full.findAnnotation

@OptIn(ExperimentalSerializationApi::class)
class UiSerializationTest {

    private val invoke = UiAction.Invoke("core.importer", "import", mapOf("count" to UiValue.of(2), "flag" to UiValue.of(true)), formId = "import", confirmText = "Sure?")

    private val tree: UiComponent = UiComponent.Column(
        children = listOf(
            UiComponent.Row(listOf(UiComponent.Icon(UiIcon(UiIconName.MUSIC)), UiComponent.Badge("ok", UiTone.SUCCESS)), weights = listOf(0.0, 1.0)),
            UiComponent.Grid(listOf(UiComponent.Stat("Songs", "1", "k", UiIcon(UiIconName.MUSIC))), columns = 3),
            UiComponent.Card(
                title = "Card",
                subtitle = "Sub",
                icon = UiIcon(UiIconName.STATS),
                tone = UiTone.PRIMARY,
                children = listOf(UiComponent.Text("body", UiTextStyle.CODE, UiTone.MUTED, UiEmphasis.LOW)),
                actions = listOf(UiComponent.Button("Go", UiAction.OpenUrl("https://example.org"), UiButtonStyle.PRIMARY, UiIcon(UiIconName.PLAY))),
            ),
            UiComponent.Section(
                title = "Section",
                collapsible = true,
                collapsed = true,
                children = listOf(
                    UiComponent.Image(UUID.randomUUID(), null, rounded = true),
                    UiComponent.Progress(0.5, "half"),
                    UiComponent.Progress(),
                    UiComponent.Tile("Tile", "sub", UiIcon(UiIconName.DOWNLOAD), UiAction.OpenPage("core.importer", mapOf("input" to "x"))),
                    UiComponent.ListItem("Item", "sub", UiIcon(UiIconName.QUEUE), "trailing", UiAction.OpenEntity(UiEntityType.ALBUM, UUID.randomUUID())),
                    UiComponent.Table(listOf("a", "b"), listOf(UiTableRow(listOf("1", "2"), UiAction.Refresh))),
                    UiComponent.Spacer(UiSpacing.LARGE),
                    UiComponent.Divider,
                    UiComponent.Fallback("update"),
                    UiComponent.Native(UiPortals.BARCODE_SCANNER, mapOf("target" to "input"), UiComponent.Text("no scanner")),
                    UiComponent.Live("log", UiComponent.Log(listOf("a", "b"), 100)),
                    UiComponent.EmptyState("Nothing", "here", UiIcon(UiIconName.QUEUE), listOf(UiComponent.Button("Add", UiAction.Refresh))),
                    UiComponent.Badge("me", UiTone.MUTED, icon = UiIcon(UiIconName.USER)),
                    UiComponent.Button("Sheet", UiAction.OpenPage("core.importer.queue", modal = true)),
                ),
            ),
            UiComponent.Form(
                id = "f",
                submit = invoke,
                submitLabel = "Save",
                cancelLabel = "Cancel",
                actions = listOf(UiComponent.Native(UiPortals.BARCODE_SCANNER, mapOf("target" to "t"))),
                children = listOf(
                    UiComponent.TextField(
                        "t", "Text", "v", "p", secret = true, multiline = true, helper = "h", error = "e", required = true, enabled = false, kind = UiTextKind.MULTILINE_URLS,
                        toolbar = listOf(UiComponent.Button("Done", UiAction.DismissKeyboard, icon = UiIcon(UiIconName.CHECK))),
                    ),
                    UiComponent.NumberField("n", "Number", 1.0, 0.0, 10.0, 1.0),
                    UiComponent.Switch("s", "Switch", true),
                    UiComponent.Select("sel", "Select", "a", listOf(UiOption("a", "A", UiIcon(UiIconName.INFO)))),
                    UiComponent.Button("Native", UiAction.OpenNative(UiPortals.EXTERNAL_SEARCH, mapOf("query" to "q"))),
                    UiComponent.Button(
                        "Menu",
                        UiAction.OpenMenu(
                            title = "More",
                            items = listOf(
                                UiMenuItem("Refresh", UiAction.Refresh, UiIcon(UiIconName.SYNC)),
                                UiMenuItem("Sub", UiAction.OpenMenu(listOf(UiMenuItem("Delete", UiAction.OpenUrl("x"), tone = UiTone.ERROR, enabled = false)))),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val render = UiRender("core.importer", tree, "Importer", revision = 3, toolbar = listOf(UiComponent.Icon(UiIcon(UiIconName.SEARCH))))

    @Test
    fun `cbor round trip preserves the whole tree`() {
        val bytes = AppCbor.encodeToByteArray(render)
        assertEquals(render, AppCbor.decodeFromByteArray<UiRender>(bytes))

        val slot = UiSlotRender("library", listOf(render))
        assertEquals(slot, AppCbor.decodeFromByteArray<UiSlotRender>(AppCbor.encodeToByteArray(slot)))

        val handlers = listOf(UiHookHandler("core.importer", "server", "Import", null, UiIcon(UiIconName.DOWNLOAD), UiAction.OpenPage("core.importer")))
        assertEquals(handlers, AppCbor.decodeFromByteArray<List<UiHookHandler>>(AppCbor.encodeToByteArray(handlers)))

        val event: UiHookEvent = UiHookEvent.ShareUrl("https://tidal.com/x", "title")
        assertEquals(event, AppCbor.decodeFromByteArray<UiHookEvent>(AppCbor.encodeToByteArray(event)))

        val items: List<IntakeItem> = listOf(
            IntakeItem.Url("https://x"), IntakeItem.Code(UiIntakeCodeKind.UPC, "1"), IntakeItem.Id("tidal", "1", dev.dertyp.services.import.Type.ALBUM),
            IntakeItem.Text("t"), IntakeItem.File(UUID.randomUUID(), "f.m3u", "audio/x-mpegurl"),
        )
        val intake = UiIntakeResult(UiIntakeStatus.NEEDS_CHOICE, "m", 1, items.take(1), listOf(UiHookHandler("i", "s", "T", null, null, UiAction.Intake(items, "i", "sure?"))), UiAction.Refresh)
        assertEquals(intake, AppCbor.decodeFromByteArray<UiIntakeResult>(AppCbor.encodeToByteArray(intake)))
        assertEquals(intake, AppJson.decodeFromString(UiIntakeResult.serializer(), AppJson.encodeToString(UiIntakeResult.serializer(), intake)))

        val updates: List<UiLiveUpdate> = listOf(UiLiveUpdate.AppendLines(listOf("x", "y")), UiLiveUpdate.Replace(UiComponent.Log(emptyList())))
        assertEquals(updates, AppCbor.decodeFromByteArray<List<UiLiveUpdate>>(AppCbor.encodeToByteArray(updates)))
        assertEquals(updates, AppJson.decodeFromString<List<UiLiveUpdate>>(AppJson.encodeToString(updates)))
    }

    @Test
    fun `json round trip preserves the whole tree and uses type discriminators`() {
        val json = AppJson.encodeToString(UiRender.serializer(), render)
        assertEquals(render, AppJson.decodeFromString(UiRender.serializer(), json))

        val root = Json.parseToJsonElement(json).jsonObject["root"]!!.jsonObject
        assertEquals("column", root["type"]!!.jsonPrimitive.content)

        val payload = UiInvokePayload(mapOf("input" to UiValue.of("a\nb")), UiContext(UiEntityType.SONG, UUID.randomUUID(), mapOf("k" to "v")))
        assertEquals(payload, AppJson.decodeFromString(UiInvokePayload.serializer(), AppJson.encodeToString(UiInvokePayload.serializer(), payload)))

        val result = UiInvokeResult(UiInvokeStatus.VALIDATION_ERROR, "msg", mapOf("input" to "bad"), refresh = true, next = UiAction.OpenUrl("https://x"))
        assertEquals(result, AppJson.decodeFromString(UiInvokeResult.serializer(), AppJson.encodeToString(UiInvokeResult.serializer(), result)))
    }

    @Test
    fun `component and action serial names are frozen`() {
        val componentNames = UiComponent::class.sealedSubclasses.map { it.findAnnotation<kotlinx.serialization.SerialName>()!!.value }.toSet()
        assertEquals(
            setOf(
                "column", "row", "grid", "card", "section", "form", "text", "icon", "image", "badge", "stat", "progress", "tile",
                "button", "listItem", "table", "spacer", "divider", "fallback", "native", "emptyState", "log", "live", "textField", "numberField", "switch", "select",
            ),
            componentNames,
        )
        val actionNames = UiAction::class.sealedSubclasses.map { it.findAnnotation<kotlinx.serialization.SerialName>()!!.value }.toSet()
        assertEquals(setOf("openMenu", "invoke", "openEntity", "openPage", "intake", "dismissKeyboard", "openUrl", "openNative", "refresh"), actionNames)
        val itemNames = IntakeItem::class.sealedSubclasses.map { it.findAnnotation<kotlinx.serialization.SerialName>()!!.value }.toSet()
        assertEquals(setOf("url", "code", "id", "text", "file"), itemNames)
        val updateNames = UiLiveUpdate::class.sealedSubclasses.map { it.findAnnotation<kotlinx.serialization.SerialName>()!!.value }.toSet()
        assertEquals(setOf("replace", "appendLines"), updateNames)
        assertTrue(UiComponent::class.sealedSubclasses.all { it in UiSchema.introducedIn.keys }, "every component must declare the schema version it was introduced in")
    }
}
