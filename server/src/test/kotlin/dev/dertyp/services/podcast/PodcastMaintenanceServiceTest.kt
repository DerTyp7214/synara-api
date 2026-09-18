package dev.dertyp.services.podcast

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastSource
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PodcastEpisodeProgressTable
import dev.dertyp.db.PodcastEpisodeTable
import dev.dertyp.db.PodcastShowTable
import dev.dertyp.db.PodcastSubscriptionTable
import dev.dertyp.db.PodcastTranscriptTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.StorageService
import dev.dertyp.services.schedule.ScheduleService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class PodcastMaintenanceServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var tempDir: File
    private lateinit var importsDir: File
    private lateinit var storageService: StorageService
    private lateinit var podcastService: PodcastService
    private lateinit var importService: PodcastImportService
    private lateinit var maintenanceService: PodcastMaintenanceService
    private lateinit var subscriberId: UUID
    private lateinit var secondSubscriberId: UUID

    private fun mockHttp(): PodcastHttp {
        val http = spyk(PodcastHttp())
        every { http.feedClient } returns HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) })
        every { http.mediaClient } returns HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) })
        return http
    }

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        tempDir = Files.createTempDirectory("podcast_maintenance").toFile()
        importsDir = File(tempDir, "imports").apply { mkdirs() }

        val config = MapApplicationConfig().apply {
            put("audio.custom", File(tempDir, "custom").absolutePath)
            put("data.images", File(tempDir, "images").absolutePath)
            put("data.animated-images", File(tempDir, "animated-images").absolutePath)
            put("podcasts.library", File(tempDir, "library").absolutePath)
            put("podcasts.imports", importsDir.absolutePath)
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config
        storageService = StorageService(environment)

        database = TestDatabase.connect(dialect, "podcast_maintenance_test")
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

        val http = mockHttp()
        podcastService = PodcastService(http)
        val scheduleService = mockk<ScheduleService>(relaxed = true)
        importService = PodcastImportService(podcastService, storageService, scheduleService, http)
        maintenanceService = PodcastMaintenanceService(podcastService, importService)

        subscriberId = insertUser()
        secondSubscriberId = insertUser()
    }

    private fun insertUser(): UUID = transaction(database) {
        val id = UUID.randomUUID()
        UserTable.insert {
            it[UserTable.id] = id
            it[username] = "user_$id"
            it[passwordHash] = "hash"
        }
        id
    }

    private fun subscribe(showId: UUID, userId: UUID) = transaction(database) {
        PodcastSubscriptionTable.insert {
            it[PodcastSubscriptionTable.userId] = userId
            it[PodcastSubscriptionTable.showId] = showId
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    private fun insertShow(
        source: PodcastSource = PodcastSource.FEED,
        mode: PodcastDeliveryMode = PodcastDeliveryMode.IMPORT,
        keep: Int? = null,
        orphaned: Long? = null,
        subscribed: Boolean = true,
        retention: PodcastRetention = PodcastRetention.NEWEST
    ): UUID = transaction(database) {
        val id = UUID.randomUUID()
        PodcastShowTable.insert {
            it[PodcastShowTable.id] = id
            it[showSource] = source
            it[sourceKey] = "${source.name}:$id"
            it[title] = "Show $id"
            it[feedUrl] = if (source == PodcastSource.FEED) "$ORIGIN/feed/$id.xml" else null
            it[localPath] = if (source == PodcastSource.LOCAL) "Show-$id" else null
            it[deliveryMode] = mode
            it[keepEpisodes] = keep
            it[PodcastShowTable.retention] = retention
            it[orphanedAt] = orphaned
        }
        if (subscribed) {
            PodcastSubscriptionTable.insert {
                it[PodcastSubscriptionTable.userId] = subscriberId
                it[showId] = id
            }
        }
        id
    }

    private fun insertEpisode(
        showId: UUID,
        published: Long,
        state: PodcastImportState = PodcastImportState.NONE,
        attempts: Int = 0,
        url: String? = "$ORIGIN/media/ep.mp3",
        path: String? = null
    ): UUID = transaction(database) {
        val id = UUID.randomUUID()
        val guid = "guid-$id"
        PodcastEpisodeTable.insert {
            it[PodcastEpisodeTable.id] = id
            it[PodcastEpisodeTable.showId] = showId
            it[PodcastEpisodeTable.guid] = guid
            it[guidKey] = PodcastKeys.guidKey(guid)
            it[title] = "Episode $published"
            it[publishedAt] = published
            it[enclosureUrl] = url
            it[enclosureType] = "audio/mpeg"
            it[importState] = state
            it[importAttempts] = attempts
            it[filePath] = path
            it[fileSize] = if (path != null) 8L else null
            it[format] = if (path != null) "mp3" else null
        }
        id
    }

    private fun importedEpisode(showId: UUID, published: Long): Pair<UUID, File> {
        val id = UUID.randomUUID()
        val directory = File(importsDir, showId.toString()).apply { mkdirs() }
        val file = File(directory, "$id.mp3").apply { writeText("audiobytes") }
        transaction(database) {
            val guid = "guid-$id"
            PodcastEpisodeTable.insert {
                it[PodcastEpisodeTable.id] = id
                it[PodcastEpisodeTable.showId] = showId
                it[PodcastEpisodeTable.guid] = guid
                it[guidKey] = PodcastKeys.guidKey(guid)
                it[title] = "Episode $published"
                it[publishedAt] = published
                it[enclosureUrl] = "$ORIGIN/media/ep.mp3"
                it[enclosureType] = "audio/mpeg"
                it[importState] = PodcastImportState.IMPORTED
                it[filePath] = file.absolutePath
                it[fileSize] = file.length()
                it[format] = "mp3"
                it[importedAt] = published
            }
        }
        return id to file
    }

    private fun insertProgress(
        episodeId: UUID,
        userId: UUID = subscriberId,
        finished: Boolean = false
    ) = transaction(database) {
        PodcastEpisodeProgressTable.insert {
            it[PodcastEpisodeProgressTable.userId] = userId
            it[PodcastEpisodeProgressTable.episodeId] = episodeId
            it[positionMs] = 1000L
            it[completed] = finished
        }
    }

    private fun stateOf(episodeId: UUID): PodcastImportState = runBlocking {
        podcastService.episodeById(episodeId)!!.importState
    }

    private fun showCount(): Int = transaction(database) { PodcastShowTable.selectAll().count().toInt() }

    private fun episodeCount(showId: UUID): Int = transaction(database) {
        PodcastEpisodeTable.selectAll().where { PodcastEpisodeTable.showId eq showId }.count().toInt()
    }

    private fun progressCount(): Int = transaction(database) {
        PodcastEpisodeProgressTable.selectAll().count().toInt()
    }

    private fun subscriptionCount(): Int = transaction(database) {
        PodcastSubscriptionTable.selectAll().count().toInt()
    }

    private fun insertTranscript(episodeId: UUID): UUID = transaction(database) {
        val id = UUID.randomUUID()
        PodcastTranscriptTable.insert {
            it[PodcastTranscriptTable.id] = id
            it[PodcastTranscriptTable.episodeId] = episodeId
            it[sourceKey] = "source"
            it[type] = "text/plain"
        }
        id
    }

    private fun transcriptCount(): Int = transaction(database) {
        PodcastTranscriptTable.selectAll().count().toInt()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queueImports queues only the newest kept episodes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = 2)
        val episodes = (1..4).map { insertEpisode(showId, published = it * 1000L) }

        assertEquals(2, maintenanceService.queueImports())

        assertEquals(PodcastImportState.QUEUED, stateOf(episodes[3]))
        assertEquals(PodcastImportState.QUEUED, stateOf(episodes[2]))
        assertEquals(PodcastImportState.NONE, stateOf(episodes[1]))
        assertEquals(PodcastImportState.NONE, stateOf(episodes[0]))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queueImports skips failed episodes that exhausted their attempts`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = 5)
        val exhausted = insertEpisode(showId, 1000L, PodcastImportState.FAILED, attempts = 3)
        val retryable = insertEpisode(showId, 2000L, PodcastImportState.FAILED, attempts = 2)

        assertEquals(1, maintenanceService.queueImports())

        assertEquals(PodcastImportState.FAILED, stateOf(exhausted))
        assertEquals(PodcastImportState.QUEUED, stateOf(retryable))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queueImports ignores stream local orphaned and unsubscribed shows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val streaming = insertShow(mode = PodcastDeliveryMode.STREAM)
        val local = insertShow(source = PodcastSource.LOCAL)
        val orphaned = insertShow(orphaned = Instant.now().toEpochMilli())
        val unsubscribed = insertShow(subscribed = false)
        val ignored = listOf(streaming, local, orphaned, unsubscribed).map { insertEpisode(it, 1000L) }

        assertEquals(0, maintenanceService.queueImports())

        ignored.forEach { assertEquals(PodcastImportState.NONE, stateOf(it)) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queueImports falls back to the default keep count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = null)
        val episodes = (1..7).map { insertEpisode(showId, published = it * 1000L) }

        assertEquals(PodcastMaintenanceService.DEFAULT_KEEP, maintenanceService.queueImports())

        val queued = episodes.filter { stateOf(it) == PodcastImportState.QUEUED }
        assertEquals(episodes.takeLast(PodcastMaintenanceService.DEFAULT_KEEP).toSet(), queued.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `enforceRetention deletes the oldest imports beyond the keep count`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = 2)
        val episodes = (1..4).map { importedEpisode(showId, published = it * 1000L) }

        assertEquals(2, maintenanceService.enforceRetention())

        episodes.take(2).forEach { (id, file) ->
            assertFalse(file.exists(), "expected ${file.name} to be deleted")
            val row = podcastService.episodeById(id)!!
            assertEquals(PodcastImportState.NONE, row.importState)
            assertNull(row.filePath)
            assertNull(row.fileSize)
        }
        episodes.drop(2).forEach { (id, file) ->
            assertTrue(file.exists(), "expected ${file.name} to be kept")
            assertEquals(PodcastImportState.IMPORTED, podcastService.episodeById(id)!!.importState)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `enforceRetention keeps everything without a keep count and never touches local shows`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val unlimited = insertShow(keep = null)
            val unlimitedEpisodes = (1..3).map { importedEpisode(unlimited, published = it * 1000L) }
            val local = insertShow(source = PodcastSource.LOCAL, keep = 1)
            val localEpisodes = (1..3).map { importedEpisode(local, published = it * 1000L) }

            assertEquals(0, maintenanceService.enforceRetention())

            (unlimitedEpisodes + localEpisodes).forEach { (id, file) ->
                assertTrue(file.exists())
                assertEquals(PodcastImportState.IMPORTED, podcastService.episodeById(id)!!.importState)
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queueImports with unlistened retention queues every episode no subscriber finished`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = null, retention = PodcastRetention.UNLISTENED)
        subscribe(showId, secondSubscriberId)
        val episodes = (1..4).map { insertEpisode(showId, published = it * 1000L) }
        insertProgress(episodes[0], subscriberId, finished = true)

        assertEquals(4, maintenanceService.queueImports())

        episodes.forEach { assertEquals(PodcastImportState.QUEUED, stateOf(it)) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `queueImports with unlistened retention fills the keep count with what is already stored`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = 2, retention = PodcastRetention.UNLISTENED)
        subscribe(showId, secondSubscriberId)
        importedEpisode(showId, published = 5000L)
        val pending = (1..3).map { insertEpisode(showId, published = it * 1000L) }

        assertEquals(1, maintenanceService.queueImports())

        assertEquals(PodcastImportState.QUEUED, stateOf(pending[2]))
        assertEquals(PodcastImportState.NONE, stateOf(pending[1]))
        assertEquals(PodcastImportState.NONE, stateOf(pending[0]))

        assertEquals(0, maintenanceService.queueImports())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `enforceRetention with unlistened retention deletes only what every subscriber finished`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = null, retention = PodcastRetention.UNLISTENED)
        subscribe(showId, secondSubscriberId)
        val (finishedId, finishedFile) = importedEpisode(showId, published = 1000L)
        val (halfId, halfFile) = importedEpisode(showId, published = 2000L)
        val (untouchedId, untouchedFile) = importedEpisode(showId, published = 3000L)

        insertProgress(finishedId, subscriberId, finished = true)
        insertProgress(finishedId, secondSubscriberId, finished = true)
        insertProgress(halfId, subscriberId, finished = true)
        insertProgress(halfId, secondSubscriberId, finished = false)

        assertEquals(1, maintenanceService.enforceRetention())

        assertFalse(finishedFile.exists())
        val finished = podcastService.episodeById(finishedId)!!
        assertEquals(PodcastImportState.NONE, finished.importState)
        assertNull(finished.filePath)

        assertTrue(halfFile.exists())
        assertEquals(PodcastImportState.IMPORTED, podcastService.episodeById(halfId)!!.importState)
        assertTrue(untouchedFile.exists())
        assertEquals(PodcastImportState.IMPORTED, podcastService.episodeById(untouchedId)!!.importState)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `enforceRetention with unlistened retention counts setPlayed as finished`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(keep = null, retention = PodcastRetention.UNLISTENED)
        subscribe(showId, secondSubscriberId)
        val (episodeId, file) = importedEpisode(showId, published = 1000L)

        podcastService.setPlayed(subscriberId, episodeId, true)
        assertEquals(0, maintenanceService.enforceRetention())
        assertTrue(file.exists())

        podcastService.setPlayed(secondSubscriberId, episodeId, true)
        assertEquals(1, maintenanceService.enforceRetention())
        assertFalse(file.exists())
        assertEquals(PodcastImportState.NONE, podcastService.episodeById(episodeId)!!.importState)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `purgeOrphanedShows removes shows orphaned for longer than the limit`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val now = Instant.now().toEpochMilli()
        val stale = insertShow(orphaned = now - 8 * DAY_MS, subscribed = true)
        val (staleEpisode, staleFile) = importedEpisode(stale, published = 1000L)
        insertProgress(staleEpisode)
        val recent = insertShow(orphaned = now - 6 * DAY_MS, subscribed = false)
        val recentEpisode = insertEpisode(recent, 1000L)
        val local = insertShow(source = PodcastSource.LOCAL, orphaned = now - 30 * DAY_MS, subscribed = false)

        assertEquals(1, maintenanceService.purgeOrphanedShows())

        assertFalse(staleFile.exists(), "the imported file of a purged show should be gone")
        assertEquals(2, showCount())
        assertEquals(1, episodeCount(recent))
        assertEquals(PodcastImportState.NONE, stateOf(recentEpisode))
        assertTrue(podcastService.localShows().map { it.id }.contains(local))

        assertEquals(0, episodeCount(stale))
        assertEquals(0, progressCount())
        assertEquals(0, subscriptionCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `purgeOrphanedShows honours the age parameter`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val now = Instant.now().toEpochMilli()
        val showId = insertShow(orphaned = now - 2 * DAY_MS, subscribed = false)
        insertEpisode(showId, 1000L)

        assertEquals(0, maintenanceService.purgeOrphanedShows(olderThanMs = 3 * DAY_MS))
        assertEquals(1, showCount())

        assertEquals(1, maintenanceService.purgeOrphanedShows(olderThanMs = DAY_MS))
        assertEquals(0, showCount())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteShow removes a feed show its episodes transcripts progress and subscriptions and deletes the imported file`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val showId = insertShow(subscribed = true)
            val (episodeId, file) = importedEpisode(showId, published = 1000L)
            insertProgress(episodeId)
            insertTranscript(episodeId)

            assertTrue(maintenanceService.deleteShow(showId))

            assertFalse(file.exists(), "expected the imported file to be deleted")
            assertEquals(0, showCount())
            assertEquals(0, episodeCount(showId))
            assertEquals(0, progressCount())
            assertEquals(0, subscriptionCount())
            assertEquals(0, transcriptCount())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteShow returns false for an unknown show id`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertFalse(maintenanceService.deleteShow(UUID.randomUUID()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteShow throws for a local show and leaves it in place`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow(source = PodcastSource.LOCAL, subscribed = false)
        val (episodeId, file) = importedEpisode(showId, published = 1000L)

        assertThrows<IllegalArgumentException> {
            runBlocking { maintenanceService.deleteShow(showId) }
        }

        assertEquals(1, showCount())
        assertEquals(1, episodeCount(showId))
        assertTrue(file.exists())
        assertEquals(PodcastImportState.IMPORTED, podcastService.episodeById(episodeId)!!.importState)
    }

    companion object {
        private const val ORIGIN = "https://203.0.113.10"
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
