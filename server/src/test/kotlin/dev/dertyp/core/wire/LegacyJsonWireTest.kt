package dev.dertyp.core.wire

import dev.dertyp.core.ClientInfo
import dev.dertyp.core.jsonFor
import dev.dertyp.data.PlaybackState
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.models.tidal.BaseAttributes
import dev.dertyp.services.models.tidal.JsonAttribute
import dev.dertyp.services.models.tidal.JsonAttributeSerializer
import dev.dertyp.services.models.tidal.ResourceIdentifier
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.dertyp.core.wire.old.QueueWriteResult as OldQueueWriteResult

class LegacyJsonWireTest {
    private val legacy = jsonFor(ClientInfo(7))
    private val plain = jsonFor(ClientInfo(8))

    private fun <N, O> assertSameAsOldClient(case: WireCase<N, O>) {
        val old = OldClientJson.encodeToString(case.oldSerializer, case.oldValue)
        assertEquals(old, legacy.encodeToString(case.serializer, case.value), case.name)
    }

    private fun <N, O> assertServerDecodesOldClient(case: WireCase<N, O>) {
        val old = OldClientJson.encodeToString(case.oldSerializer, case.oldValue)
        assertEquals(case.value, legacy.decodeFromString(case.serializer, old), case.name)
        assertEquals(
            case.value,
            legacy.decodeFromJsonElement(case.serializer, AppJson.parseToJsonElement(old)),
            case.name
        )
    }

    private fun <N, O> assertOldClientDecodesLegacy(case: WireCase<N, O>) {
        val text = legacy.encodeToString(case.serializer, case.value)
        assertEquals(case.oldValue, OldClientJson.decodeFromString(case.oldSerializer, text), case.name)
    }

    private fun <N, O> assertPlainForNewClients(case: WireCase<N, O>) {
        val text = plain.encodeToString(case.serializer, case.value)
        assertEquals(AppJson.encodeToString(case.serializer, case.value), text, case.name)
        assertEquals(case.value, plain.decodeFromString(case.serializer, text), case.name)
    }

    @Test
    fun `legacy encoding equals the old client encoding`() {
        wireCases.forEach { assertSameAsOldClient(it) }
    }

    @Test
    fun `the server decodes old client payloads into the new models`() {
        wireCases.forEach { assertServerDecodesOldClient(it) }
    }

    @Test
    fun `an old client decodes the legacy encoding`() {
        wireCases.forEach { assertOldClientDecodesLegacy(it) }
    }

    @Test
    fun `clients from api version 8 get exactly the plain encoding`() {
        wireCases.forEach { assertPlainForNewClients(it) }
        val text = plain.encodeToString(PlaybackState.serializer(), newState)
        assertTrue("\"isShuffled\":true" in text)
        assertTrue("{\"type\":\"WithSong\",\"song\":" in text)
        assertFalse("shuffleMode" in text)
        assertFalse("Explicit" in text)
        assertFalse("musicbrainzId" in text)
    }

    @Test
    fun `the queue entry type is written inline under its old name`() {
        val text = legacy.encodeToString(PlaybackState.serializer(), newState)
        assertTrue("{\"type\":\"Explicit\",\"song\":" in text)
        assertTrue("{\"type\":\"FromSource\",\"songId\":" in text)
        assertTrue("\"shuffleMode\":true" in text)
        assertTrue("\"musicbrainzId\":" in text)
        assertFalse("WithSong" in text)
        assertFalse("isShuffled" in text)
        assertFalse("[\"Explicit\"" in text)
    }

    @Test
    fun `a renamed field inside a subclass without a rename is read under its old name`() {
        val old = OldClientJson.encodeToString(OldQueueWriteResult.serializer(), OldQueueWriteResult.Conflict(oldInfo))
        assertTrue("\"shuffleMode\":true" in old)
        assertEquals(QueueWriteResult.Conflict(newInfo), legacy.decodeFromString(QueueWriteResult.serializer(), old))
    }

    @Test
    fun `hand written legacy json with unknown keys and the type last is accepted`() {
        val text = """
            {"queue":[
               {"songId":"00000000-0000-0000-0000-000000000011","queueId":2,"type":"FromSource"},
               {"song":{"id":"00000000-0000-0000-0000-000000000010","title":"Bare","artists":[],"album":null,"duration":100,"explicit":false,"path":"/b.flac"},"queueId":3,"type":"Explicit"}
             ],"currentIndex":0,"isPlaying":false,"positionMs":5,"shuffleMode":true,"repeatMode":"OFF","bogus":{"a":[1]}}
        """.trimIndent()
        val decoded = legacy.decodeFromString(PlaybackState.serializer(), text)
        assertTrue(decoded.isShuffled)
        assertTrue(decoded.queue[0] is PlaybackState.QueueEntry.FromSource)
        val withSong = decoded.queue[1]
        assertTrue(withSong is PlaybackState.QueueEntry.WithSong && withSong.song.title == "Bare")
    }

    @Test
    fun `tidal attributes keep their json encoding through the legacy wrapper`() {
        val attributes = listOf<BaseAttributes>(ResourceIdentifier("1", "tracks"), ResourceIdentifier("2", "albums"))
        val serializer = ListSerializer(BaseAttributes.serializer())
        val text = legacy.encodeToString(serializer, attributes)
        assertEquals(AppJson.encodeToString(serializer, attributes), text)
        assertEquals(attributes, legacy.decodeFromString(serializer, text))

        val raw = listOf(
            JsonAttribute(buildJsonObject {
                put("k", 1)
                put("arr", JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(true))))
            }),
            null,
        )
        val rawSerializer = ListSerializer(JsonAttributeSerializer.nullable)
        val rawText = legacy.encodeToString(rawSerializer, raw)
        assertEquals(AppJson.encodeToString(rawSerializer, raw), rawText)
        assertEquals(raw, legacy.decodeFromString(rawSerializer, rawText))
        assertEquals(raw, legacy.decodeFromJsonElement(rawSerializer, AppJson.parseToJsonElement(rawText)))
    }
}
