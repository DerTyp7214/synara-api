package dev.dertyp.core

import dev.dertyp.data.ApiVersion
import dev.dertyp.serializers.AppJson
import dev.dertyp.ui.UiSchemaVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class ClientInfoTest {

    @Test
    fun `missing or invalid header resolves to legacy version`() {
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader(null).apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("").apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("abc").apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("0").apiVersion)
        assertEquals(ApiVersion.LEGACY, ClientInfo.fromHeader("-3").apiVersion)
    }

    @Test
    fun `header is parsed as integer`() {
        assertEquals(2, ClientInfo.fromHeader("2").apiVersion)
        assertEquals(7, ClientInfo.fromHeader(" 7 ").apiVersion)
    }

    @Test
    fun `legacy clients do not support wav aiff streaming`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.LOSSLESS_WAV_AIFF))
        assertTrue(ClientInfo(2).supports(ClientFeature.LOSSLESS_WAV_AIFF))
        assertTrue(ClientInfo(99).supports(ClientFeature.LOSSLESS_WAV_AIFF))
    }

    @Test
    fun `dolby atmos requires api version 3`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.DOLBY_ATMOS))
        assertFalse(ClientInfo(2).supports(ClientFeature.DOLBY_ATMOS))
        assertTrue(ClientInfo(3).supports(ClientFeature.DOLBY_ATMOS))
    }

    @Test
    fun `nested audio info requires api version 4`() {
        assertFalse(ClientInfo(3).supports(ClientFeature.AUDIO_INFO))
        assertTrue(ClientInfo(4).supports(ClientFeature.AUDIO_INFO))
    }

    @Test
    fun `ui schema version and locale are parsed from headers`() {
        val client = ClientInfo.fromHeaders("5", "1", "de-AT, de;q=0.9, en;q=0.5")
        assertEquals(5, client.apiVersion)
        assertEquals(1, client.uiSchemaVersion)
        assertEquals("de-at", client.locale)
        assertTrue(client.supportsUiSchema(1))
        assertFalse(client.supportsUiSchema(2))
    }

    @Test
    fun `missing ui headers resolve to no schema and english`() {
        val client = ClientInfo.fromHeaders("5", null, null)
        assertEquals(UiSchemaVersion.NONE, client.uiSchemaVersion)
        assertEquals(ClientInfo.DEFAULT_LOCALE, client.locale)
        assertEquals(UiSchemaVersion.NONE, ClientInfo.fromHeaders("5", "abc", "*").uiSchemaVersion)
        assertEquals(ClientInfo.DEFAULT_LOCALE, ClientInfo.fromHeaders("5", "-1", "*").locale)
    }

    @Test
    fun `a valid time zone header is parsed`() {
        assertEquals(ZoneId.of("Europe/Berlin"), ClientInfo.fromHeaders("5", "2", "de", "Europe/Berlin").timeZone)
        assertEquals(
            ZoneId.of("America/New_York"),
            ClientInfo.fromHeaders("5", "2", "en", " America/New_York ").timeZone
        )
    }

    @Test
    fun `invalid blank or missing time zones resolve to none`() {
        assertNull(ClientInfo.fromHeaders("5", "2", "de", "Mars/Olympus").timeZone)
        assertNull(ClientInfo.fromHeaders("5", "2", "de", "not a zone").timeZone)
        assertNull(ClientInfo.fromHeaders("5", "2", "de", "  ").timeZone)
        assertNull(ClientInfo.fromHeaders("5", "2", "de", null).timeZone)
        assertNull(ClientInfo.fromHeaders("5", "2", "de").timeZone)
    }

    @Test
    fun `accept language picks the highest quality tag`() {
        assertEquals("fr", ClientInfo.parseLocale("en;q=0.3, fr;q=0.8"))
        assertEquals("en", ClientInfo.parseLocale("en, de;q=0.5"))
    }

    @Test
    fun `server driven ui requires api version 5`() {
        assertFalse(ClientInfo(4).supports(ClientFeature.SERVER_DRIVEN_UI))
        assertTrue(ClientInfo(5).supports(ClientFeature.SERVER_DRIVEN_UI))
    }

    @Test
    fun `title tags require api version 6`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.TITLE_TAGS))
        assertFalse(ClientInfo(5).supports(ClientFeature.TITLE_TAGS))
        assertTrue(ClientInfo(6).supports(ClientFeature.TITLE_TAGS))
    }

    @Test
    fun `release versions require api version 7`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.RELEASE_VERSIONS))
        assertFalse(ClientInfo(6).supports(ClientFeature.RELEASE_VERSIONS))
        assertTrue(ClientInfo(7).supports(ClientFeature.RELEASE_VERSIONS))
    }

    @Test
    fun `album versions require api version 9`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.ALBUM_VERSIONS))
        assertFalse(ClientInfo(8).supports(ClientFeature.ALBUM_VERSIONS))
        assertTrue(ClientInfo(9).supports(ClientFeature.ALBUM_VERSIONS))
    }

    @Test
    fun `album title tags require api version 9`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.ALBUM_TITLE_TAGS))
        assertFalse(ClientInfo(8).supports(ClientFeature.ALBUM_TITLE_TAGS))
        assertTrue(ClientInfo(9).supports(ClientFeature.ALBUM_TITLE_TAGS))
    }

    @Test
    fun `field renames require api version 8`() {
        assertFalse(ClientInfo.LEGACY.supports(ClientFeature.FIELD_RENAMES))
        assertFalse(ClientInfo(7).supports(ClientFeature.FIELD_RENAMES))
        assertTrue(ClientInfo(8).supports(ClientFeature.FIELD_RENAMES))
    }

    @Test
    fun `wire formats send old keys only below api version 8`() {
        assertSame(LegacyServerJson, jsonFor(ClientInfo.LEGACY).format)
        assertSame(LegacyServerJson, jsonFor(ClientInfo(7)).format)
        assertSame(AppJson, jsonFor(ClientInfo(8)).format)
        assertSame(AppJson, jsonFor(ClientInfo(ApiVersion.CURRENT)).format)
    }

    @Test
    fun `every feature is available at the current api version`() {
        ClientFeature.entries.forEach { feature ->
            assertTrue(ClientInfo(ApiVersion.CURRENT).supports(feature), "${feature.name} must be supported at CURRENT")
        }
    }
}
