package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.PluginSettings
import dev.dertyp.services.podcast.PodcastHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PodcastIndexOrgIndexTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var respondTo: MockRequestHandler = { respondError(HttpStatusCode.NotFound) }

    private val settings = mockk<PluginSettings>()
    private val config = MapApplicationConfig()
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC)

    private fun index(): PodcastIndexOrgIndex {
        val engine = MockEngine { request ->
            requests += request
            respondTo(this, request)
        }
        return PodcastIndexOrgIndex(
            credentials = PodcastIndexCredentialSource(settings, config),
            client = podcastIndexHttpClient(engine),
            clock = clock,
            baseUrl = "https://index.test/api/1.0",
        )
    }

    private fun json(body: String): MockRequestHandler = {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun configured() {
        coEvery { settings.getAll() } returns mapOf(
            PodcastIndexCredentialSource.KEY_API_KEY to "key123",
            PodcastIndexCredentialSource.KEY_API_SECRET to "secret456",
        )
    }

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `signs the request and maps a full feed`() = runBlocking {
        configured()
        respondTo = json(
            """
            {
              "status": "true",
              "count": 1,
              "description": "Found matching feeds.",
              "feeds": [
                {
                  "id": 42,
                  "title": "Deep Space",
                  "url": "https://example.com/feed.xml",
                  "originalUrl": "https://example.com/original.xml",
                  "link": "https://example.com",
                  "description": "Space talk",
                  "author": "Ada",
                  "ownerName": "Owner",
                  "image": "https://example.com/image.jpg",
                  "artwork": "https://example.com/artwork.jpg",
                  "language": "en-us",
                  "explicit": true,
                  "episodeCount": 12,
                  "newestItemPubdate": 1699999999,
                  "categories": { "9": "Science", "107": "Astronomy", "8": "   " }
                }
              ]
            }
            """.trimIndent()
        )

        val entries = index().search("space", 25)

        val request = requests.single()
        assertEquals("/api/1.0/search/byterm", request.url.encodedPath)
        assertEquals("space", request.url.parameters["q"])
        assertEquals("25", request.url.parameters["max"])
        assertTrue(request.url.parameters.contains("fulltext"))
        assertEquals(PodcastHttp.USER_AGENT, request.headers[HttpHeaders.UserAgent])
        assertEquals("key123", request.headers["X-Auth-Key"])
        assertEquals("1700000000", request.headers["X-Auth-Date"])
        assertEquals(sha1Hex("key123secret4561700000000"), request.headers[HttpHeaders.Authorization])

        val entry = entries.single()
        assertEquals("https://example.com/feed.xml", entry.feedUrl)
        assertEquals("Deep Space", entry.title)
        assertEquals("Space talk", entry.description)
        assertEquals("Ada", entry.author)
        assertEquals("https://example.com/artwork.jpg", entry.imageUrl)
        assertEquals("https://example.com", entry.link)
        assertEquals("en-us", entry.language)
        assertEquals(12, entry.episodeCount)
        assertEquals(1699999999000L, entry.lastPublishedAt)
        assertTrue(entry.explicit)
        assertEquals(listOf("Science", "Astronomy"), entry.categories)
    }

    @Test
    fun `falls back to image and owner name and skips unusable feeds`() = runBlocking {
        configured()
        respondTo = json(
            """
            {
              "status": "true",
              "feeds": [
                { "title": "Fallbacks", "url": "https://example.com/a.xml", "ownerName": "Owner", "image": "https://example.com/image.jpg", "author": "  ", "artwork": "", "language": "  ", "newestItemPubdate": 0 },
                { "title": "No feed url", "url": "   " },
                { "title": "   ", "url": "https://example.com/b.xml" }
              ]
            }
            """.trimIndent()
        )

        val entry = index().search("fallback", 10).single()
        assertEquals("https://example.com/a.xml", entry.feedUrl)
        assertEquals("Owner", entry.author)
        assertEquals("https://example.com/image.jpg", entry.imageUrl)
        assertEquals("", entry.description)
        assertNull(entry.language)
        assertNull(entry.link)
        assertNull(entry.lastPublishedAt)
        assertNull(entry.episodeCount)
        assertTrue(entry.categories.isEmpty())
    }

    @Test
    fun `tolerates unknown fields and an empty categories array`() = runBlocking {
        configured()
        respondTo = json(
            """
            {
              "status": "true",
              "somethingNew": { "nested": [1, 2] },
              "feeds": [
                { "title": "Empty categories", "url": "https://example.com/a.xml", "categories": [], "crawlErrors": 0, "extra": "x" }
              ]
            }
            """.trimIndent()
        )

        val entry = index().search("empty", 10).single()
        assertEquals("Empty categories", entry.title)
        assertTrue(entry.categories.isEmpty())
    }

    @Test
    fun `returns nothing and sends no request when unconfigured`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()
        val index = index()
        assertTrue(index.search("space", 10).isEmpty())
        assertTrue(requests.isEmpty())
        assertTrue(!index.isConfigured())
    }

    @Test
    fun `is configured once credentials exist`() = runBlocking {
        configured()
        assertTrue(index().isConfigured())
    }

    @Test
    fun `throws on an error status code`() = runBlocking {
        configured()
        respondTo = { respondError(HttpStatusCode.Unauthorized) }
        val failure = assertThrows<PodcastIndexException> { runBlocking { index().search("space", 10) } }
        assertEquals(401, failure.status)
        assertEquals("Podcast Index answered 401", failure.message)
    }

    @Test
    fun `throws when the payload reports a failure`() = runBlocking {
        configured()
        respondTo = json("""{ "status": "false", "description": "Bad authorization", "feeds": [] }""")
        val failure = assertThrows<PodcastIndexException> { runBlocking { index().search("space", 10) } }
        assertNull(failure.status)
        assertEquals("Bad authorization", failure.message)

        respondTo = json("""{ "status": "false", "feeds": [] }""")
        val generic = assertThrows<PodcastIndexException> { runBlocking { index().search("space", 10) } }
        assertEquals("Podcast Index reported an error", generic.message)
    }
}
