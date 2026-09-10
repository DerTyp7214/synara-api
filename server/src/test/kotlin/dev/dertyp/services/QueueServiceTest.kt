package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.QueueInfo
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueUploadStart
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
import dev.dertyp.data.UserSong
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.QueueSyncDeviceTable
import dev.dertyp.db.SessionTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.db.UserQueueEntryTable
import dev.dertyp.db.UserQueueTable
import dev.dertyp.db.UserTable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class QueueServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: QueueService
    private lateinit var songService: SongService

    private val defaultMeta = QueueMeta(currentIndex = 0, shuffleMode = false, repeatMode = RepeatMode.OFF)

    private fun setup(dialect: DbDialect) {
        songService = mockk(relaxed = true)
        startKoin {
            modules(module {
                single { songService }
            })
        }

        database = TestDatabase.connect(dialect, "queue_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                SongTable, SongVariantTable,
                SessionTable,
                UserQueueTable,
                UserQueueEntryTable,
                QueueSyncDeviceTable,
            )
        }
        service = QueueService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        UserTable.insert {
            it[UserTable.id] = id
            it[username] = "user_$id"
            it[passwordHash] = "hash"
        }
        return id
    }

    private fun insertAlbum(): UUID {
        val id = UUID.randomUUID()
        AlbumTable.insert {
            it[AlbumTable.id] = id
            it[name] = "Album"
        }
        return id
    }

    private fun insertSong(albumId: UUID): UUID {
        val id = UUID.randomUUID()
        SongTable.insert {
            it[SongTable.id] = id
            it[title] = "Song"
            it[SongTable.albumId] = albumId
        }
        return id
    }

    private fun item(songId: UUID, queueId: Long, position: Int, shuffledPosition: Int? = null) =
        QueueItem(songId = songId, queueId = queueId, position = position, shuffledPosition = shuffledPosition)

    private suspend fun upload(
        userId: UUID,
        sessionId: UUID?,
        items: List<QueueItem>,
        meta: QueueMeta,
        baseVersion: Long = 0,
        force: Boolean = false,
        pageSize: Int = 100,
    ): QueueWriteResult {
        val start = service.beginUpload(userId, sessionId, baseVersion, force)
        check(start is QueueUploadStart.Started) { "expected upload to start, got $start" }
        items.chunked(pageSize).forEach { page -> service.uploadPage(userId, start.uploadId, page) }
        return service.commitUpload(userId, start.uploadId, meta)
    }

    private suspend fun newSession(userId: UUID): UUID = SessionService().createSession(userId, "agent", "127.0.0.1")

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a user without a stored queue is reported at version 0 with no entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }

        val info = service.getInfo(userId)
        assertEquals(0L, info.version)
        assertEquals(0, info.total)

        val page = service.getQueue(userId, 0, 200, false)
        assertEquals(0, page.total)
        assertTrue(page.data.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a chunked upload renumbers entries and advances to version 1`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..5).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = songIds.mapIndexed { index, songId -> item(songId, index.toLong(), index) }

        val result = upload(userId, sessionId, items, defaultMeta, pageSize = 2)

        check(result is QueueWriteResult.Ok)
        assertEquals(1L, result.info.version)
        assertEquals(5, result.info.total)
        assertEquals(sessionId, result.info.modifiedBySessionId)

        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(listOf(0, 1, 2, 3, 4), page.data.sortedBy { it.position }.map { it.position })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a stale baseVersion is rejected with a conflict carrying the device name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        service.setSyncEnabled(userId, sessionId, true, "Kitchen Echo")
        val first = upload(userId, sessionId, listOf(item(songId, 1, 0)), defaultMeta)
        check(first is QueueWriteResult.Ok)

        val result = service.insert(userId, sessionId, 0, 0, listOf(item(songId, 2, 0)), false)

        check(result is QueueWriteResult.Conflict)
        assertEquals(1L, result.info.version)
        assertEquals("Kitchen Echo", result.info.modifiedByDeviceName)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `force applies a write even past the base version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        val first = upload(userId, sessionId, listOf(item(songId, 1, 0)), defaultMeta)
        check(first is QueueWriteResult.Ok)

        val result = service.setCurrentIndex(userId, sessionId, 0, 0, true)

        check(result is QueueWriteResult.Ok)
        assertEquals(2L, result.info.version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `beginUpload discards a previous unstaged upload`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)

        val first = service.beginUpload(userId, sessionId, 0, false)
        check(first is QueueUploadStart.Started)
        service.uploadPage(userId, first.uploadId, listOf(item(songId, 1, 0)))

        val second = service.beginUpload(userId, sessionId, 0, false)
        check(second is QueueUploadStart.Started)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.commitUpload(userId, first.uploadId, defaultMeta) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an expired upload cannot be committed`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val sessionId = newSession(userId)
        service.uploadTtlMs = -1

        val start = service.beginUpload(userId, sessionId, 0, false)
        check(start is QueueUploadStart.Started)

        assertThrows<IllegalArgumentException> {
            runBlocking { service.commitUpload(userId, start.uploadId, defaultMeta) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getQueue paginates and reports the total and hasNextPage`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..5).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = songIds.mapIndexed { index, songId -> item(songId, index.toLong(), index) }
        upload(userId, sessionId, items, defaultMeta)

        val firstPage = service.getQueue(userId, 0, 2, false)
        assertEquals(5, firstPage.total)
        assertTrue(firstPage.hasNextPage)
        assertEquals(listOf(0, 1), firstPage.data.map { it.position })

        val lastPage = service.getQueue(userId, 2, 2, false)
        assertEquals(1, lastPage.data.size)
        assertFalse(lastPage.hasNextPage)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getQueue resolves songs only when includeSongs is true`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        upload(userId, sessionId, listOf(item(songId, 1, 0)), defaultMeta)
        val resolved = UserSong(id = songId, title = "Title", artists = emptyList(), album = null, duration = 1000, explicit = false, path = "path")

        val withoutSongs = service.getQueue(userId, 0, 10, false)
        assertNull(withoutSongs.data.single().song)
        coVerify(exactly = 0) { songService.byIds(any(), any()) }

        coEvery { songService.byIds(listOf(songId), userId) } returns listOf(resolved)
        val withSongs = service.getQueue(userId, 0, 10, true)
        assertEquals(resolved, withSongs.data.single().song)
        coVerify(exactly = 1) { songService.byIds(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `shuffled order survives a round trip`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..3).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = listOf(
            item(songIds[0], 1, 0, shuffledPosition = 2),
            item(songIds[1], 2, 1, shuffledPosition = 0),
            item(songIds[2], 3, 2, shuffledPosition = 1),
        )

        upload(userId, sessionId, items, defaultMeta.copy(shuffleMode = true))

        val byQueueId = service.getQueue(userId, 0, 10, false).data.associateBy { it.queueId }
        assertEquals(2, byQueueId.getValue(1).shuffledPosition)
        assertEquals(0, byQueueId.getValue(2).shuffledPosition)
        assertEquals(1, byQueueId.getValue(3).shuffledPosition)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `shuffledPosition is cleared while shuffle is off`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)

        upload(userId, sessionId, listOf(item(songId, 1, 0, shuffledPosition = 5)), defaultMeta.copy(shuffleMode = false))

        val page = service.getQueue(userId, 0, 10, false)
        assertNull(page.data.single().shuffledPosition)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `insert splices into the original order while shuffle is off`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..2).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val base = upload(userId, sessionId, listOf(item(songIds[0], 1, 0), item(songIds[1], 2, 1)), defaultMeta)
        check(base is QueueWriteResult.Ok)
        val newSongId = transaction(database) { insertSong(insertAlbum()) }

        val result = service.insert(userId, sessionId, base.info.version, 1, listOf(item(newSongId, 3, 0)), false)

        check(result is QueueWriteResult.Ok)
        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(listOf(1L, 3L, 2L), page.data.sortedBy { it.position }.map { it.queueId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `insert splices into the shuffled order and appends to the original order while shuffle is on`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..2).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val base = upload(
            userId, sessionId,
            listOf(item(songIds[0], 1, 0, shuffledPosition = 0), item(songIds[1], 2, 1, shuffledPosition = 1)),
            defaultMeta.copy(shuffleMode = true),
        )
        check(base is QueueWriteResult.Ok)
        val newSongId = transaction(database) { insertSong(insertAlbum()) }

        val result = service.insert(userId, sessionId, base.info.version, 1, listOf(item(newSongId, 3, 0)), false)

        check(result is QueueWriteResult.Ok)
        val byQueueId = service.getQueue(userId, 0, 10, false).data.associateBy { it.queueId }
        assertEquals(2, byQueueId.getValue(3).position)
        assertEquals(1, byQueueId.getValue(3).shuffledPosition)
        assertEquals(0, byQueueId.getValue(1).shuffledPosition)
        assertEquals(2, byQueueId.getValue(2).shuffledPosition)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `commit rejects duplicate queue ids`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        val start = service.beginUpload(userId, sessionId, 0, false)
        check(start is QueueUploadStart.Started)
        service.uploadPage(userId, start.uploadId, listOf(item(songId, 1, 0), item(songId, 1, 1)))

        assertThrows<IllegalArgumentException> {
            runBlocking { service.commitUpload(userId, start.uploadId, defaultMeta) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `commit silently drops entries referencing unknown songs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        val items = listOf(item(songId, 1, 0), item(UUID.randomUUID(), 2, 1))

        val result = upload(userId, sessionId, items, defaultMeta)

        check(result is QueueWriteResult.Ok)
        assertEquals(1, result.info.total)
        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(listOf(songId), page.data.map { it.songId })
        assertEquals(listOf(0), page.data.map { it.position })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `remove adjusts the current index to stay on the playing entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..3).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = listOf(item(songIds[0], 1, 0), item(songIds[1], 2, 1), item(songIds[2], 3, 2))
        val base = upload(userId, sessionId, items, defaultMeta.copy(currentIndex = 2))
        check(base is QueueWriteResult.Ok)
        assertEquals(2, base.info.currentIndex)

        val result = service.remove(userId, sessionId, base.info.version, listOf(1L), false)

        check(result is QueueWriteResult.Ok)
        assertEquals(1, result.info.currentIndex)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `remove clamps the current index when the playing entry is removed`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..2).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = listOf(item(songIds[0], 1, 0), item(songIds[1], 2, 1))
        val base = upload(userId, sessionId, items, defaultMeta.copy(currentIndex = 1))
        check(base is QueueWriteResult.Ok)

        val result = service.remove(userId, sessionId, base.info.version, listOf(2L), false)

        check(result is QueueWriteResult.Ok)
        assertEquals(0, result.info.currentIndex)
        assertEquals(1, result.info.total)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `move keeps the current index pointing at the playing entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..3).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = listOf(item(songIds[0], 1, 0), item(songIds[1], 2, 1), item(songIds[2], 3, 2))
        val base = upload(userId, sessionId, items, defaultMeta.copy(currentIndex = 1))
        check(base is QueueWriteResult.Ok)

        val result = service.move(userId, sessionId, base.info.version, 1L, 2, false)

        check(result is QueueWriteResult.Ok)
        assertEquals(0, result.info.currentIndex)
        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(2, page.data.single { it.queueId == 1L }.position)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `enabling shuffle starts a new order at the current entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..3).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = listOf(item(songIds[0], 1, 0), item(songIds[1], 2, 1), item(songIds[2], 3, 2))
        val base = upload(userId, sessionId, items, defaultMeta.copy(currentIndex = 2))
        check(base is QueueWriteResult.Ok)

        val result = service.setModes(userId, sessionId, base.info.version, true, RepeatMode.OFF, false)

        check(result is QueueWriteResult.Ok)
        assertEquals(0, result.info.currentIndex)
        assertTrue(result.info.shuffleMode)
        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(0, page.data.single { it.queueId == 3L }.shuffledPosition)
        assertEquals(setOf(0, 1, 2), page.data.mapNotNull { it.shuffledPosition }.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `disabling shuffle drops the shuffled order and restores the original current index`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..3).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        val items = listOf(
            item(songIds[0], 1, 0, shuffledPosition = 2),
            item(songIds[1], 2, 1, shuffledPosition = 0),
            item(songIds[2], 3, 2, shuffledPosition = 1),
        )
        val base = upload(userId, sessionId, items, defaultMeta.copy(shuffleMode = true, currentIndex = 0))
        check(base is QueueWriteResult.Ok)

        val result = service.setModes(userId, sessionId, base.info.version, false, RepeatMode.OFF, false)

        check(result is QueueWriteResult.Ok)
        assertFalse(result.info.shuffleMode)
        assertEquals(1, result.info.currentIndex)
        val page = service.getQueue(userId, 0, 10, false)
        assertTrue(page.data.all { it.shuffledPosition == null })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `every successful write advances the version and stamps the writer session`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val session1 = newSession(userId)
        val session2 = newSession(userId)

        val first = upload(userId, session1, listOf(item(songId, 1, 0)), defaultMeta)
        check(first is QueueWriteResult.Ok)
        assertEquals(1L, first.info.version)
        assertEquals(session1, first.info.modifiedBySessionId)

        val second = service.setCurrentIndex(userId, session2, first.info.version, 0, false)
        check(second is QueueWriteResult.Ok)
        assertEquals(2L, second.info.version)
        assertEquals(session2, second.info.modifiedBySessionId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a rejected delta op leaves the stored rows unchanged`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        val base = upload(userId, sessionId, listOf(item(songId, 1, 0)), defaultMeta)
        check(base is QueueWriteResult.Ok)

        val result = service.remove(userId, sessionId, 0, listOf(1L), false)

        check(result is QueueWriteResult.Conflict)
        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(1, page.total)
        assertEquals(1L, service.getInfo(userId).version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `observe emits after a successful write but not after a conflict`(dialect: DbDialect) = runTest {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val sessionId = newSession(userId)
        val results = mutableListOf<QueueInfo>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.observe(userId).collect { results.add(it) }
        }

        upload(userId, sessionId, listOf(item(songId, 1, 0)), defaultMeta)
        assertEquals(1, results.size)

        service.remove(userId, sessionId, 0, listOf(1L), false)
        assertEquals(1, results.size)

        job.cancel()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleting a song cascades and removes its queue entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assumeTrue(dialect == DbDialect.POSTGRES, "FK cascade requires foreign key enforcement")

        val (userId, songIds) = transaction(database) {
            val u = insertUser()
            val album = insertAlbum()
            u to (1..2).map { insertSong(album) }
        }
        val sessionId = newSession(userId)
        upload(userId, sessionId, listOf(item(songIds[0], 1, 0), item(songIds[1], 2, 1)), defaultMeta)

        transaction(database) { SongTable.deleteWhere { SongTable.id eq songIds[0] } }

        val page = service.getQueue(userId, 0, 10, false)
        assertEquals(1, page.total)
        assertEquals(listOf(songIds[1]), page.data.map { it.songId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getSyncDevices reports enabled devices and marks the calling session`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val userId = transaction(database) { insertUser() }
        val session1 = newSession(userId)
        val session2 = newSession(userId)

        service.setSyncEnabled(userId, session1, true, "Kitchen Echo")
        service.setSyncEnabled(userId, session2, true, "Living Room TV")

        val devices = service.getSyncDevices(userId, session1)
        assertEquals(setOf("Kitchen Echo", "Living Room TV"), devices.map { it.deviceName }.toSet())
        assertTrue(devices.single { it.sessionId == session1 }.isCurrent)
        assertFalse(devices.single { it.sessionId == session2 }.isCurrent)

        service.setSyncEnabled(userId, session2, false, "Living Room TV")
        val afterDisable = service.getSyncDevices(userId, session1)
        assertEquals(listOf(session1), afterDisable.map { it.sessionId })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ackSynced and writes update the device's lastSyncedVersion`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) {
            val u = insertUser()
            u to insertSong(insertAlbum())
        }
        val writer = newSession(userId)
        val reader = newSession(userId)
        service.setSyncEnabled(userId, writer, true, "Writer")
        service.setSyncEnabled(userId, reader, true, "Reader")

        val write = upload(userId, writer, listOf(item(songId, 1, 0)), defaultMeta)
        check(write is QueueWriteResult.Ok)

        val afterWrite = service.getSyncDevices(userId, writer)
        assertEquals(1L, afterWrite.single { it.sessionId == writer }.lastSyncedVersion)
        assertEquals(0L, afterWrite.single { it.sessionId == reader }.lastSyncedVersion)

        service.ackSynced(userId, reader, 1L)

        val afterAck = service.getSyncDevices(userId, writer)
        assertEquals(1L, afterAck.single { it.sessionId == reader }.lastSyncedVersion)
    }
}
