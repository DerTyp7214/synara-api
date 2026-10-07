package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.EntityChangeConfig
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.SchemaTables
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.EntityType
import dev.dertyp.data.TaskStatus
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.BackupSchemaException
import dev.dertyp.db.BackupSchemaException.Reason
import dev.dertyp.db.CustomMigrationTable
import dev.dertyp.db.ArtistTable
import dev.dertyp.db.EntityChangeScopeTable
import dev.dertyp.db.EntityChangeTable
import dev.dertyp.db.EntityChangeTrackingTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.MigrationBase
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.SearchIndexEntityType
import dev.dertyp.db.SearchIndexQueueTable
import dev.dertyp.db.SessionTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SyncServiceTable
import dev.dertyp.db.TsVectorColumnType
import dev.dertyp.db.UserEntityChangeTable
import dev.dertyp.db.UserSongTable
import dev.dertyp.db.UserTable
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.*
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

class DbManagementServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: DbManagementService
    private val databaseManager = mockk<DatabaseManager> { every { schemaVersion() } returns "1.109" }

    private companion object {
        val SINGLE_CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(20)
    }

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

        service = DbManagementService(EntityChangeRecorder(), databaseManager)
        database = TestDatabase.connect(dialect, "db_mgmt_test", *getDiscoveredTables(service).toTypedArray())
    }

    private fun setupFromBase(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }
        database = TestDatabase.connectMigrated(dialect, "db_mgmt_base_test")
        service = DbManagementService(EntityChangeRecorder(), databaseManager)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun sections(data: ByteArray): Map<String, List<Map<String, DbValue>>> {
        val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())
        return ZstdInputStream(ByteArrayInputStream(data)).use { zstd ->
            DataInputStream(zstd).use { dis ->
                assertEquals(DbManagementService.FORMAT_V2_MARKER, dis.readInt())
                (0 until dis.readInt()).associate {
                    val name = dis.readUTF()
                    val rows = generateSequence {
                        val size = dis.readInt()
                        if (size < 0) null else Cbor.decodeFromByteArray(rowSerializer, ByteArray(size).also(dis::readFully))
                    }.toList()
                    name to rows
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun dumpOf(sections: List<Pair<String, List<Map<String, DbValue>>>>): ByteArray {
        val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())
        val blob = ByteArrayOutputStream()
        ZstdOutputStream(blob).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                dos.writeInt(DbManagementService.FORMAT_V2_MARKER)
                dos.writeInt(sections.size)
                sections.forEach { (tableName, rows) ->
                    dos.writeUTF(tableName)
                    rows.forEach { row ->
                        val bytes = Cbor.encodeToByteArray(rowSerializer, row)
                        dos.writeInt(bytes.size)
                        dos.write(bytes)
                    }
                    dos.writeInt(-1)
                }
            }
        }
        return blob.toByteArray()
    }

    private fun queuedForSearch() = transaction(database) {
        SearchIndexQueueTable.selectAll()
            .map { it[SearchIndexQueueTable.entityType] to it[SearchIndexQueueTable.entityId] }
            .toSet()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an export leaves out the derived search data and its import keeps the rows`(dialect: DbDialect) =
        runBlocking {
            setupFromBase(dialect)
            val imageId = UUID.randomUUID()
            val albumId = UUID.randomUUID()
            val songId = UUID.randomUUID()
            val performer = UUID.randomUUID()
            insertLibrary(imageId, albumId, songId, "backup")
            transaction(database) {
                ArtistTable.insert {
                    it[id] = performer
                    it[name] = "Artist backup"
                }
            }
            val searchable = setOf(
                SearchIndexEntityType.SONG to songId,
                SearchIndexEntityType.ALBUM to albumId,
                SearchIndexEntityType.ARTIST to performer,
            )
            if (dialect == DbDialect.POSTGRES) {
                transaction(database) {
                    listOf(SongTable, AlbumTable, ArtistTable).forEach { table ->
                        exec("UPDATE ${table.tableName} SET search_vector = to_tsvector('simple', 'backup')")
                        assertNotNull(table.selectAll().single()[table.columns.single { it.columnType is TsVectorColumnType }])
                    }
                }
                assertEquals(searchable, queuedForSearch())
            }

            val exported = service.exportData()

            val dumped = sections(exported)
            assertEquals(
                (SchemaTables.all - SearchIndexQueueTable).map { it.tableName }.toSet() +
                    DbManagementService.SCHEMA_VERSION_SECTION,
                dumped.keys
            )
            listOf(SongTable, AlbumTable, ArtistTable).forEach { table ->
                val row = dumped.getValue(table.tableName).single()
                val expected = table.columns.filterNot { it.columnType is TsVectorColumnType }.map { it.name }.toSet()
                assertEquals(expected, row.keys)
                assertEquals(table.columns.size - 1, row.size)
            }

            transaction(database) {
                SongTable.deleteAll()
                AlbumTable.deleteAll()
                ArtistTable.deleteAll()
                if (dialect == DbDialect.POSTGRES) SearchIndexQueueTable.deleteAll()
            }

            service.importData(exported)

            assertLibrary(imageId, albumId, songId, "backup")
            transaction(database) {
                val artist = ArtistTable.selectAll().single()
                assertEquals(performer, artist[ArtistTable.id].value)
                assertEquals("Artist backup", artist[ArtistTable.name])
                assertNull(artist[ArtistTable.searchVector])
                assertNull(SongTable.selectAll().single()[SongTable.searchVector])
                assertNull(AlbumTable.selectAll().single()[AlbumTable.searchVector])
            }
            if (dialect == DbDialect.POSTGRES) assertEquals(searchable, queuedForSearch())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a dump that carries search vectors and the search queue imports without them`(dialect: DbDialect) =
        runBlocking {
            setupFromBase(dialect)
            insertLibrary(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "current")
            val imageId = UUID.randomUUID()
            val albumId = UUID.randomUUID()
            val songId = UUID.randomUUID()
            val performer = UUID.randomUUID()
            val vanished = UUID.randomUUID()
            val vector = DbValue.DbString("'backup':1A")
            val data = dumpOf(
                listOf(
                    "customMigration" to lastCustomMigrationRows(),
                    "image" to listOf(
                        mapOf(
                            "id" to DbValue.DbUuid(imageId.toString()),
                            "path" to DbValue.DbString("/covers/backup.jpg"),
                            "hash" to DbValue.DbString("hash-backup"),
                            "origin" to DbValue.DbString("test")
                        )
                    ),
                    "album" to listOf(
                        mapOf(
                            "id" to DbValue.DbUuid(albumId.toString()),
                            "name" to DbValue.DbString("Album backup"),
                            "cover" to DbValue.DbUuid(imageId.toString()),
                            "search_vector" to vector
                        )
                    ),
                    "artist" to listOf(
                        mapOf(
                            "id" to DbValue.DbUuid(performer.toString()),
                            "name" to DbValue.DbString("Artist backup"),
                            "search_vector" to vector
                        )
                    ),
                    "search_index_queue" to listOf(
                        mapOf(
                            "id" to DbValue.DbInt(7),
                            "entity_type" to DbValue.DbString(SearchIndexEntityType.SONG.name),
                            "entity_id" to DbValue.DbUuid(vanished.toString())
                        )
                    ),
                    "song" to listOf(
                        mapOf(
                            "id" to DbValue.DbUuid(songId.toString()),
                            "title" to DbValue.DbString("Song backup"),
                            "albumId" to DbValue.DbUuid(albumId.toString()),
                            "cover" to DbValue.DbUuid(imageId.toString()),
                            "inserted" to DbValue.DbLong(1),
                            "search_vector" to vector
                        )
                    ),
                )
            )

            service.importData(data)

            assertLibrary(imageId, albumId, songId, "backup")
            transaction(database) {
                val artist = ArtistTable.selectAll().single()
                assertEquals(performer, artist[ArtistTable.id].value)
                assertNull(artist[ArtistTable.searchVector])
                assertNull(SongTable.selectAll().single()[SongTable.searchVector])
                assertNull(AlbumTable.selectAll().single()[AlbumTable.searchVector])
            }
            if (dialect == DbDialect.POSTGRES) {
                val queued = queuedForSearch()
                assertTrue(
                    queued.containsAll(
                        setOf(
                            SearchIndexEntityType.SONG to songId,
                            SearchIndexEntityType.ALBUM to albumId,
                            SearchIndexEntityType.ARTIST to performer,
                        )
                    )
                )
                assertFalse(SearchIndexEntityType.SONG to vanished in queued)
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
                val migrationBytes = Cbor.encodeToByteArray(TableData("customMigration", lastCustomMigrationRows()))
                val cborBytes = Cbor.encodeToByteArray(TableData("artist", legacyRows))
                dos.writeInt(2)
                dos.writeUTF("customMigration")
                dos.writeInt(migrationBytes.size)
                dos.write(migrationBytes)
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

    private fun lastCustomMigrationRows() = listOf(
        mapOf(
            "id" to DbValue.DbString(MigrationBase.LAST_CUSTOM_MIGRATION),
            "executedAt" to DbValue.DbLong(1_000)
        )
    )

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
            "customMigration" to lastCustomMigrationRows().single(),
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

    private fun rowsOf(tables: List<Table>): Map<String, List<String>> = transaction(database) {
        tables.associate { table ->
            table.tableName to table.selectAll().map { row ->
                table.columns.associate { column ->
                    column.name to when (val value = row[column]) {
                        is ByteArray -> value.toList()
                        is EntityID<*> -> value.value
                        else -> value
                    }
                }.toString()
            }.sorted()
        }
    }

    private fun seedAccountWithLibrary(recorder: EntityChangeRecorder): UUID {
        val account = UUID.randomUUID()
        val performer = UUID.randomUUID()
        val track = UUID.randomUUID()
        insertLibrary(UUID.randomUUID(), UUID.randomUUID(), track, "current")
        transaction(database) {
            UserTable.insert {
                it[id] = account
                it[username] = "current-user"
                it[passwordHash] = "hash"
            }
            SessionTable.insert {
                it[userId] = account
            }
            ArtistTable.insert {
                it[id] = performer
                it[name] = "Current Artist"
            }
            UserSongTable.insert {
                it[userId] = account
                it[songId] = track
            }
            EntityChangeTrackingTable.deleteAll()
            EntityChangeTrackingTable.insert {
                it[id] = ROW_ID
                it[startedAt] = 1_000
            }
            recorder.created(EntityType.ARTIST, listOf(performer))
        }
        return account
    }

    private fun libraryDump(
        imageId: UUID,
        albumId: UUID,
        songId: UUID,
        performer: UUID,
        account: UUID,
        likes: List<Map<String, DbValue>>?
    ): ByteArray = dumpOf(
        listOfNotNull(
            "customMigration" to lastCustomMigrationRows(),
            "image" to listOf(
                mapOf(
                    "id" to DbValue.DbUuid(imageId.toString()),
                    "path" to DbValue.DbString("/covers/backup.jpg"),
                    "hash" to DbValue.DbString("hash-backup"),
                    "origin" to DbValue.DbString("test")
                )
            ),
            "album" to listOf(
                mapOf(
                    "id" to DbValue.DbUuid(albumId.toString()),
                    "name" to DbValue.DbString("Album backup"),
                    "cover" to DbValue.DbUuid(imageId.toString())
                )
            ),
            "artist" to listOf(
                mapOf(
                    "id" to DbValue.DbUuid(performer.toString()),
                    "name" to DbValue.DbString("Artist backup")
                )
            ),
            "user" to listOf(
                mapOf(
                    "id" to DbValue.DbUuid(account.toString()),
                    "username" to DbValue.DbString("backup-user"),
                    "passwordHash" to DbValue.DbString("hash")
                )
            ),
            "song" to listOf(
                mapOf(
                    "id" to DbValue.DbUuid(songId.toString()),
                    "title" to DbValue.DbString("Song backup"),
                    "albumId" to DbValue.DbUuid(albumId.toString()),
                    "cover" to DbValue.DbUuid(imageId.toString()),
                    "inserted" to DbValue.DbLong(1)
                )
            ),
            likes?.let { "userSong" to it },
        )
    )

    private fun likeOfMissingSong(account: UUID) = listOf(
        mapOf(
            "userId" to DbValue.DbUuid(account.toString()),
            "songId" to DbValue.DbUuid(UUID.randomUUID().toString())
        )
    )

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a restore that fails midway changes nothing and does not restart the tracking`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val recorder = EntityChangeRecorder()
            val existing = UUID.randomUUID()
            val restoredArtist = UUID.randomUUID()
            val account = UUID.randomUUID()
            transaction(database) {
                UserTable.insert {
                    it[id] = account
                    it[username] = "current-user"
                    it[passwordHash] = "hash"
                }
                SessionTable.insert {
                    it[userId] = account
                }
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
                    dos.writeInt(3)
                    val tableData = listOf(
                        TableData("customMigration", lastCustomMigrationRows()),
                        TableData("artist", artistRows),
                        TableData("album", brokenAlbumRows)
                    )
                    for (table in tableData) {
                        val cborBytes = Cbor.encodeToByteArray(table)
                        dos.writeUTF(table.tableName)
                        dos.writeInt(cborBytes.size)
                        dos.write(cborBytes)
                    }
                }
            }
            val before = rowsOf(getDiscoveredTables(service))

            val failure = runCatching { service.importData(blob.toByteArray()) }.exceptionOrNull()

            assertTrue(failure is SQLException)
            assertEquals(before, rowsOf(getDiscoveredTables(service)))
            transaction(database) {
                assertEquals(listOf(account), UserTable.selectAll().map { it[UserTable.id].value })
                assertEquals(listOf(account), SessionTable.selectAll().map { it[SessionTable.userId].value })
                assertEquals(listOf(existing), ArtistTable.selectAll().map { it[ArtistTable.id].value })
                assertEquals(1, AlbumTable.selectAll().count())
                assertEquals(1, EntityChangeTable.selectAll().count())
                assertEquals(1_000, EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt])
            }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a restore that fails in a late table after many tables were replaced changes nothing`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val account = seedAccountWithLibrary(EntityChangeRecorder())
            val restoredAccount = UUID.randomUUID()
            val data = libraryDump(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                restoredAccount,
                likeOfMissingSong(restoredAccount)
            )
            val order = getDiscoveredTables(service)
            val replacedFirst = listOf(ImageTable, AlbumTable, ArtistTable, UserTable, SessionTable, SongTable)
            assertTrue(order.indexOf(UserSongTable) > replacedFirst.maxOf { order.indexOf(it) })
            val before = rowsOf(order)
            assertTrue(replacedFirst.all { before.getValue(it.tableName).isNotEmpty() })

            val failure = runCatching { service.importData(data) }.exceptionOrNull()

            assertTrue(failure is SQLException)
            assertEquals(before, rowsOf(order))
            transaction(database) {
                assertEquals(listOf(account), UserTable.selectAll().map { it[UserTable.id].value })
                assertEquals(listOf(account), SessionTable.selectAll().map { it[SessionTable.userId].value })
                assertEquals(1, EntityChangeTable.selectAll().count())
                assertEquals(1_000, EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt])
            }
        }

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a dump cut off in the middle of a table's rows is rejected by the scan before the version check`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        seedAccountWithLibrary(EntityChangeRecorder())
        val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())
        val artistRows = (0 until 3).map {
            Cbor.encodeToByteArray(
                rowSerializer,
                mapOf("id" to DbValue.DbUuid(UUID.randomUUID().toString()), "name" to DbValue.DbString("Restored $it"))
            )
        }
        val blob = ByteArrayOutputStream()
        ZstdOutputStream(blob).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                dos.writeInt(DbManagementService.FORMAT_V2_MARKER)
                dos.writeInt(3)
                dos.writeUTF("customMigration")
                val migration = Cbor.encodeToByteArray(rowSerializer, lastCustomMigrationRows().single())
                dos.writeInt(migration.size)
                dos.write(migration)
                dos.writeInt(-1)
                dos.writeUTF("artist")
                artistRows.dropLast(1).forEach {
                    dos.writeInt(it.size)
                    dos.write(it)
                }
                dos.writeInt(artistRows.last().size)
                dos.write(artistRows.last(), 0, artistRows.last().size / 2)
            }
        }
        val order = getDiscoveredTables(service)
        val before = rowsOf(order)

        val failure = runCatching { service.importData(blob.toByteArray()) }.exceptionOrNull()

        assertTrue(failure is EOFException)
        verify(exactly = 0) { databaseManager.schemaVersion() }
        assertEquals(before, rowsOf(order))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `on the migration base a failed import leaves rows and search queue untouched and the repaired dump imports`(
        dialect: DbDialect
    ) = runBlocking {
        setupFromBase(dialect)
        val account = seedAccountWithLibrary(EntityChangeRecorder())
        val imageId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val songId = UUID.randomUUID()
        val performer = UUID.randomUUID()
        val restoredAccount = UUID.randomUUID()
        val searchable = setOf(
            SearchIndexEntityType.SONG to songId,
            SearchIndexEntityType.ALBUM to albumId,
            SearchIndexEntityType.ARTIST to performer,
        )
        val tables = when (dialect) {
            DbDialect.POSTGRES -> SchemaTables.all
            DbDialect.SQLITE -> SchemaTables.all - SearchIndexQueueTable
        }
        val before = rowsOf(tables)
        if (dialect == DbDialect.POSTGRES) {
            assertEquals(3, before.getValue(SearchIndexQueueTable.tableName).size)
        }

        val failure = runCatching {
            service.importData(
                libraryDump(imageId, albumId, songId, performer, restoredAccount, likeOfMissingSong(restoredAccount))
            )
        }.exceptionOrNull()

        assertTrue(failure is SQLException)
        assertEquals(before, rowsOf(tables))
        if (dialect == DbDialect.POSTGRES) assertTrue(queuedForSearch().none { it in searchable })
        transaction(database) {
            assertEquals(listOf(account), UserTable.selectAll().map { it[UserTable.id].value })
            assertEquals(1_000, EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt])
        }
        val started = System.currentTimeMillis()

        service.importData(libraryDump(imageId, albumId, songId, performer, restoredAccount, null))

        assertLibrary(imageId, albumId, songId, "backup")
        transaction(database) {
            assertEquals(listOf(restoredAccount), UserTable.selectAll().map { it[UserTable.id].value })
            assertEquals(0, SessionTable.selectAll().count())
            assertEquals(0, UserSongTable.selectAll().count())
            assertEquals(listOf(performer), ArtistTable.selectAll().map { it[ArtistTable.id].value })
            assertEquals(0, EntityChangeTable.selectAll().count())
            assertTrue(EntityChangeTrackingTable.selectAll().single()[EntityChangeTrackingTable.startedAt] >= started)
        }
        if (dialect == DbDialect.POSTGRES) assertTrue(queuedForSearch().containsAll(searchable))
    }

    private fun productionSqlite(file: File): DatabaseManager {
        startKoin {
            modules(module {
                single { mockk<ImageService>(relaxed = true) }
            })
        }
        val manager = DatabaseManager(
            ServerConfig(
                MapApplicationConfig(
                    "storage.driverClassName" to "org.sqlite.JDBC",
                    "storage.jdbcURL" to "jdbc:sqlite:${file.absolutePath}",
                    "storage.user" to "",
                    "storage.password" to "",
                )
            )
        )
        manager.init()
        database = checkNotNull(TransactionManager.primaryDatabase)
        assertTrue(file.absolutePath in database.url)
        service = DbManagementService(EntityChangeRecorder(), manager)
        return manager
    }

    @Test
    fun `imports that commit and that fail late run on the single connection of a production sqlite pool`() {
        val file = File.createTempFile("db_mgmt_single_connection", ".db")
        val manager = productionSqlite(file)
        try {
            val restoredAccount = UUID.randomUUID()
            val performer = UUID.randomUUID()
            val imageId = UUID.randomUUID()
            val albumId = UUID.randomUUID()
            val songId = UUID.randomUUID()
            seedAccountWithLibrary(EntityChangeRecorder())
            val tables = SchemaTables.all - SearchIndexQueueTable
            val before = rowsOf(tables)

            val failure = assertTimeoutPreemptively(SINGLE_CONNECTION_TIMEOUT, ThrowingSupplier {
                runBlocking {
                    runCatching {
                        service.importData(
                            libraryDump(
                                imageId,
                                albumId,
                                songId,
                                performer,
                                restoredAccount,
                                likeOfMissingSong(restoredAccount)
                            )
                        )
                    }.exceptionOrNull()
                }
            })

            assertTrue(failure is SQLException)
            assertEquals(before, rowsOf(tables))

            val users = assertTimeoutPreemptively(SINGLE_CONNECTION_TIMEOUT, ThrowingSupplier {
                runBlocking {
                    service.importData(libraryDump(imageId, albumId, songId, performer, restoredAccount, null))
                    val exported = service.exportData()
                    service.importData(exported)
                    dbQuery { UserTable.selectAll().map { it[UserTable.id].value } }
                }
            })

            assertEquals(listOf(restoredAccount), users)
            assertLibrary(imageId, albumId, songId, "backup")
        } finally {
            manager.close()
            file.delete()
        }
    }

    @Test
    fun `an import called inside a transaction joins it across dispatchers on a single sqlite connection`() {
        val file = File.createTempFile("db_mgmt_single_connection", ".db")
        val manager = productionSqlite(file)
        try {
            val restoredAccount = UUID.randomUUID()
            val performer = UUID.randomUUID()
            val marker = UUID.randomUUID()
            seedAccountWithLibrary(EntityChangeRecorder())
            val data = libraryDump(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                performer,
                restoredAccount,
                null
            )
            val tables = SchemaTables.all - SearchIndexQueueTable
            val before = rowsOf(tables)

            val failure = assertTimeoutPreemptively(SINGLE_CONNECTION_TIMEOUT, ThrowingSupplier {
                runBlocking {
                    runCatching {
                        dbQuery {
                            val outer = TransactionManager.current()
                            ArtistTable.insert {
                                it[id] = marker
                                it[name] = "Marker"
                            }
                            withContext(Dispatchers.Default) {
                                assertSame(outer, TransactionManager.current())
                                service.importData(data)
                            }
                            dbQuery {
                                assertSame(outer, TransactionManager.current())
                                assertEquals(listOf(performer), ArtistTable.selectAll().map { it[ArtistTable.id].value })
                                assertEquals(listOf(restoredAccount), UserTable.selectAll().map { it[UserTable.id].value })
                            }
                            throw IllegalStateException("abort the surrounding transaction")
                        }
                    }.exceptionOrNull()
                }
            })

            assertTrue(failure is IllegalStateException)
            assertEquals(before, rowsOf(tables))
        } finally {
            manager.close()
            file.delete()
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an import that fails with an sql error is attempted once`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        seedAccountWithLibrary(EntityChangeRecorder())
        val restoredAccount = UUID.randomUUID()
        val recorder = mockk<EntityChangeRecorder> {
            every { restartTracking() } answers {
                UserSongTable.insert {
                    it[userId] = restoredAccount
                    it[songId] = UUID.randomUUID()
                }
            }
        }
        val failing = DbManagementService(recorder, databaseManager)
        val order = getDiscoveredTables(service)
        val before = rowsOf(order)

        val failure = runCatching {
            failing.importData(
                libraryDump(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    restoredAccount,
                    null
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is SQLException)
        verify(exactly = 1) { recorder.restartTracking() }
        assertEquals(before, rowsOf(order))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a transaction that an import joined is not run again after the import failed`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            seedAccountWithLibrary(EntityChangeRecorder())
            val restoredAccount = UUID.randomUUID()
            val data = libraryDump(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                restoredAccount,
                likeOfMissingSong(restoredAccount)
            )
            val order = getDiscoveredTables(service)
            val before = rowsOf(order)
            var runs = 0

            val failure = runCatching {
                dbQuery {
                    runs++
                    service.importData(data)
                }
            }.exceptionOrNull()

            assertTrue(failure is SQLException)
            assertEquals(1, runs)
            assertEquals(before, rowsOf(order))
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

    private data class Counts(val users: List<UUID>, val artists: List<UUID>, val customMigrations: List<String>)

    private fun counts() = transaction(database) {
        Counts(
            UserTable.selectAll().map { it[UserTable.id].value },
            ArtistTable.selectAll().map { it[ArtistTable.id].value },
            CustomMigrationTable.selectAll().map { it[CustomMigrationTable.id] }
        )
    }

    private fun seedCurrentData(): Counts {
        transaction(database) {
            UserTable.insert {
                it[id] = UUID.randomUUID()
                it[username] = "current-user"
                it[passwordHash] = "hash"
            }
            ArtistTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Current Artist"
            }
            CustomMigrationTable.insert {
                it[id] = "SomethingElse"
                it[executedAt] = 5
            }
        }
        return counts()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun dump(version: String?, customMigrations: List<String>?, artistId: UUID): ByteArray {
        val sections = buildList {
            if (version != null) add(DbManagementService.SCHEMA_VERSION_SECTION to mapOf("version" to DbValue.DbString(version)))
            customMigrations?.forEach {
                add("customMigration" to mapOf("id" to DbValue.DbString(it), "executedAt" to DbValue.DbLong(1)))
            }
            add("artist" to mapOf("id" to DbValue.DbUuid(artistId.toString()), "name" to DbValue.DbString("Restored")))
        }
        val tableNames = sections.map { it.first }.distinct()
        val rowSerializer = MapSerializer(String.serializer(), DbValue.serializer())
        val blob = ByteArrayOutputStream()
        ZstdOutputStream(blob).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                dos.writeInt(DbManagementService.FORMAT_V2_MARKER)
                dos.writeInt(tableNames.size)
                tableNames.forEach { tableName ->
                    dos.writeUTF(tableName)
                    sections.filter { it.first == tableName }.forEach { (_, row) ->
                        val bytes = Cbor.encodeToByteArray(rowSerializer, row)
                        dos.writeInt(bytes.size)
                        dos.write(bytes)
                    }
                    dos.writeInt(-1)
                }
            }
        }
        return blob.toByteArray()
    }

    private fun assertRefusedUnchanged(data: ByteArray, reason: Reason, backupVersion: String?) = runBlocking {
        val before = seedCurrentData()

        val refusal = assertThrows(BackupSchemaException::class.java) {
            runBlocking { service.importData(data) }
        }

        assertEquals(reason, refusal.reason)
        assertEquals(backupVersion, refusal.backupVersion)
        assertEquals("1.109", refusal.serverVersion)
        assertEquals(before, counts())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an export carries the schema version and imports on a server at or above it`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val artistId = UUID.randomUUID()
            transaction(database) {
                ArtistTable.insert {
                    it[id] = artistId
                    it[name] = "Exported"
                }
            }
            every { databaseManager.schemaVersion() } returns "1.110"
            val exported = service.exportData()
            transaction(database) { ArtistTable.deleteAll() }

            every { databaseManager.schemaVersion() } returns "1.111"
            service.importData(exported)

            assertEquals(listOf(artistId), counts().artists)
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a backup without a version but with the last custom migration is accepted`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val artistId = UUID.randomUUID()
        seedCurrentData()

        service.importData(dump(null, listOf("Early", MigrationBase.LAST_CUSTOM_MIGRATION), artistId))

        val after = counts()
        assertEquals(listOf(artistId), after.artists)
        assertEquals(setOf("Early", MigrationBase.LAST_CUSTOM_MIGRATION), after.customMigrations.toSet())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a backup without a version and without the last custom migration is refused and changes nothing`(
        dialect: DbDialect
    ) {
        setup(dialect)
        assertRefusedUnchanged(dump(null, listOf("Early"), UUID.randomUUID()), Reason.UNVERSIONED_AND_UNFINISHED, null)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a backup without a version and without custom migrations is refused and changes nothing`(dialect: DbDialect) {
        setup(dialect)
        assertRefusedUnchanged(dump(null, null, UUID.randomUUID()), Reason.UNVERSIONED_AND_UNFINISHED, null)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a backup older than the migration base is refused and changes nothing`(dialect: DbDialect) {
        setup(dialect)
        val data = dump("1.108", listOf(MigrationBase.LAST_CUSTOM_MIGRATION), UUID.randomUUID())
        assertRefusedUnchanged(data, Reason.BELOW_BASE, "1.108")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a backup from a newer server is refused and changes nothing`(dialect: DbDialect) {
        setup(dialect)
        val data = dump("1.110", listOf(MigrationBase.LAST_CUSTOM_MIGRATION), UUID.randomUUID())
        assertRefusedUnchanged(data, Reason.NEWER_THAN_SERVER, "1.110")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a legacy format backup without the last custom migration is refused and changes nothing`(dialect: DbDialect) {
        setup(dialect)
        val blob = ByteArrayOutputStream()
        ZstdOutputStream(blob).use { zstd ->
            DataOutputStream(zstd).use { dos ->
                val cborBytes = Cbor.encodeToByteArray(
                    TableData("artist", listOf(mapOf("id" to DbValue.DbUuid(UUID.randomUUID().toString()), "name" to DbValue.DbString("Legacy"))))
                )
                dos.writeInt(1)
                dos.writeUTF("artist")
                dos.writeInt(cborBytes.size)
                dos.write(cborBytes)
            }
        }
        assertRefusedUnchanged(blob.toByteArray(), Reason.UNVERSIONED_AND_UNFINISHED, null)
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
