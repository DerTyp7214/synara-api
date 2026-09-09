package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.decodeTitleTags
import dev.dertyp.services.ScheduledTaskLogService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
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

class BackfillSongTitleTagsTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "backfill_song_title_tags_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, AlbumTable, SongTable, ScheduledTaskLogTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertSong(songTitle: String, tagsJson: String = "[]", isExplicit: Boolean = false): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            val album = AlbumTable.insertAndGetId { it[name] = "Album" }
            SongTable.insert {
                it[id] = songId
                it[title] = songTitle
                it[titleTags] = tagsJson
                it[explicit] = isExplicit
                it[albumId] = album
                it[filePath] = "/$songId.flac"
            }
        }
        return songId
    }

    private fun rowsById(): Map<UUID, ResultRow> = transaction(database) {
        SongTable.selectAll().associateBy { it[SongTable.id].value }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `splits recognised tags out of titles and leaves the rest alone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val remix = insertSong("Song (Skrillex Remix)")
        val unknown = insertSong("Song (Drift)")
        val live = insertSong("Song (Live) 🅴")
        val explicitOnly = insertSong("Song [Explicit]")
        val preTagged = insertSong("Song (Remix)", """[{"kind":"FEAT","label":"feat. X"}]""")

        BackfillSongTitleTags().migrate()

        val rows = rowsById()

        assertEquals("Song", rows[remix]!![SongTable.title])
        assertEquals(
            listOf(TitleTag(TitleTagKind.REMIX, "Skrillex Remix")),
            decodeTitleTags(rows[remix]!![SongTable.titleTags]),
        )

        assertEquals("Song (Drift)", rows[unknown]!![SongTable.title])
        assertEquals("[]", rows[unknown]!![SongTable.titleTags])

        assertEquals("Song", rows[live]!![SongTable.title])
        assertEquals(listOf(TitleTag(TitleTagKind.LIVE, "Live")), decodeTitleTags(rows[live]!![SongTable.titleTags]))
        assertEquals(false, rows[live]!![SongTable.explicit])

        assertEquals("Song", rows[explicitOnly]!![SongTable.title])
        assertEquals(emptyList<TitleTag>(), decodeTitleTags(rows[explicitOnly]!![SongTable.titleTags]))

        assertEquals("Song", rows[preTagged]!![SongTable.title])
        assertEquals(
            listOf(TitleTag(TitleTagKind.FEAT, "feat. X"), TitleTag(TitleTagKind.REMIX, "Remix")),
            decodeTitleTags(rows[preTagged]!![SongTable.titleTags]),
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `is idempotent across repeated runs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertSong("Song (Skrillex Remix)")
        insertSong("Song (Drift)")
        insertSong("Song (Live) 🅴")
        insertSong("Song [Explicit]")
        insertSong("Song (Remix)", """[{"kind":"FEAT","label":"feat. X"}]""")

        BackfillSongTitleTags().migrate()
        val first = rowsById().mapValues { (_, row) -> row[SongTable.title] to row[SongTable.titleTags] }

        BackfillSongTitleTags().migrate()
        val second = rowsById().mapValues { (_, row) -> row[SongTable.title] to row[SongTable.titleTags] }

        assertEquals(first, second)
    }
}
