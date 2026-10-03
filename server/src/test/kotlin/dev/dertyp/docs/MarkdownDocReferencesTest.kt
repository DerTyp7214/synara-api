package dev.dertyp.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarkdownDocReferencesTest {

    private val servicesMarkdown = """
        # Synara RPC Services Documentation

        ## Table of Contents
        - [IMetadataService](#devdertypservicesmetadataimetadataservice)

        ### IMetadataService <a name="devdertypservicesmetadataimetadataservice"></a>

        | Function | Parameters | Returns | Permissions | Errors | Description |
        | :--- | :--- | :--- | :--- | :--- | :--- |
        | `searchAlbum` <a name="devdertypservicesmetadataimetadataservice-searchalbum"></a> | - | Unit | - | - | - |
    """.trimIndent()

    private val modelsMarkdown = """
        # Synara Data Models

        ## Table of Contents
        - [dev.dertyp.data.Album](#devdertypdataalbum)

        ## Data Models

        ### Album <a name="devdertypdataalbum"></a>
        *Full name: `dev.dertyp.data.Album`*

        | Field | Type | Description |
        | :--- | :--- | :--- |
        | `musicBrainzId` | `PlatformUUID`? | - |
        | `musicbrainzId` | `PlatformUUID`? | Old name, used for clients below API version 8. |

        ### ChangeTopic <a name="devdertypdatachangetopic"></a>

        | Value | Description |
        | :--- | :--- |
        | `LISTENS` | - |

        ### QueueWriteResult <a name="devdertypdataqueuewriteresult"></a>

        ### Conflict <a name="devdertypdataqueuewriteresultconflict"></a>

        ### ClientSettingsWriteResult <a name="devdertypdataclientsettingswriteresult"></a>

        ### Conflict <a name="devdertypdataclientsettingswriteresultconflict"></a>

        ### Image <a name="devdertypdataimage"></a>

        ### Image <a name="devdertypuiimage"></a>

        ### Album <a name="devdertypservicesmetadataimetadataservicealbum"></a>
        *Full name: `dev.dertyp.services.metadata.IMetadataService.Album`*
    """.trimIndent()

    private fun references() = MarkdownDocReferences(
        services = parseDocEntries(servicesMarkdown),
        models = parseDocEntries(modelsMarkdown),
        serviceDoc = "RPC_SERVICES.md",
        modelDoc = "MODELS.md",
    )

    @Test
    fun `links models, fields, entries and services like the doc compiler`() {
        val linked = references().link(
            "See @ChangeTopic, @ChangeTopic.LISTENS, @Album.musicBrainzId and @IMetadataService.searchAlbum.",
            "X",
        )

        assertEquals(
            "See [ChangeTopic](MODELS.md#devdertypdatachangetopic), " +
                    "[ChangeTopic.LISTENS](MODELS.md#devdertypdatachangetopic), " +
                    "[Album.musicBrainzId](MODELS.md#devdertypdataalbum) and " +
                    "[IMetadataService.searchAlbum](RPC_SERVICES.md#devdertypservicesmetadataimetadataservice-searchalbum).",
            linked,
        )
    }

    @Test
    fun `prefers the single top-level model over nested models of the same name`() {
        assertEquals("[Album](MODELS.md#devdertypdataalbum)", references().link("@Album", "X"))
    }

    @Test
    fun `links nested models through their outer service or model`() {
        val linked = references().link("@IMetadataService.Album and @QueueWriteResult.Conflict", "X")

        assertEquals(
            "[IMetadataService.Album](MODELS.md#devdertypservicesmetadataimetadataservicealbum) and " +
                    "[QueueWriteResult.Conflict](MODELS.md#devdertypdataqueuewriteresultconflict)",
            linked,
        )
    }

    @Test
    fun `leaves text without references untouched`() {
        val text = "Mail me at a@Example.com, use @lowercase, `@RestGet` or an @ sign."

        assertEquals(text, references().link(text, "X"))
    }

    @Test
    fun `fails on unknown names`() {
        val error = assertFailsWith<IllegalStateException> { references().link("See @Nope.", "ClientFeature.X") }

        assertEquals(
            "[ApiConstantsDocs] ClientFeature.X: unresolved doc reference @Nope: " +
                    "Nope is neither a service in RPC_SERVICES.md nor a model in MODELS.md",
            error.message,
        )
    }

    @Test
    fun `fails on names that stay ambiguous`() {
        val conflict = assertFailsWith<IllegalStateException> { references().link("@Conflict", "X") }
        val image = assertFailsWith<IllegalStateException> { references().link("@Image", "X") }

        assertEquals(
            "[ApiConstantsDocs] X: unresolved doc reference @Conflict: " +
                    "Conflict names more than one documented service or model",
            conflict.message,
        )
        assertEquals(
            "[ApiConstantsDocs] X: unresolved doc reference @Image: " +
                    "Image names more than one documented service or model",
            image.message,
        )
    }

    @Test
    fun `fails on missing members`() {
        val field = assertFailsWith<IllegalStateException> { references().link("@Album.title", "X") }
        val method = assertFailsWith<IllegalStateException> { references().link("@IMetadataService.search", "X") }

        assertEquals(
            "[ApiConstantsDocs] X: unresolved doc reference @Album.title: " +
                    "Album has no nested model, field or entry title",
            field.message,
        )
        assertEquals(
            "[ApiConstantsDocs] X: unresolved doc reference @IMetadataService.search: " +
                    "IMetadataService has no method or nested model search",
            method.message,
        )
    }
}
