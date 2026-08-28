package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BackfillListenIsrcTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "backfill_listen_isrc_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ListenBrainzUserTable,
                ImageTable,
                AlbumTable,
                SongTable, SongVariantTable,
                MBRecordingTable,
                MBRecordingIsrcTable,
                ListenTable,
                ScheduledTaskLogTable,
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backfills the smallest ISRC (uppercased) from the recording mbid`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val recordingId = UUID.randomUUID()
        val listenId = UUID.randomUUID()
        transaction(database) {
            MBRecordingTable.insert {
                it[id] = recordingId
                it[title] = "Recording"
            }
            MBRecordingIsrcTable.insert {
                it[MBRecordingIsrcTable.recordingId] = recordingId
                it[isrc] = "isrcb"
            }
            MBRecordingIsrcTable.insert {
                it[MBRecordingIsrcTable.recordingId] = recordingId
                it[isrc] = "isrca"
            }
            ListenTable.insert {
                it[id] = listenId
                it[recordingMbid] = recordingId
                it[listenedAt] = 1000
                it[listenSource] = ListenSource.LISTENBRAINZ
            }
        }

        BackfillListenIsrc().migrate()

        val isrcs = transaction(database) {
            ListenTable.select(ListenTable.isrcs).where { ListenTable.id eq listenId }.single()[ListenTable.isrcs]
        }
        assertEquals("ISRCA,ISRCB", isrcs)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `leaves listens without a recording mbid untouched`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val listenId = UUID.randomUUID()
        transaction(database) {
            ListenTable.insert {
                it[id] = listenId
                it[listenedAt] = 1000
                it[listenSource] = ListenSource.LOCAL
            }
        }

        BackfillListenIsrc().migrate()

        val isrcs = transaction(database) {
            ListenTable.select(ListenTable.isrcs).where { ListenTable.id eq listenId }.single()[ListenTable.isrcs]
        }
        assertNull(isrcs)
    }
}
