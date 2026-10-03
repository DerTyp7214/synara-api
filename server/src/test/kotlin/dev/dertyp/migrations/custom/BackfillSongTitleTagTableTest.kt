package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongTitleTagTable
import dev.dertyp.testing.relaxedTaskLogService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class BackfillSongTitleTagTableTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "backfill_song_title_tag_table_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, AlbumTable, SongTable, SongTitleTagTable, ScheduledTaskLogTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertSong(songTitle: String, tagsJson: String = "[]"): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            val album = AlbumTable.insertAndGetId { it[name] = "Album" }
            SongTable.insert {
                it[id] = songId
                it[title] = songTitle
                it[titleTags] = tagsJson
                it[albumId] = album
                it[filePath] = "/$songId.flac"
            }
        }
        return songId
    }

    private fun tableRows(): Set<Pair<UUID, TitleTagKind>> = transaction(database) {
        SongTitleTagTable.selectAll()
            .map { it[SongTitleTagTable.songId].value to it[SongTitleTagTable.kind] }
            .toSet()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fills the table from the title tags column`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val remix =
            insertSong("Song", """[{"kind":"REMIX","label":"Skrillex Remix"},{"kind":"FEAT","label":"feat. X"}]""")
        val doubleFeat = insertSong("Song", """[{"kind":"FEAT","label":"feat. X"},{"kind":"FEAT","label":"with Y"}]""")
        insertSong("Plain")
        insertSong("Broken", "not json")

        BackfillSongTitleTagTable().migrate()

        assertEquals(
            setOf(remix to TitleTagKind.REMIX, remix to TitleTagKind.FEAT, doubleFeat to TitleTagKind.FEAT),
            tableRows(),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `is idempotent and replaces stale rows`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val live = insertSong("Song", """[{"kind":"LIVE","label":"Live"}]""")
        val plain = insertSong("Plain")
        transaction(database) {
            SongTitleTagTable.insert {
                it[songId] = plain
                it[kind] = TitleTagKind.DEMO
            }
            SongTitleTagTable.insert {
                it[songId] = live
                it[kind] = TitleTagKind.REMIX
            }
        }

        BackfillSongTitleTagTable().migrate()
        val first = tableRows()
        BackfillSongTitleTagTable().migrate()
        val second = tableRows()

        assertEquals(setOf(live to TitleTagKind.LIVE), first)
        assertEquals(first, second)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `splitting title tags keeps the table in sync`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val remix = insertSong("Song (Skrillex Remix)")
        val preTagged = insertSong("Song (Live)", """[{"kind":"FEAT","label":"feat. X"}]""")
        insertSong("Song (Drift)")

        BackfillSongTitleTags().migrate()

        assertEquals(
            setOf(remix to TitleTagKind.REMIX, preTagged to TitleTagKind.FEAT, preTagged to TitleTagKind.LIVE),
            tableRows(),
        )
    }
}
