package dev.dertyp.services.podcast

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PodcastEpisodeProgressTable
import dev.dertyp.db.PodcastEpisodeTable
import dev.dertyp.db.PodcastShowTable
import dev.dertyp.db.PodcastSubscriptionTable
import dev.dertyp.db.PodcastTranscriptTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.ImageService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class PodcastFeedServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var podcastService: PodcastService
    private lateinit var feedService: PodcastFeedService
    private lateinit var imageService: ImageService

    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val artworkId = UUID.randomUUID()

    private var respondTo: MockRequestHandler = { respondError(HttpStatusCode.NotFound) }

    private val feedAddress = "https://example.com/feed.xml"
    private val coverAddress = "https://example.com/cover.jpg"

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        database = TestDatabase.connect(dialect, "podcast_feed_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                PodcastShowTable,
                PodcastEpisodeTable,
                PodcastTranscriptTable,
                PodcastSubscriptionTable,
                PodcastEpisodeProgressTable,
            )
            ImageTable.insert {
                it[id] = artworkId
                it[path] = "images/artwork.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
            }
        }

        val http = spyk(PodcastHttp())
        every { http.feedClient } returns HttpClient(
            MockEngine { request ->
                requests += request
                respondTo(this, request)
            }
        )

        imageService = mockk(relaxed = true)
        coEvery { imageService.createImage(any(), any()) } returns artworkId

        podcastService = PodcastService(http)
        feedService = PodcastFeedService(podcastService, imageService, http)
    }

    @AfterEach
    fun tearDown() {
        requests.clear()
        respondTo = { respondError(HttpStatusCode.NotFound) }
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertUser(): UUID = transaction(database) {
        val user = UUID.randomUUID()
        UserTable.insert {
            it[id] = user
            it[username] = "user_$user"
            it[passwordHash] = "hash"
        }
        user
    }

    private fun ageShow(show: UUID, lastFetched: Long) {
        transaction(database) {
            PodcastShowTable.update({ PodcastShowTable.id eq show }) {
                it[lastFetchedAt] = lastFetched
            }
        }
    }

    private fun feedXml(
        title: String = "Example Show",
        image: String? = coverAddress,
        newFeedUrl: String? = null,
        items: List<String> = listOf(item("guid-1", "First Episode", "https://example.com/1.mp3")),
    ) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
          <channel>
            <title>$title</title>
            <description>Example description</description>
            <link>https://example.com/show</link>
            <language>en-us</language>
            <itunes:author>Example Author</itunes:author>
            ${image?.let { "<itunes:image href=\"$it\"/>" } ?: ""}
            ${newFeedUrl?.let { "<itunes:new-feed-url>$it</itunes:new-feed-url>" } ?: ""}
            ${items.joinToString("\n")}
          </channel>
        </rss>
    """.trimIndent()

    private fun item(guid: String, title: String, url: String) = """
        <item>
          <guid>$guid</guid>
          <title>$title</title>
          <pubDate>Tue, 10 Sep 2024 12:00:00 GMT</pubDate>
          <itunes:duration>00:10:00</itunes:duration>
          <enclosure url="$url" type="audio/mpeg" length="4242"/>
        </item>
    """.trimIndent()

    private fun xmlHeaders(etag: String? = null, lastModified: String? = null) = headersOf(
        *buildList {
            add(HttpHeaders.ContentType to listOf("application/rss+xml"))
            if (etag != null) add(HttpHeaders.ETag to listOf(etag))
            if (lastModified != null) add(HttpHeaders.LastModified to listOf(lastModified))
        }.toTypedArray()
    )

    private fun servingFeed(
        body: String,
        etag: String? = null,
        lastModified: String? = null,
    ): MockRequestHandler = { request ->
        when (request.url.encodedPath) {
            "/feed.xml" -> respond(body, HttpStatusCode.OK, xmlHeaders(etag, lastModified))
            "/cover.jpg" -> respond(
                byteArrayOf(1, 2, 3, 4),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "image/jpeg")
            )
            else -> respondError(HttpStatusCode.NotFound)
        }
    }

    private fun requestCount(path: String) = requests.count { it.url.encodedPath == path }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `subscribe creates the show with metadata episodes artwork and the subscription`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml(), etag = "\"v1\"", lastModified = "Tue, 10 Sep 2024 12:00:00 GMT")

        val show = feedService.subscribe(userId, feedAddress)

        assertEquals("Example Show", show.title)
        assertEquals("Example description", show.description)
        assertEquals("Example Author", show.author)
        assertEquals("en-us", show.language)
        assertEquals(feedAddress, show.feedUrl)
        assertEquals(artworkId, show.imageId)
        assertTrue(show.subscribed)

        val row = storedShow(show.id)
        assertEquals("\"v1\"", row.etag)
        assertEquals("Tue, 10 Sep 2024 12:00:00 GMT", row.lastModified)
        assertEquals(coverAddress, row.imageUrl)
        assertNull(row.lastFetchError)
        assertNotNull(row.lastFetchedAt)

        val episodes = podcastService.episodesOfShow(show.id)
        assertEquals(listOf("guid-1"), episodes.map { it.guid })
        assertEquals("First Episode", episodes.single().title)
        assertEquals(600_000L, episodes.single().durationMs)
        assertEquals("https://example.com/1.mp3", episodes.single().enclosureUrl)

        assertEquals(1, requestCount("/feed.xml"))
        assertEquals(1, requestCount("/cover.jpg"))
        assertEquals(listOf(show.id), podcastService.getSubscriptions(userId).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second subscriber reuses the show without fetching again`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val first = insertUser()
        val second = insertUser()
        respondTo = servingFeed(feedXml())

        val created = feedService.subscribe(first, feedAddress)
        val requestsAfterFirst = requests.size

        val reused = feedService.subscribe(second, "  $feedAddress#part-2  ")

        assertEquals(created.id, reused.id)
        assertEquals(requestsAfterFirst, requests.size)
        assertTrue(reused.subscribed)
        assertEquals(listOf(created.id), podcastService.getSubscriptions(second).map { it.id })
        assertEquals(1, podcastService.browseShows(first, "", 0, 50).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `subscribe rejects unsupported schemes oversized urls and unreachable feeds`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = { respondError(HttpStatusCode.NotFound) }

        assertThrows<IllegalArgumentException> {
            runBlocking { feedService.subscribe(userId, "ftp://example.com/feed.xml") }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { feedService.subscribe(userId, "https://example.com/" + "a".repeat(2100)) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { feedService.subscribe(userId, feedAddress) }
        }

        assertEquals(0, podcastService.browseShows(userId, "", 0, 50).total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshShow sends conditional headers and only touches lastFetchedAt on 304`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml(), etag = "\"v1\"", lastModified = "Tue, 10 Sep 2024 12:00:00 GMT")
        val show = feedService.subscribe(userId, feedAddress)
        val before = storedShow(show.id)
        ageShow(show.id, System.currentTimeMillis() - 600_000L)

        requests.clear()
        respondTo = { respond("", HttpStatusCode.NotModified) }

        val outcome = feedService.refreshShow(show.id)

        assertFalse(outcome.changed)
        assertNull(outcome.error)
        assertEquals(0, outcome.inserted)
        assertEquals(0, outcome.updated)

        val conditional = requests.single()
        assertEquals("\"v1\"", conditional.headers[HttpHeaders.IfNoneMatch])
        assertEquals("Tue, 10 Sep 2024 12:00:00 GMT", conditional.headers[HttpHeaders.IfModifiedSince])

        val after = storedShow(show.id)
        assertEquals("\"v1\"", after.etag)
        assertNull(after.lastFetchError)
        assertTrue((after.lastFetchedAt ?: 0) >= (before.lastFetchedAt ?: 0))
        assertEquals(1, podcastService.episodesOfShow(show.id).size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshShow inserts new episodes and stores the new etag`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml(), etag = "\"v1\"")
        val show = feedService.subscribe(userId, feedAddress)
        ageShow(show.id, System.currentTimeMillis() - 600_000L)

        respondTo = servingFeed(
            feedXml(
                items = listOf(
                    item("guid-1", "First Episode", "https://example.com/1.mp3"),
                    item("guid-2", "Second Episode", "https://example.com/2.mp3"),
                )
            ),
            etag = "\"v2\""
        )

        val outcome = feedService.refreshShow(show.id)

        assertTrue(outcome.changed)
        assertEquals(1, outcome.inserted)
        assertEquals(1, outcome.updated)
        assertNull(outcome.error)

        val episodes = podcastService.episodesOfShow(show.id)
        assertEquals(setOf("guid-1", "guid-2"), episodes.map { it.guid }.toSet())
        assertEquals("\"v2\"", storedShow(show.id).etag)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failing origin is recorded as lastFetchError without throwing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml(), etag = "\"v1\"")
        val show = feedService.subscribe(userId, feedAddress)
        ageShow(show.id, System.currentTimeMillis() - 600_000L)

        respondTo = { respondError(HttpStatusCode.ServiceUnavailable) }

        val outcome = feedService.refreshShow(show.id)

        assertFalse(outcome.changed)
        assertNotNull(outcome.error)
        assertTrue(outcome.error!!.contains("503"), "Unexpected error: ${outcome.error}")

        val row = storedShow(show.id)
        assertNotNull(row.lastFetchError)
        assertTrue(row.lastFetchError!!.contains("503"))
        assertEquals("\"v1\"", row.etag)
        assertEquals(1, podcastService.episodesOfShow(show.id).size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an oversized feed body is recorded as a size error`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml())
        val show = feedService.subscribe(userId, feedAddress)
        ageShow(show.id, System.currentTimeMillis() - 600_000L)

        val oversized = ByteArray((PodcastFeedService.MAX_FEED_BYTES + 1024).toInt())
        respondTo = {
            respond(
                oversized,
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentLength, oversized.size.toString())
            )
        }

        val outcome = feedService.refreshShow(show.id)

        assertNotNull(outcome.error)
        assertTrue(outcome.error!!.contains("larger"), "Unexpected error: ${outcome.error}")
        val row = storedShow(show.id)
        assertTrue(row.lastFetchError!!.contains("larger"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `artwork is only fetched again when the image url changed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml())
        val show = feedService.subscribe(userId, feedAddress)
        assertEquals(1, requestCount("/cover.jpg"))

        ageShow(show.id, System.currentTimeMillis() - 600_000L)
        feedService.refreshShow(show.id)
        assertEquals(1, requestCount("/cover.jpg"))

        ageShow(show.id, System.currentTimeMillis() - 600_000L)
        respondTo = { request ->
            when (request.url.encodedPath) {
                "/feed.xml" -> respond(
                    feedXml(image = "https://example.com/cover2.jpg"),
                    HttpStatusCode.OK,
                    xmlHeaders()
                )

                else -> respond(
                    byteArrayOf(9, 9, 9),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "image/jpeg")
                )
            }
        }
        feedService.refreshShow(show.id)

        assertEquals(1, requestCount("/cover2.jpg"))
        assertEquals("https://example.com/cover2.jpg", storedShow(show.id).imageUrl)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a new feed url from the feed replaces the stored feed url`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml())
        val show = feedService.subscribe(userId, feedAddress)
        ageShow(show.id, System.currentTimeMillis() - 600_000L)

        respondTo = servingFeed(feedXml(newFeedUrl = "https://example.com/moved.xml"))
        feedService.refreshShow(show.id)

        val row = storedShow(show.id)
        assertEquals("https://example.com/moved.xml", row.feedUrl)
        assertEquals(PodcastKeys.feedSourceKey("https://example.com/moved.xml"), row.sourceKey)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `refreshShow is skipped within the minimum interval unless forced`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = insertUser()
        respondTo = servingFeed(feedXml())
        val show = feedService.subscribe(userId, feedAddress)
        requests.clear()

        val skipped = feedService.refreshShow(show.id)
        assertFalse(skipped.changed)
        assertNull(skipped.error)
        assertEquals(0, requests.size)

        val forced = feedService.refreshShow(show.id, force = true)
        assertTrue(forced.changed)
        assertEquals(1, requestCount("/feed.xml"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `normalizeFeedUrl trims drops fragments and rejects unsupported urls`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertEquals(feedAddress, feedService.normalizeFeedUrl("  $feedAddress  "))
        assertEquals(feedAddress, feedService.normalizeFeedUrl("$feedAddress#section"))
        assertEquals(
            "https://example.com/feed.xml?page=2",
            feedService.normalizeFeedUrl("https://example.com/feed.xml?page=2#top")
        )
        assertThrows<IllegalArgumentException> { feedService.normalizeFeedUrl("   ") }
        assertThrows<IllegalArgumentException> { feedService.normalizeFeedUrl("ftp://example.com/feed.xml") }
        assertThrows<IllegalArgumentException> {
            feedService.normalizeFeedUrl("https://example.com/" + "a".repeat(2100))
        }
    }

    private suspend fun storedShow(showId: UUID): PodcastShowRow =
        requireNotNull(podcastService.showById(showId)) { "Podcast show $showId is missing" }
}
