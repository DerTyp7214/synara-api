package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.db.*
import dev.dertyp.db.migrations.V1_103__StoreEnumsByName
import dev.dertyp.services.DatabaseManager
import dev.dertyp.services.ISyncService.SyncServiceType
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class StoreEnumsByNameMigrationTest : KoinTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private var user = ""
    private var password = ""

    private object LegacyListenTable : UUIDTable("listen") {
        val listenBrainzUserId = reference("listenBrainzUserId", ListenBrainzUserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
        val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE).nullable()
        val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
        val recordingMbid = javaUUID("recordingMbid").nullable()
        val recordingMsid = javaUUID("recordingMsid").nullable()
        val releaseMbid = javaUUID("releaseMbid").nullable()
        val isrcs = text("isrcs").nullable()
        val artistMbids = text("artistMbids").nullable()
        val trackName = text("trackName").nullable()
        val artistName = text("artistName").nullable()
        val releaseName = text("releaseName").nullable()
        val listenedAt = long("listenedAt")
        val legacySource = enumeration<ListenSource>("source")
        val msPlayed = long("msPlayed").nullable()
        val updatedAt = long("updatedAt").default(0L)

        init {
            uniqueIndex(listenBrainzUserId, listenedAt)
            index(false, songId)
            index(false, recordingMbid)
            index(false, userId)
            index(false, userId, listenedAt)
            index(false, legacySource, updatedAt)
        }
    }

    private object LegacyFavSyncTable : Table("favSync") {
        val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
        val service = enumeration<SyncServiceType>("service")
        val syncedAt = long("syncedAt")

        override val primaryKey = PrimaryKey(userId, service)
    }

    private fun setup(dialect: DbDialect): Boolean {
        if (dialect == DbDialect.POSTGRES && TestDatabase.postgresContainer == null) {
            println("Skipping PostgreSQL enum migration test because Docker is not available.")
            return false
        }

        database = TestDatabase.connect(dialect, "store_enums_by_name_test")
        if (dialect == DbDialect.SQLITE) {
            DriverManager.getConnection(database.url).use { connection ->
                connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            }
        }
        val driver = if (dialect == DbDialect.POSTGRES) "org.postgresql.Driver" else "org.sqlite.JDBC"
        user = TestDatabase.postgresContainer?.takeIf { dialect == DbDialect.POSTGRES }?.username ?: "sa"
        password = TestDatabase.postgresContainer?.takeIf { dialect == DbDialect.POSTGRES }?.password ?: ""
        databaseManager = DatabaseManager(
            ServerConfig(
                MapApplicationConfig(
                    "storage.driverClassName" to driver,
                    "storage.jdbcURL" to database.url,
                    "storage.user" to user,
                    "storage.password" to password
                )
            )
        )
        startKoin { modules(module { single { databaseManager } }) }

        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ListenBrainzUserTable,
                ImageTable,
                AlbumTable,
                SongTable, SongVariantTable,
                LegacyListenTable,
                LegacyFavSyncTable,
            )
        }
        return true
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun runMigration() {
        DriverManager.getConnection(database.url, user, password).use { connection ->
            connection.autoCommit = false
            val context = mockk<Context>()
            every { context.connection } returns connection
            V1_103__StoreEnumsByName().migrate(context)
            connection.commit()
        }
    }

    private fun columnNames(connection: Connection, table: String): Set<String> =
        connection.metaData.getColumns(null, null, table, null).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("COLUMN_NAME")) }
        }

    private fun insertUser(name: String): UUID = transaction(database) {
        UserTable.insertAndGetId {
            it[username] = name
            it[passwordHash] = "hash"
        }.value
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `listen sources keep their values when stored by name`(dialect: DbDialect) {
        if (!setup(dialect)) return
        val userId = insertUser("listener")
        val listenBrainz = UUID.randomUUID()
        val local = UUID.randomUUID()
        transaction(database) {
            LegacyListenTable.insert {
                it[id] = listenBrainz
                it[LegacyListenTable.userId] = userId
                it[listenedAt] = 1000
                it[legacySource] = ListenSource.LISTENBRAINZ
                it[updatedAt] = 1000
            }
            LegacyListenTable.insert {
                it[id] = local
                it[LegacyListenTable.userId] = userId
                it[listenedAt] = 2000
                it[legacySource] = ListenSource.LOCAL
                it[updatedAt] = 2000
            }
        }

        runMigration()

        val sources = transaction(database) {
            ListenTable.select(ListenTable.id, ListenTable.listenSource)
                .associate { it[ListenTable.id].value to it[ListenTable.listenSource] }
        }
        assertEquals(mapOf(listenBrainz to ListenSource.LISTENBRAINZ, local to ListenSource.LOCAL), sources)

        val localOnly = transaction(database) {
            ListenTable.select(ListenTable.id)
                .where { ListenTable.listenSource eq ListenSource.LOCAL }
                .map { it[ListenTable.id].value }
        }
        assertEquals(listOf(local), localOnly)

        val (columns, indexedColumns) = DriverManager.getConnection(database.url, user, password).use { connection ->
            columnNames(connection, "listen") to connection.metaData.getIndexInfo(null, null, "listen", false, false).use { rs ->
                buildSet { while (rs.next()) rs.getString("COLUMN_NAME")?.let { add(it.trim('"')) } }
            }
        }
        assertFalse(columns.any { it.equals("source", ignoreCase = true) })
        assertTrue(indexedColumns.any { it.equals("listenSource", ignoreCase = true) })
        assertFalse(indexedColumns.any { it.equals("source", ignoreCase = true) })
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fav sync entries keep their services and stay unique per user and service`(dialect: DbDialect) {
        if (!setup(dialect)) return
        val first = insertUser("first")
        val second = insertUser("second")
        transaction(database) {
            LegacyFavSyncTable.insert {
                it[userId] = first
                it[service] = SyncServiceType.tidal
                it[syncedAt] = 1000
            }
            LegacyFavSyncTable.insert {
                it[userId] = first
                it[service] = SyncServiceType.unknown
                it[syncedAt] = 2000
            }
            LegacyFavSyncTable.insert {
                it[userId] = second
                it[service] = SyncServiceType.tidal
                it[syncedAt] = 3000
            }
        }

        runMigration()

        val entries = transaction(database) {
            FavSyncTable.selectAll()
                .map { Triple(it[FavSyncTable.userId].value, it[FavSyncTable.service], it[FavSyncTable.syncedAt]) }
                .toSet()
        }
        assertEquals(
            setOf(
                Triple(first, SyncServiceType.tidal, 1000L),
                Triple(first, SyncServiceType.unknown, 2000L),
                Triple(second, SyncServiceType.tidal, 3000L),
            ),
            entries
        )

        transaction(database) {
            FavSyncTable.upsert {
                it[userId] = first
                it[service] = SyncServiceType.tidal
                it[syncedAt] = 4000
            }
        }
        val firstTidal = transaction(database) {
            FavSyncTable.selectAll()
                .where { FavSyncTable.userId eq first }
                .andWhere { FavSyncTable.service eq SyncServiceType.tidal }
                .map { it[FavSyncTable.syncedAt] }
        }
        assertEquals(listOf(4000L), firstTidal)

        val columns = DriverManager.getConnection(database.url, user, password).use { columnNames(it, "favSync") }
        assertFalse(columns.any { it.equals("service", ignoreCase = true) })
    }

    private fun deleteUserWithoutForeignKeys(userId: UUID) {
        val unchecked = Database.connect(database.url.substringBefore('?'), "org.sqlite.JDBC")
        transaction(unchecked) {
            UserTable.deleteWhere { UserTable.id eq userId }
        }
    }

    @Test
    fun `fav sync rows of deleted users are dropped on sqlite with foreign keys enforced`() {
        if (!setup(DbDialect.SQLITE)) return
        val kept = insertUser("kept")
        val gone = insertUser("gone")
        transaction(database) {
            LegacyFavSyncTable.insert {
                it[userId] = kept
                it[service] = SyncServiceType.tidal
                it[syncedAt] = 1000
            }
            LegacyFavSyncTable.insert {
                it[userId] = gone
                it[service] = SyncServiceType.tidal
                it[syncedAt] = 2000
            }
            LegacyFavSyncTable.insert {
                it[userId] = gone
                it[service] = SyncServiceType.unknown
                it[syncedAt] = 3000
            }
        }
        deleteUserWithoutForeignKeys(gone)

        runMigration()

        val entries = transaction(database) {
            FavSyncTable.selectAll()
                .map { Triple(it[FavSyncTable.userId].value, it[FavSyncTable.service], it[FavSyncTable.syncedAt]) }
                .toSet()
        }
        assertEquals(setOf(Triple(kept, SyncServiceType.tidal, 1000L)), entries)
    }

    @Test
    fun `listen rows of deleted users migrate on sqlite with foreign keys enforced`() {
        if (!setup(DbDialect.SQLITE)) return
        val kept = insertUser("kept")
        val gone = insertUser("gone")
        val keptListen = UUID.randomUUID()
        val orphanListen = UUID.randomUUID()
        transaction(database) {
            LegacyListenTable.insert {
                it[id] = keptListen
                it[userId] = kept
                it[listenedAt] = 1000
                it[legacySource] = ListenSource.LOCAL
            }
            LegacyListenTable.insert {
                it[id] = orphanListen
                it[userId] = gone
                it[listenedAt] = 2000
                it[legacySource] = ListenSource.LISTENBRAINZ
            }
        }
        deleteUserWithoutForeignKeys(gone)

        runMigration()

        val sources = transaction(database) {
            ListenTable.select(ListenTable.id, ListenTable.listenSource)
                .associate { it[ListenTable.id].value to it[ListenTable.listenSource] }
        }
        assertEquals(mapOf(keptListen to ListenSource.LOCAL, orphanListen to ListenSource.LISTENBRAINZ), sources)
    }
}
