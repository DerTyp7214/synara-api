package dev.dertyp.services.podcast

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.EpisodePlaybackReport
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastEpisodeProgress
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastShowSettings
import dev.dertyp.data.PodcastSource
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PodcastEpisodeProgressTable
import dev.dertyp.db.PodcastEpisodeTable
import dev.dertyp.db.PodcastShowTable
import dev.dertyp.db.PodcastSubscriptionTable
import dev.dertyp.db.PodcastTranscriptTable
import dev.dertyp.db.UserTable
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastServiceTest {
    private lateinit var database: Database
    private lateinit var service: PodcastService

    private fun setup(dialect: DbDialect, handler: MockRequestHandler? = null) {
        database = TestDatabase.connect(dialect, "podcast_service_test")
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
        if (handler != null) {
            every { http.feedClient } returns HttpClient(MockEngine(handler))
            every { http.mediaClient } returns HttpClient(MockEngine(handler))
        }
        service = PodcastService(http)
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    private fun insertUser(name: String = "user_${UUID.randomUUID()}"): UUID {
        val id = UUID.randomUUID()
        UserTable.insert {
            it[UserTable.id] = id
            it[username] = name
            it[passwordHash] = "hash"
        }
        return id
    }

    private fun insertImage(): UUID {
        val id = UUID.randomUUID()
        ImageTable.insert {
            it[ImageTable.id] = id
            it[path] = "/images/$id"
            it[imageHash] = "hash-$id"
            it[origin] = "test"
        }
        return id
    }

    private fun insertFeedShow(
        feedUrl: String,
        title: String = "Show",
        author: String? = null,
        description: String = "",
        imageId: UUID? = null,
        deliveryMode: PodcastDeliveryMode = PodcastDeliveryMode.STREAM,
        keepEpisodes: Int? = null,
        retention: PodcastRetention = PodcastRetention.NEWEST,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().toEpochMilli()
        PodcastShowTable.insert {
            it[PodcastShowTable.id] = id
            it[showSource] = PodcastSource.FEED
            it[sourceKey] = PodcastKeys.feedSourceKey(feedUrl)
            it[PodcastShowTable.feedUrl] = feedUrl
            it[PodcastShowTable.title] = title
            it[PodcastShowTable.description] = description
            it[PodcastShowTable.author] = author
            it[PodcastShowTable.imageId] = imageId
            it[PodcastShowTable.deliveryMode] = deliveryMode
            it[PodcastShowTable.keepEpisodes] = keepEpisodes
            it[PodcastShowTable.retention] = retention
            it[createdAt] = now
            it[updatedAt] = now
        }
        return id
    }

    private fun insertLocalShow(localPath: String, title: String = "Local Show"): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().toEpochMilli()
        PodcastShowTable.insert {
            it[PodcastShowTable.id] = id
            it[showSource] = PodcastSource.LOCAL
            it[sourceKey] = PodcastKeys.localSourceKey(localPath)
            it[PodcastShowTable.localPath] = localPath
            it[PodcastShowTable.title] = title
            it[createdAt] = now
            it[updatedAt] = now
        }
        return id
    }

    private fun insertEpisode(
        showId: UUID,
        guid: String,
        publishedAt: Long,
        title: String = "Episode",
        description: String = "",
        durationMs: Long? = null,
        enclosureUrl: String? = null,
        importState: PodcastImportState = PodcastImportState.NONE,
        importAttempts: Int = 0,
        filePath: String? = null,
        updatedAt: Long? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().toEpochMilli()
        PodcastEpisodeTable.insert {
            it[PodcastEpisodeTable.id] = id
            it[PodcastEpisodeTable.showId] = showId
            it[PodcastEpisodeTable.guid] = guid
            it[guidKey] = PodcastKeys.guidKey(guid)
            it[PodcastEpisodeTable.title] = title
            it[PodcastEpisodeTable.description] = description
            it[PodcastEpisodeTable.publishedAt] = publishedAt
            it[PodcastEpisodeTable.durationMs] = durationMs
            it[PodcastEpisodeTable.enclosureUrl] = enclosureUrl
            it[PodcastEpisodeTable.importState] = importState
            it[PodcastEpisodeTable.importAttempts] = importAttempts
            it[PodcastEpisodeTable.filePath] = filePath
            it[createdAt] = now
            it[PodcastEpisodeTable.updatedAt] = updatedAt ?: now
        }
        return id
    }

    private fun insertTranscript(
        episodeId: UUID,
        sourceKey: String,
        url: String? = null,
        filePath: String? = null,
        type: String = "text/vtt",
        content: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        PodcastTranscriptTable.insert {
            it[PodcastTranscriptTable.id] = id
            it[PodcastTranscriptTable.episodeId] = episodeId
            it[PodcastTranscriptTable.sourceKey] = sourceKey
            it[PodcastTranscriptTable.url] = url
            it[PodcastTranscriptTable.filePath] = filePath
            it[PodcastTranscriptTable.type] = type
            it[PodcastTranscriptTable.content] = content
            it[createdAt] = Instant.now().toEpochMilli()
        }
        return id
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `subscribeToShow is idempotent and tracks subscribed and subscriberCount per user, unknown show throws`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/a") }

        service.subscribeToShow(userA, showId)
        val show = service.subscribeToShow(userA, showId)

        assertTrue(show.subscribed)
        assertEquals(1, show.subscriberCount)

        val fromB = service.getShow(userB, showId)!!
        assertFalse(fromB.subscribed)
        assertEquals(1, fromB.subscriberCount)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.subscribeToShow(userA, UUID.randomUUID()) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `unsubscribe orphans a feed show only for the last subscriber, never a local show, and resubscribing clears it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val feedShowId = transaction(database) { insertFeedShow("https://feed.example/b") }
        val localShowId = transaction(database) { insertLocalShow("localdir") }

        service.subscribeToShow(userA, feedShowId)
        service.subscribeToShow(userB, feedShowId)
        service.subscribeToShow(userA, localShowId)

        assertTrue(service.unsubscribe(userA, feedShowId))
        assertNull(service.showById(feedShowId)!!.orphanedAt)

        service.unsubscribe(userB, feedShowId)
        assertNotNull(service.showById(feedShowId)!!.orphanedAt)

        service.subscribeToShow(userB, feedShowId)
        assertNull(service.showById(feedShowId)!!.orphanedAt)

        service.unsubscribe(userA, localShowId)
        assertNull(service.showById(localShowId)!!.orphanedAt)

        assertFalse(service.unsubscribe(userA, feedShowId))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `browseShows with a blank query returns every show`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        transaction(database) {
            insertFeedShow("https://feed.example/1", title = "One")
            insertFeedShow("https://feed.example/2", title = "Two")
            insertLocalShow("three")
        }

        val page = service.browseShows(userId, "", 0, 50)
        assertEquals(3, page.total)
        assertEquals(3, page.data.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `browseShows matches title author and description case-insensitively`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val byTitle = transaction(database) { insertFeedShow("https://feed.example/t", title = "Space Talk") }
        val byAuthor = transaction(database) { insertFeedShow("https://feed.example/a", title = "Unrelated", author = "Jane Doe") }
        val byDescription = transaction(database) { insertFeedShow("https://feed.example/d", title = "Unrelated 2", description = "Something about Cats") }

        assertEquals(listOf(byTitle), service.browseShows(userId, "space talk", 0, 50).data.map { it.id })
        assertEquals(listOf(byAuthor), service.browseShows(userId, "JANE", 0, 50).data.map { it.id })
        assertEquals(listOf(byDescription), service.browseShows(userId, "CATS", 0, 50).data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `browseShows treats percent and underscore in the query as literal characters`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val percentMatch = transaction(database) { insertFeedShow("https://feed.example/p1", title = "100% Cotton") }
        transaction(database) { insertFeedShow("https://feed.example/p2", title = "100X Cotton") }
        val underscoreMatch = transaction(database) { insertFeedShow("https://feed.example/u1", title = "a_b") }
        transaction(database) { insertFeedShow("https://feed.example/u2", title = "axb") }

        assertEquals(listOf(percentMatch), service.browseShows(userId, "100%", 0, 50).data.map { it.id })
        assertEquals(listOf(underscoreMatch), service.browseShows(userId, "a_b", 0, 50).data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `browseShows reports subscribed independently per user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/s") }
        service.subscribeToShow(userA, showId)

        assertTrue(service.browseShows(userA, "", 0, 50).data.single().subscribed)
        assertFalse(service.browseShows(userB, "", 0, 50).data.single().subscribed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `browseShows paginates and clamps pageSize to 500`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        transaction(database) {
            repeat(505) { index -> insertFeedShow("https://feed.example/page-$index", title = "Show $index") }
        }

        val first = service.browseShows(userId, "", 0, 1000)
        assertEquals(505, first.total)
        assertEquals(500, first.pageSize)
        assertEquals(500, first.data.size)
        assertTrue(first.hasNextPage)

        val second = service.browseShows(userId, "", 1, 1000)
        assertEquals(5, second.data.size)
        assertFalse(second.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `knownSourceKeys returns only the keys of existing shows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val feedUrlOne = "https://feed.example/known-1"
        val feedUrlTwo = "https://feed.example/known-2"
        transaction(database) {
            insertFeedShow(feedUrlOne)
            insertFeedShow(feedUrlTwo)
        }
        val keyOne = PodcastKeys.feedSourceKey(feedUrlOne)
        val keyTwo = PodcastKeys.feedSourceKey(feedUrlTwo)

        val result = service.knownSourceKeys(listOf(keyOne, keyTwo, "FEED:missing"))

        assertEquals(setOf(keyOne, keyTwo), result)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `knownSourceKeys returns an empty set for no keys`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val result = service.knownSourceKeys(emptyList())

        assertTrue(result.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodes orders newest or oldest first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/order") }
        val e1 = transaction(database) { insertEpisode(showId, "o1", 1000L) }
        val e2 = transaction(database) { insertEpisode(showId, "o2", 3000L) }
        val e3 = transaction(database) { insertEpisode(showId, "o3", 2000L) }

        val newest = service.getEpisodes(userId, showId, 0, 50, newestFirst = true)
        assertEquals(listOf(e2, e3, e1), newest.data.map { it.id })

        val oldest = service.getEpisodes(userId, showId, 0, 50, newestFirst = false)
        assertEquals(listOf(e1, e3, e2), oldest.data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodes inlines progress only for the calling user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/progress") }
        val episodeId = transaction(database) { insertEpisode(showId, "p1", 1000L, durationMs = 60000L) }

        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = episodeId, positionMs = 5000))

        val forA = service.getEpisodes(userA, showId, 0, 50, true).data.single()
        val forB = service.getEpisodes(userB, showId, 0, 50, true).data.single()

        assertNotNull(forA.progress)
        assertEquals(5000L, forA.progress!!.positionMs)
        assertNull(forB.progress)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodes populates showTitle and showImageId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val imageId = transaction(database) { insertImage() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/img", title = "Illustrated Show", imageId = imageId) }
        transaction(database) { insertEpisode(showId, "i1", 1000L) }

        val episode = service.getEpisodes(userId, showId, 0, 50, true).data.single()
        assertEquals("Illustrated Show", episode.showTitle)
        assertEquals(imageId, episode.showImageId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodes reports hasTranscript only for episodes with a transcript`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/transcripts") }
        val withTranscript = transaction(database) { insertEpisode(showId, "t1", 1000L) }
        val withoutTranscript = transaction(database) { insertEpisode(showId, "t2", 2000L) }
        transaction(database) { insertTranscript(withTranscript, "src1", content = "hello") }

        val episodes = service.getEpisodes(userId, showId, 0, 50, true).data.associateBy { it.id }
        assertTrue(episodes.getValue(withTranscript).hasTranscript)
        assertFalse(episodes.getValue(withoutTranscript).hasTranscript)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodesByIds keeps the requested order, repeats duplicates and skips unknown ids`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showA = transaction(database) { insertFeedShow("https://feed.example/byids-a") }
        val showB = transaction(database) { insertFeedShow("https://feed.example/byids-b") }
        val first = transaction(database) { insertEpisode(showA, "bi1", 1000L) }
        val second = transaction(database) { insertEpisode(showB, "bi2", 2000L) }
        val third = transaction(database) { insertEpisode(showA, "bi3", 3000L) }
        val unknown = UUID.randomUUID()

        val requested = listOf(third, unknown, first, second, first)
        assertEquals(listOf(third, first, second, first), service.getEpisodesByIds(userId, requested).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodesByIds inlines progress only for the calling user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/byids-progress") }
        val episodeId = transaction(database) { insertEpisode(showId, "bip1", 1000L, durationMs = 60000L) }

        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = episodeId, positionMs = 5000))

        val forA = service.getEpisodesByIds(userA, listOf(episodeId)).single()
        val forB = service.getEpisodesByIds(userB, listOf(episodeId)).single()

        assertNotNull(forA.progress)
        assertEquals(5000L, forA.progress!!.positionMs)
        assertNull(forB.progress)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodesByIds returns nothing for an empty list and rejects more than 500 ids`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertTrue(service.getEpisodesByIds(userId, emptyList()).isEmpty())

        val tooMany = List(501) { UUID.randomUUID() }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.getEpisodesByIds(userId, tooMany) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodeWindow returns the anchor with the requested neighbours in chronological order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/window") }
        val episodes = transaction(database) { (1..7).map { insertEpisode(showId, "w$it", it * 1000L) } }

        val window = service.getEpisodeWindow(userId, episodes[3], older = 2, newer = 2)

        assertEquals(episodes.subList(1, 6), window.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodeWindow with zero counts returns only the anchor and larger counts return every episode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/window-bounds") }
        val episodes = transaction(database) { (1..7).map { insertEpisode(showId, "wb$it", it * 1000L) } }

        assertEquals(listOf(episodes[3]), service.getEpisodeWindow(userId, episodes[3], older = 0, newer = 0).map { it.id })
        assertEquals(episodes.subList(0, 4), service.getEpisodeWindow(userId, episodes[3], older = 50, newer = 0).map { it.id })
        assertEquals(episodes.subList(3, 7), service.getEpisodeWindow(userId, episodes[3], older = 0, newer = 50).map { it.id })
        assertEquals(episodes, service.getEpisodeWindow(userId, episodes[3], older = 50, newer = 50).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodeWindow treats negative counts as zero`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/window-negative") }
        val episodes = transaction(database) { (1..5).map { insertEpisode(showId, "wn$it", it * 1000L) } }

        assertEquals(listOf(episodes[2]), service.getEpisodeWindow(userId, episodes[2], older = -3, newer = -1).map { it.id })
        assertEquals(episodes.subList(2, 4), service.getEpisodeWindow(userId, episodes[2], older = -3, newer = 1).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodeWindow never crosses into another show and returns nothing for an unknown episode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showA = transaction(database) { insertFeedShow("https://feed.example/window-a") }
        val showB = transaction(database) { insertFeedShow("https://feed.example/window-b") }
        val mine = transaction(database) { (1..3).map { insertEpisode(showA, "wa$it", it * 1000L) } }
        transaction(database) { (1..5).map { insertEpisode(showB, "wbb$it", it * 500L) } }

        assertEquals(mine, service.getEpisodeWindow(userId, mine[1], older = 10, newer = 10).map { it.id })
        assertTrue(service.getEpisodeWindow(userId, UUID.randomUUID(), older = 5, newer = 5).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodeWindow inlines progress only for the calling user`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/window-progress") }
        val older = transaction(database) { insertEpisode(showId, "wp1", 1000L, durationMs = 600000L) }
        val anchor = transaction(database) { insertEpisode(showId, "wp2", 2000L, durationMs = 600000L) }

        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = anchor, positionMs = 5000))
        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = older, positionMs = 3000))

        val forA = service.getEpisodeWindow(userA, anchor, older = 5, newer = 5).associateBy { it.id }
        val forB = service.getEpisodeWindow(userB, anchor, older = 5, newer = 5).associateBy { it.id }

        assertEquals(3000L, forA.getValue(older).progress!!.positionMs)
        assertEquals(5000L, forA.getValue(anchor).progress!!.positionMs)
        assertNull(forB.getValue(older).progress)
        assertNull(forB.getValue(anchor).progress)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getEpisodeWindow breaks ties on the same publishedAt by id`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/window-ties") }
        val tied = transaction(database) { (1..4).map { insertEpisode(showId, "wt$it", 1000L) } }
        val ordered = service.getEpisodes(userId, showId, 0, 50, newestFirst = false).data.map { it.id }
        val anchor = ordered[2]

        assertEquals(tied.toSet(), ordered.toSet())
        assertEquals(ordered, service.getEpisodeWindow(userId, anchor, older = 10, newer = 10).map { it.id })
        assertEquals(ordered.subList(1, 4), service.getEpisodeWindow(userId, anchor, older = 1, newer = 10).map { it.id })
        assertEquals(ordered.subList(2, 3), service.getEpisodeWindow(userId, anchor, older = 0, newer = 0).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `searchEpisodes matches episode title description and show title, blank throws`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/search", title = "Searchable Show") }
        val byTitle = transaction(database) { insertEpisode(showId, "se1", 1000L, title = "Unique Episode Title") }
        val byDescription = transaction(database) { insertEpisode(showId, "se2", 2000L, description = "Mentions unicorns here") }
        val byShowTitle = transaction(database) { insertEpisode(showId, "se3", 3000L, title = "Plain") }

        assertEquals(listOf(byTitle), service.searchEpisodes(userId, "Unique Episode", 0, 50).data.map { it.id })
        assertEquals(listOf(byDescription), service.searchEpisodes(userId, "unicorns", 0, 50).data.map { it.id })
        assertEquals(setOf(byTitle, byDescription, byShowTitle), service.searchEpisodes(userId, "Searchable", 0, 50).data.map { it.id }.toSet())

        assertThrows<IllegalArgumentException> {
            runBlocking { service.searchEpisodes(userId, "   ", 0, 50) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getLatestEpisodes only returns episodes from the user's subscriptions newest first`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val subscribed = transaction(database) { insertFeedShow("https://feed.example/subscribed") }
        val notSubscribed = transaction(database) { insertFeedShow("https://feed.example/not-subscribed") }
        val older = transaction(database) { insertEpisode(subscribed, "l1", 1000L) }
        val newer = transaction(database) { insertEpisode(subscribed, "l2", 2000L) }
        transaction(database) { insertEpisode(notSubscribed, "l3", 3000L) }

        service.subscribeToShow(userId, subscribed)

        val latest = service.getLatestEpisodes(userId, 0, 50)
        assertEquals(listOf(newer, older), latest.data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getInProgress excludes completed and untouched episodes and orders by lastPlayedAt desc`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/inprogress") }
        val untouched = transaction(database) { insertEpisode(showId, "ip1", 1000L, durationMs = 60000L) }
        val completed = transaction(database) { insertEpisode(showId, "ip2", 2000L, durationMs = 60000L) }
        val olderStarted = transaction(database) { insertEpisode(showId, "ip3", 3000L, durationMs = 60000L) }
        val newerStarted = transaction(database) { insertEpisode(showId, "ip4", 4000L, durationMs = 60000L) }

        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = untouched, positionMs = 0))
        service.setPlayed(userId, completed, true)
        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = olderStarted, positionMs = 1000))
        Thread.sleep(5)
        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = newerStarted, positionMs = 2000))

        val inProgress = service.getInProgress(userId, 0, 50)
        assertEquals(listOf(newerStarted, olderStarted), inProgress.data.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getLastPlayed returns the most recently played episode across shows with its progress`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showA = transaction(database) { insertFeedShow("https://feed.example/last-a") }
        val showB = transaction(database) { insertFeedShow("https://feed.example/last-b") }
        val older = transaction(database) { insertEpisode(showA, "lp1", 1000L, durationMs = 600000L) }
        val newer = transaction(database) { insertEpisode(showB, "lp2", 2000L, durationMs = 600000L) }

        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = older, positionMs = 1000))
        Thread.sleep(5)
        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = newer, positionMs = 2000))

        val lastPlayed = service.getLastPlayed(userId, includeCompleted = true)
        assertNotNull(lastPlayed)
        assertEquals(newer, lastPlayed!!.id)
        assertEquals(2000L, lastPlayed.progress!!.positionMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getLastPlayed without completed skips a newer finished episode and one still at the start`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/last-unfinished") }
        val started = transaction(database) { insertEpisode(showId, "lu1", 1000L, durationMs = 600000L) }
        val completed = transaction(database) { insertEpisode(showId, "lu2", 2000L, durationMs = 600000L) }
        val atStart = transaction(database) { insertEpisode(showId, "lu3", 3000L, durationMs = 600000L) }

        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = started, positionMs = 1000))
        Thread.sleep(5)
        service.setPlayed(userId, completed, true)
        Thread.sleep(5)
        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = atStart, positionMs = 0))

        assertEquals(atStart, service.getLastPlayed(userId, includeCompleted = true)!!.id)
        assertEquals(started, service.getLastPlayed(userId, includeCompleted = false)!!.id)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getLastPlayed returns null without progress and never reads another user's progress`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/last-leak") }
        val episodeId = transaction(database) { insertEpisode(showId, "ll1", 1000L, durationMs = 600000L) }

        assertNull(service.getLastPlayed(userA, includeCompleted = true))

        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = episodeId, positionMs = 1000))

        assertEquals(episodeId, service.getLastPlayed(userA, includeCompleted = true)!!.id)
        assertNull(service.getLastPlayed(userB, includeCompleted = true))
        assertNull(service.getLastPlayed(userB, includeCompleted = false))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback creates then overwrites, last writer wins and stores deviceId`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/lww") }
        val episodeId = transaction(database) { insertEpisode(showId, "rp1", 1000L, durationMs = 100000L) }

        val first = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 1000, deviceId = "device-a"))
        assertEquals(1000L, first.positionMs)
        assertEquals("device-a", first.deviceId)

        val second = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 5000, deviceId = "device-b"))
        assertEquals(5000L, second.positionMs)
        assertEquals("device-b", second.deviceId)

        val stored = service.getEpisode(userId, episodeId)!!.progress!!
        assertEquals(5000L, stored.positionMs)
        assertEquals("device-b", stored.deviceId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback auto-completes when 30 seconds or less remain on a short episode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/tail30") }
        val episodeId = transaction(database) { insertEpisode(showId, "t30", 1000L, durationMs = 40000L) }

        val progress = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 10000))
        assertTrue(progress.completed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback auto-completes at 5 percent remaining on a 20 minute episode even past 30 seconds`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/tail5pct") }
        val duration = 1_200_000L
        val completingId = transaction(database) { insertEpisode(showId, "tp1", 1000L, durationMs = duration) }
        val stillPlayingId = transaction(database) { insertEpisode(showId, "tp2", 2000L, durationMs = duration) }

        val completing = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = completingId, positionMs = duration - 45_000L))
        assertTrue(completing.completed)

        val stillPlaying = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = stillPlayingId, positionMs = duration - 65_000L))
        assertFalse(stillPlaying.completed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback clamps a position beyond the duration`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/clamp") }
        val episodeId = transaction(database) { insertEpisode(showId, "cl1", 1000L, durationMs = 1000L) }

        val progress = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 999999))
        assertEquals(1000L, progress.positionMs)
        assertTrue(progress.completed)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a later report at position 0 clears completed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/restart") }
        val episodeId = transaction(database) { insertEpisode(showId, "rs1", 1000L, durationMs = 100000L) }

        val done = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 90000))
        assertTrue(done.completed)

        val restarted = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 0))
        assertFalse(restarted.completed)
        assertEquals(0L, restarted.positionMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback honours an explicit completed flag with no known duration`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/explicit") }
        val episodeId = transaction(database) { insertEpisode(showId, "ex1", 1000L, durationMs = null) }

        val progress = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 500, completed = true))
        assertTrue(progress.completed)
        assertNull(progress.durationMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback for an unknown episode throws`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.reportPlayback(userId, EpisodePlaybackReport(episodeId = UUID.randomUUID(), positionMs = 0)) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback validation rejects a negative position and an oversized deviceId`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/validation") }
        val episodeId = transaction(database) { insertEpisode(showId, "val1", 1000L) }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = -1)) }
        }
        assertThrows<IllegalArgumentException> {
            runBlocking { service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 0, deviceId = "d".repeat(65))) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `reportPlayback falls back to the episode's stored duration when the report has none`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/fallback-duration") }
        val episodeId = transaction(database) { insertEpisode(showId, "fd1", 1000L, durationMs = 100000L) }

        val progress = service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 999999999))
        assertEquals(100000L, progress.durationMs)
        assertEquals(100000L, progress.positionMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `setPlayed moves the position to the end when known and back to the start`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/setplayed") }
        val episodeId = transaction(database) { insertEpisode(showId, "sp1", 1000L, durationMs = 50000L) }

        val played = service.setPlayed(userId, episodeId, true)
        assertTrue(played.completed)
        assertEquals(50000L, played.positionMs)

        val unplayed = service.setPlayed(userId, episodeId, false)
        assertFalse(unplayed.completed)
        assertEquals(0L, unplayed.positionMs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `observeProgress emits once per reportPlayback for that user only`(dialect: DbDialect) = runTest {
        setup(dialect)
        val userA = transaction(database) { insertUser() }
        val userB = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/observe") }
        val episodeId = transaction(database) { insertEpisode(showId, "obs1", 1000L) }

        val resultsA = mutableListOf<PodcastEpisodeProgress>()
        val resultsB = mutableListOf<PodcastEpisodeProgress>()
        val jobA = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeProgress(userA).collect { resultsA.add(it) }
        }
        val jobB = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observeProgress(userB).collect { resultsB.add(it) }
        }

        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = episodeId, positionMs = 1000))
        assertEquals(1, resultsA.size)
        assertEquals(0, resultsB.size)

        service.reportPlayback(userA, EpisodePlaybackReport(episodeId = episodeId, positionMs = 2000))
        assertEquals(2, resultsA.size)
        assertEquals(0, resultsB.size)

        jobA.cancel()
        jobB.cancel()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertEpisodes is idempotent by showId and guid and preserves import state while keeping the stored duration`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/upsert") }

        val (inserted, _) = service.upsertEpisodes(
            showId,
            listOf(
                ParsedEpisode(
                    guid = "ep-1",
                    title = "Old Title",
                    publishedAt = 1000L,
                    durationMs = 5000L,
                    enclosureUrl = "https://feed.example/e1.mp3",
                )
            )
        )
        assertEquals(1, inserted)

        val episodeId = service.episodesOfShow(showId).single().id
        service.markImported(episodeId, "/data/ep1.mp3", 999L, "mp3", null)
        service.markImportState(episodeId, PodcastImportState.IMPORTED, incrementAttempts = true)

        val (insertedAgain, updated) = service.upsertEpisodes(
            showId,
            listOf(
                ParsedEpisode(
                    guid = "ep-1",
                    title = "New Title",
                    publishedAt = 1000L,
                    durationMs = null,
                    enclosureUrl = "https://feed.example/e1.mp3",
                )
            )
        )
        assertEquals(0, insertedAgain)
        assertEquals(1, updated)

        val row = service.episodeById(episodeId)!!
        assertEquals("New Title", row.title)
        assertEquals(5000L, row.durationMs)
        assertEquals("/data/ep1.mp3", row.filePath)
        assertEquals(PodcastImportState.IMPORTED, row.importState)
        assertEquals(1, row.importAttempts)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertEpisodes accepts a guid longer than 600 characters`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/longguid") }
        val longGuid = "g".repeat(601)

        val (inserted, _) = service.upsertEpisodes(
            showId,
            listOf(ParsedEpisode(guid = longGuid, title = "Long Guid", publishedAt = 1000L, enclosureUrl = "https://feed.example/lg.mp3"))
        )
        assertEquals(1, inserted)
        assertEquals(longGuid, service.episodesOfShow(showId).single().guid)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertEpisodes syncs feed transcripts, removing vanished urls while embedded transcripts survive`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/transcript-sync") }

        service.upsertEpisodes(
            showId,
            listOf(
                ParsedEpisode(
                    guid = "tr-1",
                    title = "Episode",
                    publishedAt = 1000L,
                    enclosureUrl = "https://feed.example/tr1.mp3",
                    transcripts = listOf(ParsedTranscript(url = "https://feed.example/tr1.vtt", type = "text/vtt")),
                )
            )
        )
        val episodeId = service.episodesOfShow(showId).single().id
        assertEquals(1, service.getTranscripts(userId, episodeId).size)

        service.addEmbeddedTranscript(episodeId, "embedded lyrics", "text/plain")
        assertEquals(2, service.getTranscripts(userId, episodeId).size)

        service.upsertEpisodes(
            showId,
            listOf(
                ParsedEpisode(
                    guid = "tr-1",
                    title = "Episode",
                    publishedAt = 1000L,
                    enclosureUrl = "https://feed.example/tr1.mp3",
                    transcripts = emptyList(),
                )
            )
        )

        val remaining = service.getTranscripts(userId, episodeId)
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().available)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertEpisodes truncates a description longer than 20000 characters`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/truncate") }

        service.upsertEpisodes(
            showId,
            listOf(
                ParsedEpisode(
                    guid = "trunc-1",
                    title = "Episode",
                    description = "d".repeat(25000),
                    publishedAt = 1000L,
                    enclosureUrl = "https://feed.example/trunc.mp3",
                )
            )
        )

        val row = service.episodesOfShow(showId).single()
        assertEquals(20000, row.description.length)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertLocalShow sets the title only on insert`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val path = "showdir"

        val firstId = service.upsertLocalShow(path, "Title A", null)
        val secondId = service.upsertLocalShow(path, "Title B", null)

        assertEquals(firstId, secondId)
        assertEquals("Title A", service.showById(firstId)!!.title)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `upsertLocalEpisodes marks episodes imported with file fields and syncs sidecar and embedded transcripts`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = service.upsertLocalShow("localshow", "Local Show", null)

        val (inserted, _) = service.upsertLocalEpisodes(
            showId,
            listOf(
                LocalEpisode(
                    guid = "local-1",
                    title = "Local Episode",
                    publishedAt = 1000L,
                    durationMs = 60000L,
                    filePath = "/library/localshow/ep1.mp3",
                    fileSize = 12345L,
                    format = "mp3",
                    transcripts = listOf(
                        LocalTranscript.Sidecar(filePath = "/library/localshow/ep1.vtt", type = "text/vtt"),
                        LocalTranscript.Embedded(content = "lyrics text", type = "text/plain"),
                    )
                )
            )
        )
        assertEquals(1, inserted)

        val episode = service.episodesOfShow(showId).single()
        assertEquals(PodcastImportState.IMPORTED, episode.importState)
        assertEquals("/library/localshow/ep1.mp3", episode.filePath)
        assertEquals(12345L, episode.fileSize)

        assertEquals(2, service.getTranscripts(userId, episode.id).size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateShowSettings rejects a keepEpisodes of 0 and stores IMPORT with a valid keepEpisodes`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/settings") }

        assertThrows<IllegalArgumentException> {
            runBlocking { service.updateShowSettings(showId, PodcastShowSettings(PodcastDeliveryMode.IMPORT, keepEpisodes = 0), userId) }
        }

        val updated = service.updateShowSettings(showId, PodcastShowSettings(PodcastDeliveryMode.IMPORT, keepEpisodes = 5), userId)
        assertEquals(PodcastDeliveryMode.IMPORT, updated.deliveryMode)
        assertEquals(5, updated.keepEpisodes)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.updateShowSettings(UUID.randomUUID(), PodcastShowSettings(PodcastDeliveryMode.STREAM), userId) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `updateShowSettings stores unlistened retention only for import delivery and getShow exposes it`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/retention") }

        assertEquals(PodcastRetention.NEWEST, service.getShow(userId, showId)!!.retention)

        assertThrows<IllegalArgumentException> {
            runBlocking {
                service.updateShowSettings(
                    showId,
                    PodcastShowSettings(PodcastDeliveryMode.STREAM, retention = PodcastRetention.UNLISTENED),
                    userId
                )
            }
        }

        val updated = service.updateShowSettings(
            showId,
            PodcastShowSettings(PodcastDeliveryMode.IMPORT, keepEpisodes = 3, retention = PodcastRetention.UNLISTENED),
            userId
        )
        assertEquals(PodcastRetention.UNLISTENED, updated.retention)
        assertEquals(PodcastRetention.UNLISTENED, service.getShow(userId, showId)!!.retention)
        assertEquals(PodcastRetention.UNLISTENED, service.showById(showId)!!.retention)

        val reverted = service.updateShowSettings(showId, PodcastShowSettings(PodcastDeliveryMode.STREAM), userId)
        assertEquals(PodcastRetention.NEWEST, reverted.retention)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `importCandidates filters by enclosure, import state and attempts, ordered newest first with a limit`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/candidates") }
        val none = transaction(database) {
            insertEpisode(showId, "ic1", 3000L, enclosureUrl = "https://x/1.mp3", importState = PodcastImportState.NONE, importAttempts = 0)
        }
        val failed = transaction(database) {
            insertEpisode(showId, "ic2", 5000L, enclosureUrl = "https://x/2.mp3", importState = PodcastImportState.FAILED, importAttempts = 1)
        }
        transaction(database) {
            insertEpisode(showId, "ic3", 4000L, enclosureUrl = "https://x/3.mp3", importState = PodcastImportState.FAILED, importAttempts = 5)
        }
        transaction(database) {
            insertEpisode(showId, "ic4", 6000L, enclosureUrl = null, importState = PodcastImportState.NONE)
        }
        transaction(database) {
            insertEpisode(showId, "ic5", 7000L, enclosureUrl = "https://x/5.mp3", importState = PodcastImportState.IMPORTED)
        }

        val candidates = service.importCandidates(showId, limit = 10, maxAttempts = 3)
        assertEquals(listOf(failed, none), candidates.map { it.id })

        val limited = service.importCandidates(showId, limit = 1, maxAttempts = 3)
        assertEquals(listOf(failed), limited.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `episodesInState filters by state ordered by updatedAt and honours a limit`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/state") }
        val older = transaction(database) {
            insertEpisode(showId, "st1", 1000L, importState = PodcastImportState.QUEUED, updatedAt = 1000L)
        }
        val newer = transaction(database) {
            insertEpisode(showId, "st2", 1000L, importState = PodcastImportState.QUEUED, updatedAt = 3000L)
        }
        transaction(database) {
            insertEpisode(showId, "st3", 1000L, importState = PodcastImportState.FAILED, updatedAt = 500L)
        }

        val queued = service.episodesInState(PodcastImportState.QUEUED)
        assertEquals(listOf(older, newer), queued.map { it.id })

        val limited = service.episodesInState(PodcastImportState.QUEUED, limit = 1)
        assertEquals(listOf(older), limited.map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `importedBeyond returns imported episodes past the newest keep, oldest last`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/beyond") }
        val e1 = transaction(database) {
            insertEpisode(showId, "ib1", 1000L, importState = PodcastImportState.IMPORTED, filePath = "/f1")
        }
        val e2 = transaction(database) {
            insertEpisode(showId, "ib2", 2000L, importState = PodcastImportState.IMPORTED, filePath = "/f2")
        }
        val e3 = transaction(database) {
            insertEpisode(showId, "ib3", 3000L, importState = PodcastImportState.IMPORTED, filePath = "/f3")
        }
        transaction(database) {
            insertEpisode(showId, "ib4", 4000L, importState = PodcastImportState.NONE)
        }

        assertEquals(listOf(e2, e1), service.importedBeyond(showId, keep = 1).map { it.id })
        assertEquals(listOf(e3, e2, e1), service.importedBeyond(showId, keep = 0).map { it.id })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `markImportState sets state and error and can increment or reset attempts`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/markstate") }
        val episodeId = transaction(database) { insertEpisode(showId, "ms1", 1000L, importAttempts = 2) }

        service.markImportState(episodeId, PodcastImportState.FAILED, "boom", incrementAttempts = true)
        var row = service.episodeById(episodeId)!!
        assertEquals(PodcastImportState.FAILED, row.importState)
        assertEquals("boom", row.importError)
        assertEquals(3, row.importAttempts)

        service.markImportState(episodeId, PodcastImportState.QUEUED, resetAttempts = true)
        row = service.episodeById(episodeId)!!
        assertEquals(PodcastImportState.QUEUED, row.importState)
        assertEquals(0, row.importAttempts)

        service.markImportState(episodeId, PodcastImportState.QUEUED)
        row = service.episodeById(episodeId)!!
        assertEquals(0, row.importAttempts)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `markImported stores the file and keeps the existing duration unless a new one is given, clearImport resets it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = transaction(database) { insertFeedShow("https://feed.example/markimported") }
        val episodeId = transaction(database) { insertEpisode(showId, "mi1", 1000L, durationMs = 5000L) }

        service.markImported(episodeId, "/data/mi1.mp3", 999L, "mp3", null)
        var row = service.episodeById(episodeId)!!
        assertEquals(PodcastImportState.IMPORTED, row.importState)
        assertEquals("/data/mi1.mp3", row.filePath)
        assertEquals(999L, row.fileSize)
        assertEquals("mp3", row.format)
        assertEquals(5000L, row.durationMs)
        assertNotNull(row.importedAt)

        service.markImported(episodeId, "/data/mi1.mp3", 999L, "mp3", 6000L)
        row = service.episodeById(episodeId)!!
        assertEquals(6000L, row.durationMs)

        service.clearImport(episodeId)
        row = service.episodeById(episodeId)!!
        assertEquals(PodcastImportState.NONE, row.importState)
        assertNull(row.filePath)
        assertNull(row.fileSize)
        assertNull(row.format)
        assertNull(row.importedAt)
        assertNull(row.importError)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteShow cascades to episodes transcripts progress and subscriptions`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/cascade") }
        val episodeId = transaction(database) { insertEpisode(showId, "cascade-ep", 1000L) }
        transaction(database) { insertTranscript(episodeId, "t1", url = "https://feed.example/t.vtt") }
        service.subscribeToShow(userId, showId)
        service.reportPlayback(userId, EpisodePlaybackReport(episodeId = episodeId, positionMs = 100))

        service.deleteShow(showId)

        assertEquals(0, transaction(database) { PodcastEpisodeTable.selectAll().count() }.toInt())
        assertEquals(0, transaction(database) { PodcastTranscriptTable.selectAll().count() }.toInt())
        assertEquals(0, transaction(database) { PodcastSubscriptionTable.selectAll().count() }.toInt())
        assertEquals(0, transaction(database) { PodcastEpisodeProgressTable.selectAll().count() }.toInt())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getTranscript serves embedded content directly without an HTTP call`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/embedded") }
        val episodeId = transaction(database) { insertEpisode(showId, "em1", 1000L) }
        val transcriptId = transaction(database) {
            insertTranscript(episodeId, PodcastKeys.EMBEDDED_TRANSCRIPT_KEY, content = "embedded text")
        }

        val content = service.getTranscript(userId, transcriptId)
        assertNotNull(content)
        assertEquals("embedded text", content!!.content)
        assertTrue(content.transcript.available)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getTranscript reads a sidecar file from disk`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/sidecar") }
        val episodeId = transaction(database) { insertEpisode(showId, "sc1", 1000L) }

        val dir = Files.createTempDirectory("podcast-sidecar")
        val file = dir.resolve("sidecar.vtt").toFile()
        file.writeText("WEBVTT\n\n00:00.000 --> 00:01.000\nHello")

        val transcriptId = transaction(database) {
            insertTranscript(episodeId, "sidecar-key", filePath = file.absolutePath)
        }

        val content = service.getTranscript(userId, transcriptId)
        assertNotNull(content)
        assertEquals(file.readText(), content!!.content)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getTranscript fetches a url transcript once through the http client and caches it`(dialect: DbDialect) = runBlocking {
        var requestCount = 0
        setup(dialect) { _ ->
            requestCount++
            respond("WEBVTT\n\nHello", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/vtt"))
        }
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/url") }
        val episodeId = transaction(database) { insertEpisode(showId, "url1", 1000L) }
        val transcriptId = transaction(database) {
            insertTranscript(episodeId, "url-key", url = "https://example.com/transcript.vtt")
        }

        val first = service.getTranscript(userId, transcriptId)
        assertNotNull(first)
        assertEquals("WEBVTT\n\nHello", first!!.content)
        assertEquals(1, requestCount)

        val second = service.getTranscript(userId, transcriptId)
        assertNotNull(second)
        assertEquals(1, requestCount)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getTranscript records fetchError and returns null when the origin fails`(dialect: DbDialect) = runBlocking {
        setup(dialect) { _ -> respondError(HttpStatusCode.InternalServerError) }
        val userId = transaction(database) { insertUser() }
        val showId = transaction(database) { insertFeedShow("https://feed.example/failing") }
        val episodeId = transaction(database) { insertEpisode(showId, "fail1", 1000L) }
        val transcriptId = transaction(database) {
            insertTranscript(episodeId, "fail-key", url = "https://example.com/missing.vtt")
        }

        val content = service.getTranscript(userId, transcriptId)
        assertNull(content)

        val fetchError = transaction(database) {
            PodcastTranscriptTable.selectAll().where { PodcastTranscriptTable.id eq transcriptId }.single()[PodcastTranscriptTable.fetchError]
        }
        assertNotNull(fetchError)
    }
}
