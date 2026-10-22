@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire

import dev.dertyp.core.wire.fixtures.*
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.serializers.AppCbor
import dev.dertyp.serializers.AppJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.modules.plus
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class LegacyWireFieldTest {
    private val newSerializer = ListSerializer(WireLyricsSong.serializer())
    private val oldSerializer = ListSerializer(OldWireLyricsSong.serializer())
    private val newPage = PaginatedResponse(newLyricsSongs, total = 2)
    private val oldPage = PaginatedResponse(oldLyricsSongs, total = 2)

    private fun json(wire: LegacyWire) =
        WireJson(Json(AppJson) { serializersModule = AppJson.serializersModule + wire.module }, wire)

    private fun cbor(wire: LegacyWire) =
        LegacyCborFormat(Cbor(AppCbor) { serializersModule = AppCbor.serializersModule + wire.module }, wire)

    @Test
    fun `renames and the extra field together give exactly the old client json`() {
        val legacy = json(FixtureRenamesAndLyricsWire)
        assertEquals(AppJson.encodeToString(oldSerializer, oldLyricsSongs), legacy.encodeToString(newSerializer, newLyricsSongs))
        assertEquals(
            AppJson.encodeToString(PaginatedResponse.serializer(OldWireLyricsSong.serializer()), oldPage),
            legacy.encodeToString(PaginatedResponse.serializer(WireLyricsSong.serializer()), newPage),
        )
    }

    @Test
    fun `renames and the extra field together are byte identical to the old client cbor`() {
        val legacy = cbor(FixtureRenamesAndLyricsWire)
        assertArrayEquals(
            AppCbor.encodeToByteArray(oldSerializer, oldLyricsSongs),
            legacy.encodeToByteArray(newSerializer, newLyricsSongs),
        )
        assertArrayEquals(
            AppCbor.encodeToByteArray(PaginatedResponse.serializer(OldWireLyricsSong.serializer()), oldPage),
            legacy.encodeToByteArray(PaginatedResponse.serializer(WireLyricsSong.serializer()), newPage),
        )
    }

    @Test
    fun `old clients decode the extra field`() {
        val text = json(FixtureRenamesAndLyricsWire).encodeToString(newSerializer, newLyricsSongs)
        assertEquals(oldLyricsSongs, AppJson.decodeFromString(oldSerializer, text))
        val bytes = cbor(FixtureRenamesAndLyricsWire).encodeToByteArray(newSerializer, newLyricsSongs)
        assertEquals(oldLyricsSongs, AppCbor.decodeFromByteArray(oldSerializer, bytes))
    }

    @Test
    fun `the server decodes payloads that send the extra field back`() {
        listOf(FixtureRenamesAndLyricsWire, FixtureLyricsOnlyWire).forEach { wire ->
            val text = json(wire).encodeToString(newSerializer, newLyricsSongs)
            assertTrue("\"lyrics\":\"First line" in text)
            assertEquals(newLyricsSongs, json(wire).decodeFromString(newSerializer, text))
            assertEquals(newLyricsSongs, json(wire).decodeFromJsonElement(newSerializer, AppJson.parseToJsonElement(text)))
            val bytes = cbor(wire).encodeToByteArray(newSerializer, newLyricsSongs)
            assertEquals(newLyricsSongs, cbor(wire).decodeFromByteArray(newSerializer, bytes))
        }
        val oldText = AppJson.encodeToString(oldSerializer, oldLyricsSongs)
        assertEquals(newLyricsSongs, json(FixtureRenamesAndLyricsWire).decodeFromString(newSerializer, oldText))
        val oldBytes = AppCbor.encodeToByteArray(oldSerializer, oldLyricsSongs)
        assertEquals(newLyricsSongs, cbor(FixtureRenamesAndLyricsWire).decodeFromByteArray(newSerializer, oldBytes))
        val lyricsOnlyBytes = cbor(FixtureLyricsOnlyWire).encodeToByteArray(newSerializer, newLyricsSongs)
        assertEquals(newLyricsSongs, AppCbor.decodeFromByteArray(newSerializer, lyricsOnlyBytes))
        val lyricsOnlyText = json(FixtureLyricsOnlyWire).encodeToString(newSerializer, newLyricsSongs)
        assertEquals(newLyricsSongs, AppJson.decodeFromString(newSerializer, lyricsOnlyText))
    }

    @Test
    fun `without renames the extra field is added under the current names`() {
        val text = json(FixtureLyricsOnlyWire).encodeToString(WireLyricsSong.serializer(), newLyricsSongs.first())
        val song = AppJson.parseToJsonElement(text).jsonObject
        assertEquals(JsonPrimitive("First line\nSecond line"), song["lyrics"])
        val album = song.getValue("album").jsonObject
        assertTrue("musicBrainzId" in album)
        assertFalse("musicbrainzId" in album)
        val plain = AppJson.parseToJsonElement(AppJson.encodeToString(WireLyricsSong.serializer(), newLyricsSongs.first()))
        assertEquals(plain.jsonObject, song.filterKeys { it != "lyrics" })
    }

    @Test
    fun `the extra element follows the real elements and decoding keeps the original indices`() {
        val descriptor = WireLyricsSong.serializer().descriptor
        val encode = FixtureLyricsOnlyWire.encodeDescriptor(descriptor)
        assertEquals(descriptor.elementsCount + 1, encode.elementsCount)
        repeat(descriptor.elementsCount) { assertEquals(descriptor.getElementName(it), encode.getElementName(it)) }
        assertEquals("lyrics", encode.getElementName(descriptor.elementsCount))
        assertSame(descriptor, FixtureLyricsOnlyWire.wireDescriptor(descriptor))
        assertSame(encode, FixtureLyricsOnlyWire.encodeDescriptor(descriptor))
        val renamed = FixtureRenamesAndLyricsWire.wireDescriptor(WireAlbum.serializer().descriptor)
        assertEquals("musicbrainzId", renamed.getElementName(WireAlbum.serializer().descriptor.getElementIndex("musicBrainzId")))
    }

    @Test
    fun `a wire without the field source leaves annotated classes untouched`() {
        val descriptor = WireLyricsSong.serializer().descriptor
        assertSame(descriptor, FixtureWire.encodeDescriptor(descriptor))
        val text = WireJson(FixtureLegacyJson, FixtureWire).encodeToString(newSerializer, newLyricsSongs)
        assertFalse("lyrics" in text)
    }

    @Test
    fun `a field declared with another version than its source is rejected`() {
        val song = WireMisdeclaredLyricsSong(UUID.randomUUID())
        assertThrows<IllegalArgumentException> {
            json(FixtureLyricsOnlyWire).encodeToString(WireMisdeclaredLyricsSong.serializer(), song)
        }
    }
}
