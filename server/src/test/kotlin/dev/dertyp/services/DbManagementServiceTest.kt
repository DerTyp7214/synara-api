package dev.dertyp.services

import com.github.luben.zstd.ZstdOutputStream
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.EntityChangeConfig
import dev.dertyp.data.EntityType
import dev.dertyp.data.TaskStatus
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.EntityChangeTrackingTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncServiceTable
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserTable
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
        service = DbManagementService(EntityChangeRecorder())
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

    private fun insertLibrary(imageId: UUID, albumId: UUID, songId: UUID, label: String) {
        transaction(database) {
            ImageTable.insert {
                it[id] = imageId
                it[path] = "/covers/$label.jpg"
                it[imageHash] = "hash-$label"
                it[origin] = "test"
            }
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album $label"
                it[cover] = imageId
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song $label"
                it[this.albumId] = albumId
                it[cover] = imageId
            }
        }
    }

    private fun assertLibrary(imageId: UUID, albumId: UUID, songId: UUID, label: String) {
        transaction(database) {
            val image = ImageTable.selectAll().single()
            assertEquals(imageId, image[ImageTable.id].value)
            assertEquals("/covers/$label.jpg", image[ImageTable.path])

            val album = AlbumTable.selectAll().single()
            assertEquals(albumId, album[AlbumTable.id].value)
            assertEquals("Album $label", album[AlbumTable.name])
            assertEquals(imageId, album[AlbumTable.cover]?.value)

            val song = SongTable.selectAll().single()
            assertEquals(songId, song[SongTable.id].value)
            assertEquals("Song $label", song[SongTable.title])
            assertEquals(albumId, song[SongTable.albumId].value)
            assertEquals(imageId, song[SongTable.cover]?.value)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `restore over existing rows respects foreign keys between image album and song`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            val imageId = UUID.randomUUID()
            val albumId = UUID.randomUUID()
            val songId = UUID.randomUUID()
            insertLibrary(imageId, albumId, songId, "backup")

            val exportedData = service.exportData()

            transaction(database) {
                SongTable.deleteAll()
                AlbumTable.deleteAll()
                ImageTable.deleteAll()
            }
            insertLibrary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "current")

            service.importData(exportedData)

            assertLibrary(imageId, albumId, songId, "backup")
        }

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `backup with children before parents restores with foreign keys enforced`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        insertLibrary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "current")

        val imageId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val sections = listOf(
            "song" to mapOf(
                "id" to DbValue.DbUuid(songId.toString()),
                "title" to DbValue.DbString("Song backup"),
                "albumId" to DbValue.DbUuid(albumId.toString()),
                "cover" to DbValue.DbUuid(imageId.toString())
            ),
            "album" to mapOf(
                "id" to DbValue.DbUuid(albumId.toString()),
                "name" to DbValue.DbString("Album backup"),
                "cover" to DbValue.DbUuid(imageId.toString())
            ),
            "image" to mapOf(
                "id" to DbValue.DbUuid(imageId.toString()),
                "path" to DbValue.DbString("/covers/backup.jpg"),
                "hash" to DbValue.DbString("hash-backup"),
                "origin" to DbValue.DbString("test")
            )
        )

        val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())
        val blob = ByteArrayOutputStream()
        ZstdOutputStream(blob).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                dos.writeInt(DbManagementService.FORMAT_V2_MARKER)
                dos.writeInt(sections.size)
                sections.forEach { (tableName, row) ->
                    dos.writeUTF(tableName)
                    val bytes = Cbor.encodeToByteArray(rowSerializer, row)
                    dos.writeInt(bytes.size)
                    dos.write(bytes)
                    dos.writeInt(-1)
                }
            }
        }

        service.importData(blob.toByteArray())

        assertLibrary(imageId, albumId, songId, "backup")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `restore replaces users that own sync service rows under the legacy set null constraint`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)

            val backupUserId = UUID.randomUUID()
            transaction(database) {
                UserTable.insert {
                    it[id] = backupUserId
                    it[username] = "backup-user"
                    it[passwordHash] = "hash"
                }
            }
            val exportedData = service.exportData()

            transaction(database) {
                UserTable.deleteAll()
                SchemaUtils.drop(SyncServiceTable)
                SchemaUtils.create(LegacySyncServiceTable)
            }

            val currentUserId = UUID.randomUUID()
            transaction(database) {
                UserTable.insert {
                    it[id] = currentUserId
                    it[username] = "current-user"
                    it[passwordHash] = "hash"
                }
                SyncServiceTable.insert {
                    it[name] = "spotify"
                    it[ownerId] = currentUserId
                    it[scope] = "scope"
                    it[accessToken] = "access"
                    it[refreshToken] = "refresh"
                    it[expiresIn] = 3600
                    it[tokenType] = "Bearer"
                    it[userId] = 1L
                    it[createdAt] = 0L
                }
            }

            assertThrows(Exception::class.java) {
                transaction(database) {
                    UserTable.deleteWhere { UserTable.id eq currentUserId }
                }
            }

            service.importData(exportedData)

            transaction(database) {
                val users = UserTable.selectAll().toList()
                assertEquals(1, users.size)
                assertEquals(backupUserId, users[0][UserTable.id].value)
                assertEquals(0, SyncServiceTable.selectAll().count())
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

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a restore discards the recorded changes and restarts the tracking at the restore`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val recorder = EntityChangeRecorder()
            val userId = UUID.randomUUID()
            val backedUp = UUID.randomUUID()
            val later = UUID.randomUUID()
            transaction(database) {
                UserTable.insert {
                    it[id] = userId
                    it[username] = "testuser"
                    it[passwordHash] = "hash"
                }
                ArtistTable.insert {
                    it[id] = backedUp
                    it[name] = "Backed Up"
                }
                EntityChangeTrackingTable.insert {
                    it[id] = ROW_ID
                    it[startedAt] = 1_000
                }
                recorder.created(EntityType.ARTIST, listOf(backedUp))
                recorder.likesChanged(userId, EntityType.ARTIST, listOf(backedUp))
            }
            val exportedData = service.exportData()
            transaction(database) {
                ArtistTable.insert {
                    it[id] = later
                    it[name] = "Later"
                }
                recorder.created(EntityType.ARTIST, listOf(later))
            }
            val before = System.currentTimeMillis()

            service.importData(exportedData)

            transaction(database) {
                assertEquals(listOf(backedUp), ArtistTable.selectAll().map { it[ArtistTable.id].value })
                assertEquals(0, EntityChangeTable.selectAll().count())
                assertEquals(0, UserEntityChangeTable.selectAll().count())
                assertEquals(0, EntityChangeScopeTable.selectAll().count())
                val started = EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt]
                assertTrue(started >= before)
            }
            val window = EntityChangeService(EntityChangeConfig(retentionDays = 30)).getWindow()
            assertTrue(window.availableSince >= before)
        }

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a restore that fails after it replaced data still restarts the tracking`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val recorder = EntityChangeRecorder()
            val existing = UUID.randomUUID()
            val restoredArtist = UUID.randomUUID()
            transaction(database) {
                ArtistTable.insert {
                    it[id] = existing
                    it[name] = "Existing"
                }
                AlbumTable.insert {
                    it[id] = UUID.randomUUID()
                    it[name] = "Existing Album"
                }
                EntityChangeTrackingTable.insert {
                    it[id] = ROW_ID
                    it[startedAt] = 1_000
                }
                recorder.created(EntityType.ARTIST, listOf(existing))
            }
            val artistRows = listOf(
                mapOf(
                    "id" to DbValue.DbUuid(restoredArtist.toString()),
                    "name" to DbValue.DbString("Restored")
                )
            )
            val brokenAlbumRows = listOf(
                mapOf(
                    "id" to DbValue.DbUuid(UUID.randomUUID().toString()),
                    "name" to DbValue.DbString("Broken"),
                    "cover" to DbValue.DbUuid(UUID.randomUUID().toString())
                )
            )
            val blob = ByteArrayOutputStream()
            ZstdOutputStream(blob).use { zstd ->
                DataOutputStream(zstd).use { dos ->
                    dos.writeInt(2)
                    for (table in listOf(TableData("artist", artistRows), TableData("album", brokenAlbumRows))) {
                        val cborBytes = Cbor.encodeToByteArray(table)
                        dos.writeUTF(table.tableName)
                        dos.writeInt(cborBytes.size)
                        dos.write(cborBytes)
                    }
                }
            }
            val before = System.currentTimeMillis()

            val failure = runCatching { service.importData(blob.toByteArray()) }.exceptionOrNull()

            assertTrue(failure != null)
            transaction(database) {
                assertEquals(0, AlbumTable.selectAll().count())
                assertEquals(0, EntityChangeTable.selectAll().count())
                val started = EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt]
                assertTrue(started >= before)
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an import that fails before it replaced anything keeps the recorded changes`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val recorder = EntityChangeRecorder()
            val existing = UUID.randomUUID()
            transaction(database) {
                ArtistTable.insert {
                    it[id] = existing
                    it[name] = "Existing"
                }
                EntityChangeTrackingTable.insert {
                    it[id] = ROW_ID
                    it[startedAt] = 1_000
                }
                recorder.created(EntityType.ARTIST, listOf(existing))
            }

            val failure = runCatching { service.importData(byteArrayOf(1, 2, 3)) }.exceptionOrNull()

            assertTrue(failure != null)
            transaction(database) {
                assertEquals(1, EntityChangeTable.selectAll().count())
                assertEquals(1_000, EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt])
            }
        }
}

private object LegacySyncServiceTable : Table("syncService") {
    val name = varchar("name", 255)
    val ownerId = reference("ownerId", UserTable.id, onDelete = ReferenceOption.SET_NULL)
    val scope = text("scope")
    val accessToken = text("accessToken")
    val refreshToken = text("refreshToken")
    val expiresIn = integer("expiresIn")
    val tokenType = text("token_type")
    val userId = long("userId")
    val createdAt = long("createdAt")

    override val primaryKey = PrimaryKey(name, ownerId)
}
