package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TitleTag
import dev.dertyp.data.TitleTagKind
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.ImageTable
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongTitleTagTable
import dev.dertyp.db.decodeTitleTags
import dev.dertyp.testing.relaxedTaskLogService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
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

class ResplitSongTitleTagsTest : KoinTest {
    private lateinit var database: Database

    private fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "resplit_song_title_tags_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, AlbumTable, SongTable, SongTitleTagTable, ScheduledTaskLogTable)
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
    fun `splits mix cuts out of titles and a second run changes nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val mixCut = insertSong("Song [Mix Cut]")

        ResplitSongTitleTags().migrate()
        val first = rowsById()

        assertEquals("Song", first[mixCut]!![SongTable.title])
        assertEquals(
            listOf(TitleTag(TitleTagKind.MIX, "Mix Cut")),
            decodeTitleTags(first[mixCut]!![SongTable.titleTags]),
        )

        ResplitSongTitleTags().migrate()
        val second = rowsById()

        assertEquals(
            first.mapValues { (_, row) -> row[SongTable.title] to row[SongTable.titleTags] },
            second.mapValues { (_, row) -> row[SongTable.title] to row[SongTable.titleTags] },
        )
    }
}
