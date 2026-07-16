package dev.dertyp.services.subsonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsonicEnvelopeTest {
    @Test
    fun `ok ping renders as xml attributes with namespace`() {
        val xml = SubsonicResponse().toXmlString()
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<subsonic-response xmlns=\"http://subsonic.org/restapi\""))
        assertTrue(xml.contains("status=\"ok\""))
        assertTrue(xml.contains("version=\"$SUBSONIC_API_VERSION\""))
        assertTrue(xml.contains("type=\"synara\""))
        assertTrue(xml.contains("openSubsonic=\"true\""))
        assertFalse(xml.contains("<error"))
    }

    @Test
    fun `error envelope carries code and message`() {
        val xml = subsonicError(40, "Wrong username or password").toXmlString()
        assertTrue(xml.contains("status=\"failed\""))
        assertTrue(xml.contains("<error"))
        assertTrue(xml.contains("code=\"40\""))
        assertTrue(xml.contains("message=\"Wrong username or password\""))

        val json = Json.parseToJsonElement(subsonicError(40, "Wrong username or password").toJsonString())
        val response = json.jsonObject["subsonic-response"]!!.jsonObject
        assertEquals("failed", response["status"]!!.jsonPrimitive.content)
        assertEquals(40, response["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `json envelope omits nulls and keeps subsonic-response key`() {
        val json = Json.parseToJsonElement(SubsonicResponse().toJsonString())
        val response = json.jsonObject["subsonic-response"]!!.jsonObject
        assertEquals("ok", response["status"]!!.jsonPrimitive.content)
        assertTrue(response["openSubsonic"]!!.jsonPrimitive.boolean)
        assertNull(response["error"])
        assertNull(response["album"])
    }

    @Test
    fun `album payload places scalars as attributes and songs as elements`() {
        val response = SubsonicResponse(
            album = AlbumWithSongsID3(
                id = "al-1",
                name = "Test <Album> & Friends",
                artist = "Tester",
                songCount = 1,
                duration = 200,
                year = 2001,
                song = listOf(Child(id = "tr-1", title = "Song \"One\"", duration = 120)),
            ),
        )

        val xml = response.toXmlString()
        assertTrue(xml.contains("<album"))
        assertTrue(xml.contains("name=\"Test &lt;Album"))
        assertTrue(xml.contains("&amp; Friends\""))
        assertTrue(xml.contains("year=\"2001\""))
        assertTrue(xml.contains("<song"))
        assertTrue(xml.contains("title='Song \"One\"'") || xml.contains("title=\"Song &quot;One&quot;\""))

        val json = Json.parseToJsonElement(response.toJsonString())
        val album = json.jsonObject["subsonic-response"]!!.jsonObject["album"]!!.jsonObject
        assertEquals("Test <Album> & Friends", album["name"]!!.jsonPrimitive.content)
        assertEquals(1, album["song"]!!.jsonArray.size)
        assertEquals("tr-1", album["song"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `genre value is xml text content`() {
        val xml = SubsonicResponse(
            genres = Genres(listOf(GenreDto(songCount = 3, albumCount = 1, value = "Electronic"))),
        ).toXmlString()
        assertTrue(xml.contains("songCount=\"3\""))
        assertTrue(xml.contains(">Electronic</genre>"))
    }
}
