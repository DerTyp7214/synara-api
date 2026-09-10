package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.QueueItem
import dev.dertyp.data.QueueMeta
import dev.dertyp.data.QueueUploadStart
import dev.dertyp.data.QueueWriteResult
import dev.dertyp.data.RepeatMode
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
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.days

class QueueCleanupTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: QueueService

    private val defaultMeta = QueueMeta(currentIndex = 0, shuffleMode = false, repeatMode = RepeatMode.OFF)

    private fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<SongService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "queue_cleanup_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                AlbumTable,
                ArtistTable,
                SongTable,
                SongVariantTable,
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
        val newId = UUID.randomUUID()
        UserTable.insert {
            it[id] = newId
            it[username] = "user_$newId"
            it[passwordHash] = "hash"
        }
        return newId
    }

    private fun insertSong(): UUID {
        val newAlbumId = UUID.randomUUID()
        AlbumTable.insert {
            it[id] = newAlbumId
            it[name] = "Album"
        }
        val newSongId = UUID.randomUUID()
        SongTable.insert {
            it[id] = newSongId
            it[title] = "Song"
            it[albumId] = newAlbumId
        }
        return newSongId
    }

    private suspend fun uploadQueue(userId: UUID, songId: UUID) {
        val start = service.beginUpload(userId, null, 0, false)
        check(start is QueueUploadStart.Started) { "expected upload to start, got $start" }
        service.uploadPage(userId, start.uploadId, listOf(QueueItem(songId = songId, queueId = 1, position = 0)))
        val result = service.commitUpload(userId, start.uploadId, defaultMeta)
        check(result is QueueWriteResult.Ok) { "expected the upload to commit, got $result" }
    }

    private fun backdate(user: UUID, at: Long) {
        UserQueueTable.update({ UserQueueTable.userId eq user }) {
            it[UserQueueTable.modifiedAt] = at
        }
    }

    private suspend fun commitEmptyQueue(userId: UUID): Long {
        val current = service.getInfo(userId)
        val start = service.beginUpload(userId, null, current.version, false)
        check(start is QueueUploadStart.Started) { "expected upload to start, got $start" }
        val result = service.commitUpload(userId, start.uploadId, defaultMeta)
        check(result is QueueWriteResult.Ok) { "expected the upload to commit, got $result" }
        return result.info.version
    }

    private fun staleTimestamp(): Long = Instant.now().toEpochMilli() - 40.days.inWholeMilliseconds

    private fun countEntries(userId: UUID): Int = UserQueueEntryTable
        .selectAll()
        .where { UserQueueEntryTable.userId eq userId }
        .count()
        .toInt()

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a stale queue is cleared but keeps its meta row at a bumped version`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) { insertUser() to insertSong() }
        uploadQueue(userId, songId)

        val before = service.getInfo(userId)
        assertEquals(1, before.total)

        transaction(database) { backdate(userId, staleTimestamp()) }

        val cleared = service.cleanupStaleQueues()

        assertEquals(1, cleared)
        assertEquals(0, transaction(database) { countEntries(userId) })

        val after = service.getInfo(userId)
        assertEquals(0, after.total)
        assertEquals(before.version + 1, after.version)
        assertNull(after.modifiedBySessionId)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a fresh queue is left untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) { insertUser() to insertSong() }
        uploadQueue(userId, songId)

        val before = service.getInfo(userId)
        val cleared = service.cleanupStaleQueues()

        assertEquals(0, cleared)

        val after = service.getInfo(userId)
        assertEquals(1, after.total)
        assertEquals(before.version, after.version)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a stale queue without entries is left alone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (userId, songId) = transaction(database) { insertUser() to insertSong() }
        uploadQueue(userId, songId)

        val emptied = commitEmptyQueue(userId)
        transaction(database) { backdate(userId, staleTimestamp()) }

        val cleared = service.cleanupStaleQueues()

        assertEquals(0, cleared)
        assertEquals(emptied, service.getInfo(userId).version)
    }
}
