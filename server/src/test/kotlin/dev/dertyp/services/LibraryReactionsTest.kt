package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.EntityType
import dev.dertyp.data.TaskKeys
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.db.TimecodeTagTable
import dev.dertyp.services.schedule.AudioStartAnalysisWorker
import dev.dertyp.services.schedule.ImageAnalysisWorker
import dev.dertyp.services.schedule.MusicBrainzWorker
import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.ScheduledTaskConfigurationService
import dev.dertyp.testing.RecordedEntityEvents
import dev.dertyp.testing.created
import dev.dertyp.testing.deleted
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.entityEventsModule
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.relaxedTaskLogService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTime

class LibraryReactionsTest : KoinTest {
    private lateinit var database: Database
    private val events = RecordedEntityEvents().subscribeLibraryReactions()
    private val publisher = events.publisher
    private val scheduleService = events.subscribe(ScheduleService())
    private val albumService = mockk<AlbumService>()
    private val libraryMergeService = mockk<LibraryMergeService>()
    private val redisSearchService = mockk<RedisSearchService>()
    private val rebuilds = Channel<Unit>(Channel.UNLIMITED)
    private val merges = Channel<Unit>(Channel.UNLIMITED)
    private val removals = Channel<Pair<SearchIndexEntityType, List<UUID>>>(Channel.UNLIMITED)
    private val postIndexRuns = Channel<String>(Channel.UNLIMITED)
    private val postIndexKeys = setOf(TaskKeys.MUSICBRAINZ_WORKER, TaskKeys.IMAGE_ANALYSIS, TaskKeys.AUDIO_START_ANALYSIS)

    private fun setup(dialect: DbDialect) {
        val configService = mockk<ScheduledTaskConfigurationService>()
        every { configService.configurationsFlow } returns emptyFlow()
        coEvery { albumService.rebuildVersionGroups() } coAnswers {
            rebuilds.send(Unit)
            0
        }
        coEvery { libraryMergeService.mergeDuplicateAlbums() } coAnswers {
            merges.send(Unit)
            0
        }
        every { redisSearchService.isEnabled() } returns true
        every { redisSearchService.remove(any(), any()) } answers {
            removals.trySend(firstArg<SearchIndexEntityType>() to secondArg<Collection<UUID>>().toList())
        }

        startKoin {
            modules(module {
                includes(entityEventsModule(events))
                single { albumService }
                single { libraryMergeService }
                single { redisSearchService }
                single { configService }
                single { relaxedTaskLogService() }
                single { MusicBrainzWorker() }
                single { ImageAnalysisWorker() }
                single { AudioStartAnalysisWorker() }
            })
        }
        database = TestDatabase.connect(dialect, "library_reactions_test")
        transaction(database) { SchemaUtils.create(TimecodeTagTable, *entityChangeTables) }
        postIndexKeys.forEach { key -> scheduleService.registerManagedTask(key, key) { postIndexRuns.send(key) } }
    }

    @AfterEach
    fun tearDown() = runBlocking {
        events.stop()
        ApplicationScope.scope.coroutineContext.cancelChildren()
        stopKoin()
        TestDatabase.cleanUp()
    }

    private suspend fun <T> Channel<T>.next(): T = withTimeout(5.seconds) { receive() }

    private suspend fun <T> Channel<T>.assertNothingMore(within: Duration = 200.milliseconds) =
        assertNull(withTimeoutOrNull(within) { receive() })

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `every kind of album event rebuilds the version groups once after the quiet period`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val album = UUID.randomUUID()
            val other = UUID.randomUUID()
            val albumWrites = listOf<() -> Unit>(
                { publisher.created(EntityType.ALBUM, listOf(album)) },
                { publisher.updated(EntityType.ALBUM, listOf(album)) },
                { publisher.albumsLinkedToMusicBrainz(listOf(album)) },
                { publisher.merging(EntityType.ALBUM, album, listOf(other)) },
                { publisher.deleting(EntityType.ALBUM, listOf(album)) },
            )

            for (write in albumWrites) {
                val waited = measureTime {
                    transaction(database) { write() }
                    rebuilds.next()
                }
                assertTrue(waited >= 1.seconds, "rebuild after $waited")
            }

            transaction(database) {
                publisher.created(EntityType.SONG, listOf(UUID.randomUUID()))
                publisher.updated(EntityType.ARTIST, listOf(UUID.randomUUID()))
                publisher.created(EntityType.ARTIST, listOf(UUID.randomUUID()))
                publisher.merging(EntityType.SONG, UUID.randomUUID(), listOf(UUID.randomUUID()))
                publisher.deleting(EntityType.ARTIST, listOf(UUID.randomUUID()))
            }
            rebuilds.assertNothingMore(1500.milliseconds)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `album events of several commits lead to one rebuild after the last of them`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val album = UUID.randomUUID()
            val albumWrites = listOf<() -> Unit>(
                { publisher.created(EntityType.ALBUM, listOf(album)) },
                { publisher.updated(EntityType.ALBUM, listOf(album)) },
                { publisher.albumsLinkedToMusicBrainz(listOf(album)) },
                { publisher.updated(EntityType.ALBUM, listOf(UUID.randomUUID())) },
            )

            var lastWrite = TimeSource.Monotonic.markNow()
            for (write in albumWrites) {
                delay(300.milliseconds)
                lastWrite = TimeSource.Monotonic.markNow()
                transaction(database) { write() }
            }

            rebuilds.next()
            assertTrue(lastWrite.elapsedNow() >= 1.seconds, "rebuild ${lastWrite.elapsedNow()} after the last write")
            merges.next()
            rebuilds.assertNothingMore(1500.milliseconds)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a request without an event joins the rebuild of the album events around it`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            events.versionGroupTrigger.requestRebuild()
            transaction(database) { publisher.updated(EntityType.ALBUM, listOf(UUID.randomUUID())) }
            events.versionGroupTrigger.requestRebuild()

            rebuilds.next()
            rebuilds.assertNothingMore(1500.milliseconds)

            val waited = measureTime {
                events.versionGroupTrigger.requestRebuild()
                rebuilds.next()
            }
            assertTrue(waited >= 1.seconds, "rebuild after $waited")
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a steady stream of album events does not postpone the rebuild beyond the upper bound`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val started = TimeSource.Monotonic.markNow()
            val stream = launch(Dispatchers.IO) {
                repeat(60) {
                    transaction(database) { publisher.updated(EntityType.ALBUM, listOf(UUID.randomUUID())) }
                    delay(200.milliseconds)
                }
            }

            withTimeout(9.seconds) { rebuilds.receive() }
            val waited = started.elapsedNow()
            assertTrue(stream.isActive)
            assertTrue(waited >= 5.seconds, "rebuild after $waited")

            stream.cancelAndJoin()
            transaction(database) { publisher.updated(EntityType.ALBUM, listOf(UUID.randomUUID())) }
            rebuilds.next()
            rebuilds.assertNothingMore(1500.milliseconds)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an album event during a running rebuild starts another one and does not cancel the first`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val release = CompletableDeferred<Unit>()
        val finished = Channel<Unit>(Channel.UNLIMITED)
        coEvery { albumService.rebuildVersionGroups() } coAnswers {
            rebuilds.send(Unit)
            release.await()
            finished.send(Unit)
            0
        }

        transaction(database) { publisher.created(EntityType.ALBUM, listOf(UUID.randomUUID())) }
        rebuilds.next()
        transaction(database) { publisher.updated(EntityType.ALBUM, listOf(UUID.randomUUID())) }
        rebuilds.next()
        finished.assertNothingMore()

        release.complete(Unit)
        repeat(2) { finished.next() }
        rebuilds.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the duplicate merge starts only after a MusicBrainz link`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = UUID.randomUUID()
        val other = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.ALBUM, listOf(album))
            publisher.updated(EntityType.ALBUM, listOf(other))
            publisher.merging(EntityType.ALBUM, album, listOf(UUID.randomUUID()))
            publisher.deleting(EntityType.ALBUM, listOf(UUID.randomUUID()))
        }
        rebuilds.next()
        merges.assertNothingMore()

        transaction(database) { publisher.albumsLinkedToMusicBrainz(listOf(album, other)) }

        merges.next()
        merges.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `search entries of deleted and merged away ids are removed after the commit`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val song = UUID.randomUUID()
            val artist = UUID.randomUUID()
            val keptAlbum = UUID.randomUUID()
            val mergedAlbum = UUID.randomUUID()

            transaction(database) {
                publisher.deleting(EntityType.SONG, listOf(song))
                publisher.deleting(EntityType.ARTIST, listOf(artist))
                publisher.merging(EntityType.ALBUM, keptAlbum, listOf(mergedAlbum))
                publisher.deleting(EntityType.USER_PLAYLIST, listOf(UUID.randomUUID()))
                publisher.deleting(EntityType.PLAYLIST, listOf(UUID.randomUUID()))
                publisher.deleting(EntityType.COLLECTION, listOf(UUID.randomUUID()))
                publisher.created(EntityType.SONG, listOf(UUID.randomUUID()))
                runBlocking { removals.assertNothingMore() }
            }

            assertEquals(
                setOf(
                    SearchIndexEntityType.SONG to listOf(song),
                    SearchIndexEntityType.ARTIST to listOf(artist),
                    SearchIndexEntityType.ALBUM to listOf(mergedAlbum),
                ),
                List(3) { removals.next() }.toSet()
            )
            removals.assertNothingMore()
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an id that is merged away and deleted in one transaction is removed from the search once`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        val kept = UUID.randomUUID()
        val mergedThenDeleted = UUID.randomUUID()
        val deletedThenMerged = UUID.randomUUID()
        val onlyDeleted = UUID.randomUUID()

        transaction(database) {
            publisher.deleting(EntityType.ALBUM, listOf(deletedThenMerged))
            publisher.merging(EntityType.ALBUM, kept, listOf(mergedThenDeleted, deletedThenMerged))
            publisher.deleting(EntityType.ALBUM, listOf(mergedThenDeleted, onlyDeleted))
        }

        assertEquals(
            listOf(onlyDeleted, mergedThenDeleted, deletedThenMerged).map { SearchIndexEntityType.ALBUM to it },
            List(2) { removals.next() }.flatMap { (type, ids) -> ids.map { type to it } }
        )
        removals.assertNothingMore()
        rebuilds.next()
        rebuilds.assertNothingMore()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `search removal does nothing while the redis search is disabled`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        every { redisSearchService.isEnabled() } returns false
        val album = UUID.randomUUID()

        transaction(database) {
            publisher.deleting(EntityType.ALBUM, listOf(album))
            publisher.merging(EntityType.SONG, UUID.randomUUID(), listOf(UUID.randomUUID()))
        }

        rebuilds.next()
        removals.assertNothingMore()
        verify(exactly = 0) { redisSearchService.remove(any(), any()) }
        assertTrue(deleted(EntityType.ALBUM, album) in recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `post index tasks are scheduled once per call and only after the commit`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val scheduler = launch { scheduleService.startService() }

        repeat(2) {
            scheduleService.schedulePostIndexTasks()
            assertEquals(postIndexKeys, List(3) { postIndexRuns.next() }.toSet())
            postIndexRuns.assertNothingMore()
        }

        transaction(database) {
            scheduleService.schedulePostIndexTasks()
            scheduleService.schedulePostIndexTasks()
            runBlocking { postIndexRuns.assertNothingMore() }
        }
        assertEquals(postIndexKeys, List(3) { postIndexRuns.next() }.toSet())
        postIndexRuns.assertNothingMore()

        stop(scheduler)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `no reaction starts for a rolled back write`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val scheduler = launch { scheduleService.startService() }
        val album = UUID.randomUUID()

        assertThrows<IllegalStateException> {
            transaction(database) {
                publisher.created(EntityType.ALBUM, listOf(album))
                publisher.updated(EntityType.ALBUM, listOf(UUID.randomUUID()))
                publisher.merging(EntityType.ALBUM, album, listOf(UUID.randomUUID()))
                publisher.deleting(EntityType.SONG, listOf(UUID.randomUUID()))
                publisher.deleting(EntityType.ALBUM, listOf(UUID.randomUUID()))
                publisher.albumsLinkedToMusicBrainz(listOf(album))
                scheduleService.schedulePostIndexTasks()
                throw IllegalStateException("rolled back")
            }
        }

        rebuilds.assertNothingMore(1500.milliseconds)
        merges.assertNothingMore()
        removals.assertNothingMore()
        postIndexRuns.assertNothingMore()
        assertEquals(emptySet<Any>(), recordedChanges(database))

        stop(scheduler)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a failing reaction affects neither the write nor the other reactions`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        coEvery { albumService.rebuildVersionGroups() } coAnswers {
            rebuilds.send(Unit)
            throw IllegalStateException("rebuild failed")
        }
        every { redisSearchService.remove(any(), any()) } answers {
            removals.trySend(firstArg<SearchIndexEntityType>() to secondArg<Collection<UUID>>().toList())
            throw IllegalStateException("redis down")
        }
        val album = UUID.randomUUID()
        val removed = UUID.randomUUID()

        transaction(database) {
            publisher.created(EntityType.ALBUM, listOf(album))
            publisher.deleting(EntityType.ALBUM, listOf(removed))
            publisher.albumsLinkedToMusicBrainz(listOf(album))
        }

        merges.next()
        assertEquals(SearchIndexEntityType.ALBUM to listOf(removed), removals.next())
        rebuilds.next()
        assertTrue(recordedChanges(database).containsAll(setOf(created(EntityType.ALBUM, album), deleted(EntityType.ALBUM, removed))))

        val song = UUID.randomUUID()
        transaction(database) {
            publisher.deleting(EntityType.SONG, listOf(song))
            publisher.albumsLinkedToMusicBrainz(listOf(album))
        }

        assertEquals(SearchIndexEntityType.SONG to listOf(song), removals.next())
        merges.next()
        rebuilds.next()
    }

    private suspend fun stop(scheduler: Job) {
        scheduleService.stopService()
        scheduler.join()
    }
}
