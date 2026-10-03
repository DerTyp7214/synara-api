package dev.dertyp.migrations

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.db.existingForeignKeyNames
import dev.dertyp.db.*
import dev.dertyp.db.migrations.V1_102__AddForeignKeyDeleteRules
import dev.dertyp.services.DatabaseManager
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.sql.DriverManager
import java.util.UUID

class ForeignKeyDeleteRulesMigrationTest : KoinTest {
    private lateinit var database: Database
    private var user = ""
    private var password = ""

    private object LegacyUserQueueTable : Table("userQueue") {
        val userId = reference("userId", UserTable.id, onDelete = ReferenceOption.CASCADE)
        val version = long("version").default(0)
        val modifiedAt = long("modifiedAt").default(0)
        val modifiedBySessionId = javaUUID("modifiedBySessionId").nullable()
        val modifiedByDeviceName = text("modifiedByDeviceName").nullable()
        val currentIndex = integer("currentIndex").default(0)
        val shuffleMode = bool("shuffleMode").default(false)
        val repeatMode = varchar("repeatMode", 16).default("OFF")
        val sourceId = text("sourceId").nullable()

        override val primaryKey = PrimaryKey(userId)
    }

    private fun setup(): Boolean {
        val container = TestDatabase.postgresContainer
        if (container == null) {
            println("Skipping PostgreSQL foreign key migration test because Docker is not available.")
            return false
        }

        database = TestDatabase.connect(DbDialect.POSTGRES, "foreign_key_delete_rules_test")
        user = container.username
        password = container.password
        val databaseManager = DatabaseManager(
            ServerConfig(
                MapApplicationConfig(
                    "storage.driverClassName" to "org.postgresql.Driver",
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
                ImageTable,
                AnimatedImageTable,
                AlbumTable,
                SongTable,
                ArtistTable,
                PlaylistTable,
                UserPlaylistTable,
                CollectionTable,
                SessionTable,
                RefreshTokenTable,
                LegacyUserQueueTable,
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
            V1_102__AddForeignKeyDeleteRules().migrate(context)
            connection.commit()
        }
    }

    private fun insertUser(name: String): UUID = transaction(database) {
        UserTable.insertAndGetId {
            it[username] = name
            it[passwordHash] = "hash"
        }.value
    }

    private fun insertSession(userId: UUID): UUID = transaction(database) {
        SessionTable.insertAndGetId { it[SessionTable.userId] = userId }.value
    }

    private fun insertQueue(userId: UUID, sessionId: UUID) = transaction(database) {
        LegacyUserQueueTable.insert {
            it[LegacyUserQueueTable.userId] = userId
            it[modifiedBySessionId] = sessionId
        }
    }

    @Test
    fun `queue writers pointing to missing sessions are cleared and later session deletes clear the writer`() {
        if (!setup()) return
        val kept = insertUser("kept")
        val orphaned = insertUser("orphaned")
        val session = insertSession(kept)
        insertQueue(kept, session)
        insertQueue(orphaned, UUID.randomUUID())

        runMigration()

        val writers = transaction(database) {
            UserQueueTable.selectAll()
                .associate { it[UserQueueTable.userId].value to it[UserQueueTable.modifiedBySessionId]?.value }
        }
        assertEquals(mapOf(kept to session, orphaned to null), writers)

        transaction(database) { SessionTable.deleteWhere { SessionTable.id eq session } }
        val afterDelete = transaction(database) {
            UserQueueTable.selectAll().where { UserQueueTable.userId eq kept }
                .single()[UserQueueTable.modifiedBySessionId]
        }
        assertNull(afterDelete)
    }

    @Test
    fun `deleting a user or an image follows the new delete rules without duplicate constraints`() {
        if (!setup()) return
        val owner = insertUser("owner")
        val session = insertSession(owner)
        val imageId = transaction(database) {
            val imageId = ImageTable.insertAndGetId {
                it[path] = "a/b.jpg"
                it[imageHash] = "hash"
                it[origin] = "test"
            }.value
            RefreshTokenTable.insert {
                it[tokenHash] = "token"
                it[userId] = owner
                it[sessionId] = session
                it[expiresAt] = 0
            }
            UserPlaylistTable.insert {
                it[name] = "playlist"
                it[description] = ""
                it[creator] = owner
                it[UserPlaylistTable.imageId] = imageId
            }
            CollectionTable.insert {
                it[name] = "collection"
                it[creator] = owner
            }
            ArtistTable.insert {
                it[name] = "artist"
                it[image] = imageId
            }
            imageId
        }
        insertQueue(owner, session)

        runMigration()

        val constraintCounts = DriverManager.getConnection(database.url, user, password).use { connection ->
            listOf(
                SessionTable.userId,
                RefreshTokenTable.userId,
                UserPlaylistTable.creator,
                CollectionTable.creator,
                SongTable.cover,
                AlbumTable.cover,
                ArtistTable.image,
                PlaylistTable.imageId,
                UserPlaylistTable.imageId,
                CollectionTable.imageId,
                UserQueueTable.modifiedBySessionId,
            ).map { existingForeignKeyNames(connection, it).size }
        }
        assertEquals(List(11) { 1 }, constraintCounts)

        transaction(database) { ImageTable.deleteWhere { ImageTable.id eq imageId } }
        val artistImage = transaction(database) { ArtistTable.selectAll().single()[ArtistTable.image] }
        assertNull(artistImage)

        transaction(database) { UserTable.deleteWhere { UserTable.id eq owner } }
        val remaining = transaction(database) {
            listOf(
                SessionTable.selectAll().count(),
                RefreshTokenTable.selectAll().count(),
                UserPlaylistTable.selectAll().count(),
                CollectionTable.selectAll().count(),
                UserQueueTable.selectAll().count(),
            )
        }
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), remaining)
    }
}
