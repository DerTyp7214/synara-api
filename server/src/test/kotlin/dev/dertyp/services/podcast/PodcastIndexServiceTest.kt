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
import dev.dertyp.plugins.IPodcastIndex
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.PodcastIndexEntry
import dev.dertyp.services.ImageService
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

class PodcastIndexServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var podcastService: PodcastService
    private lateinit var feedService: PodcastFeedService
    private lateinit var service: PodcastIndexService

    private val pluginManager = mockk<PluginManager>()

    private class FakeIndex(
        override val id: String,
        override val name: String,
        private val configured: Boolean = true,
        private val entries: List<PodcastIndexEntry> = emptyList(),
        private val failWith: Throwable? = null,
        private val configureFailure: Throwable? = null,
        private val answerAfterMs: Long = 0
    ) : IPodcastIndex {
        val searchCalls = CopyOnWriteArrayList<Pair<String, Int>>()

        override suspend fun isConfigured(): Boolean {
            configureFailure?.let { throw it }
            return configured
        }

        override suspend fun search(query: String, limit: Int): List<PodcastIndexEntry> {
            searchCalls += query to limit
            if (answerAfterMs > 0) delay(answerAfterMs)
            failWith?.let { throw it }
            return entries
        }
    }

    private fun setup(dialect: DbDialect, registered: List<IPodcastIndex>) {
        startKoin { modules(module { }) }

        database = TestDatabase.connect(dialect, "podcast_index_test")
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
        }

        val http = spyk(PodcastHttp())
        val imageService = mockk<ImageService>(relaxed = true)

        podcastService = PodcastService(http)
        feedService = PodcastFeedService(podcastService, imageService, http)

        every { pluginManager.getPodcastIndexes() } returns registered

        service = PodcastIndexService(pluginManager, podcastService, feedService)
        service.providerTimeout = 300.milliseconds
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun entry(url: String, showTitle: String = "Show") = PodcastIndexEntry(feedUrl = url, title = showTitle)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a blank search term is rejected`(dialect: DbDialect) {
        setup(dialect, listOf(FakeIndex("a", "A", entries = listOf(entry("https://example.com/a.xml")))))

        assertThrows<IllegalArgumentException> { runBlocking { service.search("   ", 10, emptyList()) } }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the limit is clamped to the allowed range`(dialect: DbDialect): Unit = runBlocking {
        val index = FakeIndex("a", "A", entries = listOf(entry("https://example.com/a.xml")))
        setup(dialect, listOf(index))

        service.search("q", 500, emptyList())
        service.search("q", 0, emptyList())

        assertEquals(listOf("q" to PodcastIndexService.MAX_LIMIT, "q" to 1), index.searchCalls.toList())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unconfigured indexes are skipped`(dialect: DbDialect): Unit = runBlocking {
        val unconfigured = FakeIndex("a", "A", configured = false, entries = listOf(entry("https://example.com/a.xml")))
        val broken = FakeIndex("b", "B", configureFailure = IllegalStateException("no token"), entries = listOf(entry("https://example.com/b.xml")))
        setup(dialect, listOf(unconfigured, broken))

        assertEquals(emptyList<String>(), service.search("q", 10, emptyList()).map { it.feedUrl })
        assertTrue(unconfigured.searchCalls.isEmpty())
        assertTrue(broken.searchCalls.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an unknown index id is rejected`(dialect: DbDialect) {
        setup(dialect, listOf(FakeIndex("a", "A")))

        val error = assertThrows<IllegalArgumentException> { runBlocking { service.search("q", 10, listOf("a", "nope")) } }
        assertEquals("Unknown podcast index: nope", error.message)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the indexes filter restricts and orders the providers`(dialect: DbDialect): Unit = runBlocking {
        val first = FakeIndex("a", "A", entries = listOf(entry("https://example.com/a.xml")))
        val second = FakeIndex("b", "B", entries = listOf(entry("https://example.com/b.xml")))
        setup(dialect, listOf(first, second))

        val ordered = service.search("q", 10, listOf("b", "a"))
        assertEquals(listOf("b", "a"), ordered.map { it.indexId })

        val restricted = service.search("q", 10, listOf("b"))
        assertEquals(listOf("https://example.com/b.xml"), restricted.map { it.feedUrl })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failing provider contributes nothing while the other one answers`(dialect: DbDialect): Unit = runBlocking {
        val failing = FakeIndex("a", "A", failWith = IllegalStateException("boom"), entries = listOf(entry("https://example.com/a.xml")))
        val working = FakeIndex("b", "B", entries = listOf(entry("https://example.com/b.xml")))
        setup(dialect, listOf(failing, working))

        assertEquals(listOf("https://example.com/b.xml"), service.search("q", 10, emptyList()).map { it.feedUrl })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a provider that exceeds the timeout is dropped`(dialect: DbDialect): Unit = runBlocking {
        val slow = FakeIndex("a", "A", entries = listOf(entry("https://example.com/a.xml")), answerAfterMs = 2000)
        val fast = FakeIndex("b", "B", entries = listOf(entry("https://example.com/b.xml")))
        setup(dialect, listOf(slow, fast))

        assertEquals(listOf("https://example.com/b.xml"), service.search("q", 10, emptyList()).map { it.feedUrl })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `results are deduplicated by the normalized feed url`(dialect: DbDialect): Unit = runBlocking {
        val first = FakeIndex("a", "A", entries = listOf(entry("https://example.com/same.xml")))
        val second = FakeIndex("b", "B", entries = listOf(entry("https://example.com/same.xml#x"), entry("https://example.com/other.xml")))
        setup(dialect, listOf(first, second))

        val results = service.search("q", 10, emptyList())

        assertEquals(listOf("a", "b"), results.map { it.indexId })
        assertEquals(
            listOf(feedService.normalizeFeedUrl("https://example.com/same.xml"), "https://example.com/other.xml"),
            results.map { it.feedUrl }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `entries with an unusable feed url or a blank title are dropped`(dialect: DbDialect): Unit = runBlocking {
        val index = FakeIndex(
            "a",
            "A",
            entries = listOf(
                entry("ftp://example.com/a.xml"),
                entry("  "),
                entry("https://example.com/blank.xml", showTitle = "   "),
                entry("https://example.com/good.xml"),
            )
        )
        setup(dialect, listOf(index))

        assertEquals(listOf("https://example.com/good.xml"), service.search("q", 10, emptyList()).map { it.feedUrl })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `feeds the server already follows are left out`(dialect: DbDialect): Unit = runBlocking {
        val known = "https://example.com/known.xml"
        val index = FakeIndex("a", "A", entries = listOf(entry(known), entry("https://example.com/new.xml")))
        setup(dialect, listOf(index))

        val normalized = feedService.normalizeFeedUrl(known)
        podcastService.createFeedShow(
            feedUrl = normalized,
            sourceKey = PodcastKeys.feedSourceKey(normalized),
            parsed = ParsedShow(title = "Known Show"),
            imageId = null,
            imageUrl = null
        )

        assertEquals(listOf("https://example.com/new.xml"), service.search("q", 10, emptyList()).map { it.feedUrl })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `indexes lists every provider with its configured flag`(dialect: DbDialect): Unit = runBlocking {
        setup(dialect, listOf(FakeIndex("a", "A"), FakeIndex("b", "B", configured = false)))

        val infos = service.indexes()

        assertEquals(listOf("a", "b"), infos.map { it.id })
        assertEquals(listOf("A", "B"), infos.map { it.name })
        assertTrue(infos[0].configured)
        assertFalse(infos[1].configured)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the mapping keeps the entry fields and names the index`(dialect: DbDialect): Unit = runBlocking {
        val found = PodcastIndexEntry(
            feedUrl = "https://example.com/full.xml#frag",
            title = "Full Show",
            description = "About things",
            author = "Author",
            imageUrl = "https://example.com/cover.jpg",
            link = "https://example.com/show",
            language = "de-DE",
            episodeCount = 42,
            lastPublishedAt = 1_700_000_000_000,
            explicit = true,
            categories = listOf("Technology", "News"),
        )
        setup(dialect, listOf(FakeIndex("a", "Index A", entries = listOf(found))))

        val result = service.search("q", 10, emptyList()).single()

        assertEquals("a", result.indexId)
        assertEquals("Index A", result.indexName)
        assertEquals(feedService.normalizeFeedUrl(found.feedUrl), result.feedUrl)
        assertEquals(found.title, result.title)
        assertEquals(found.description, result.description)
        assertEquals(found.author, result.author)
        assertEquals(found.imageUrl, result.imageUrl)
        assertEquals(found.link, result.link)
        assertEquals(found.language, result.language)
        assertEquals(found.episodeCount, result.episodeCount)
        assertEquals(found.lastPublishedAt, result.lastPublishedAt)
        assertTrue(result.explicit)
        assertEquals(found.categories, result.categories)
    }
}
