package dev.dertyp.core.db

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.UserPlaylistTable
import dev.dertyp.db.UserTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.slf4j.LoggerFactory
import java.util.UUID

class SqliteForeignKeyCheckTest {
    private lateinit var database: Database
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger("SqliteForeignKeyCheck") as Logger

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
        TestDatabase.cleanUp()
    }

    private fun createSchema() = transaction(database) {
        SchemaUtils.create(UserTable, ImageTable, AlbumTable, SongTable, UserPlaylistTable)
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    @Test
    fun `violations are counted per table and logged as a warning`() = runBlocking {
        database = TestDatabase.connect(DbDialect.SQLITE, "fk_check_test", foreignKeys = false)
        createSchema()
        transaction(database) {
            val albumId = AlbumTable.insertAndGetId { it[AlbumTable.name] = "Album" }
            SongTable.insert {
                it[SongTable.title] = "Orphan cover one"
                it[SongTable.albumId] = albumId
                it[SongTable.cover] = EntityID(UUID.randomUUID(), ImageTable)
            }
            SongTable.insert {
                it[SongTable.title] = "Orphan cover two"
                it[SongTable.albumId] = albumId
                it[SongTable.cover] = EntityID(UUID.randomUUID(), ImageTable)
            }
            UserPlaylistTable.insert {
                it[UserPlaylistTable.name] = "Orphan playlist"
                it[UserPlaylistTable.description] = ""
                it[UserPlaylistTable.creator] = EntityID(UUID.randomUUID(), UserTable)
            }
        }

        val violations = SqliteForeignKeyCheck().run()

        assertEquals(mapOf("song" to 2, "userPlaylist" to 1), violations)
        val warning = warnings().single()
        assertTrue("3 violation(s)" in warning, warning)
        assertTrue("song: 2" in warning, warning)
        assertTrue("userPlaylist: 1" in warning, warning)
    }

    @Test
    fun `a consistent database logs no warning`() = runBlocking {
        database = TestDatabase.connect(DbDialect.SQLITE, "fk_check_test")
        createSchema()
        transaction(database) {
            AlbumTable.insert { it[AlbumTable.name] = "Album" }
        }

        val violations = SqliteForeignKeyCheck().run()

        assertEquals(emptyMap<String, Int>(), violations)
        assertEquals(emptyList<String>(), warnings())
        assertTrue(appender.list.any { it.level == Level.INFO && "V1_100" in it.formattedMessage })
    }

    @ParameterizedTest
    @EnumSource(value = DbDialect::class, names = ["POSTGRES"])
    fun `other dialects are not checked`(dialect: DbDialect) = runBlocking {
        database = TestDatabase.connect(dialect, "fk_check_test")
        createSchema()

        assertEquals(emptyMap<String, Int>(), SqliteForeignKeyCheck().run())
        assertTrue(appender.list.isEmpty())
    }
}
