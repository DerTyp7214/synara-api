package dev.dertyp.services

import com.github.luben.zstd.ZstdOutputStream
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TaskStatus
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.UserTable
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.*
import java.util.UUID

class DbManagementServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: DbManagementService

    private fun getDiscoveredTables(service: DbManagementService): List<Table> {
        val method = service.javaClass.getDeclaredMethod("getTables")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(service) as List<Table>
    }

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }

        database = TestDatabase.connect(dialect, "db_mgmt_test")
        service = DbManagementService()
        val tables = getDiscoveredTables(service).toTypedArray()
        
        transaction(database) {
            SchemaUtils.create(*tables)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `export and import should preserve data`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        val userId = UUID.randomUUID()
        val artistId = UUID.randomUUID()
        
        transaction(database) {
            UserTable.insert {
                it[id] = userId
                it[username] = "testuser"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = artistId
                it[name] = "Test Artist"
            }
        }

        val exportedData = service.exportData()
        val tables = getDiscoveredTables(service).toTypedArray()

        transaction(database) {
            SchemaUtils.drop(*tables)
            SchemaUtils.create(*tables)
        }
        
        transaction(database) {
            assertEquals(0, UserTable.selectAll().count())
            assertEquals(0, ArtistTable.selectAll().count())
        }

        service.importData(exportedData)

        transaction(database) {
            val users = UserTable.selectAll().toList()
            assertEquals(1, users.size)
            assertEquals("testuser", users[0][UserTable.username])
            assertEquals(userId, users[0][UserTable.id].value)

            val artists = ArtistTable.selectAll().toList()
            assertEquals(1, artists.size)
            assertEquals("Test Artist", artists[0][ArtistTable.name])
            assertEquals(artistId, artists[0][ArtistTable.id].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stream export and import should preserve binary columns and chunked rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val rowCount = 600
        val logIds = (0 until rowCount).map { UUID.randomUUID() }

        transaction(database) {
            logIds.forEachIndexed { index, logId ->
                ScheduledTaskLogTable.insert {
                    it[id] = logId
                    it[taskName] = "task-$index"
                    it[startTime] = index.toLong()
                    it[endTime] = index.toLong() + 1
                    it[status] = TaskStatus.SUCCESS
                    it[details] = byteArrayOf(index.toByte(), (index + 1).toByte(), 7)
                    it[progress] = 100.0
                }
            }
        }

        val output = ByteArrayOutputStream()
        service.exportData(output)

        val tables = getDiscoveredTables(service).toTypedArray()
        transaction(database) {
            SchemaUtils.drop(*tables)
            SchemaUtils.create(*tables)
        }

        transaction(database) {
            assertEquals(0, ScheduledTaskLogTable.selectAll().count())
        }

        service.importData(ByteArrayInputStream(output.toByteArray()))

        transaction(database) {
            assertEquals(rowCount.toLong(), ScheduledTaskLogTable.selectAll().count())

            val lastIndex = rowCount - 1
            val row = ScheduledTaskLogTable
                .selectAll()
                .where { ScheduledTaskLogTable.taskName eq "task-$lastIndex" }
                .single()
            assertEquals(logIds[lastIndex], row[ScheduledTaskLogTable.id].value)
            assertEquals(TaskStatus.SUCCESS, row[ScheduledTaskLogTable.status])
            assertArrayEquals(
                byteArrayOf(lastIndex.toByte(), (lastIndex + 1).toByte(), 7),
                row[ScheduledTaskLogTable.details]
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `legacy export format should still import`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        val artistId = UUID.randomUUID()
        val legacyRows = listOf(
            mapOf(
                "id" to DbValue.DbUuid(artistId.toString()),
                "name" to DbValue.DbString("Legacy Artist")
            )
        )

        val legacyBlob = ByteArrayOutputStream()
        ZstdOutputStream(legacyBlob).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                val cborBytes = Cbor.encodeToByteArray(TableData("artist", legacyRows))
                dos.writeInt(1)
                dos.writeUTF("artist")
                dos.writeInt(cborBytes.size)
                dos.write(cborBytes)
            }
        }

        service.importData(legacyBlob.toByteArray())

        transaction(database) {
            val artists = ArtistTable.selectAll().toList()
            assertEquals(1, artists.size)
            assertEquals("Legacy Artist", artists[0][ArtistTable.name])
            assertEquals(artistId, artists[0][ArtistTable.id].value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should automatically discover tables`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val discoveredTables = getDiscoveredTables(service)
        
        assertTrue(discoveredTables.size > 30)
        assertTrue(discoveredTables.any { it.tableName == "user" })
        assertTrue(discoveredTables.any { it.tableName == "song" })
        assertTrue(discoveredTables.any { it.tableName == "mb_artist" })
    }
}
