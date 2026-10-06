package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.EntityType
import dev.dertyp.db.*
import dev.dertyp.services.EntityChangeRecorder
import dev.dertyp.testing.entityChangeTables
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.relaxedTaskLogService
import dev.dertyp.testing.updated
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class FillAlbumReleaseDatesTest : KoinTest {
    private lateinit var database: Database
    private val recorder = EntityChangeRecorder()

    private fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()
        startKoin {
            modules(module {
                single { logService }
                single { recorder }
            })
        }

        database = TestDatabase.connect(dialect, "fill_album_release_dates_test")
        transaction(database) {
            SchemaUtils.create(*entityChangeTables, ScheduledTaskLogTable)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertAlbum(
        albumName: String,
        storedDate: String? = null,
        storedAsEstimated: Boolean = false,
        linkedReleaseDate: String? = null
    ): UUID {
        val newAlbumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = newAlbumId
                it[name] = albumName
                it[releaseDate] = storedDate
                it[releaseDateEstimated] = storedAsEstimated
            }
            if (linkedReleaseDate != null) {
                val releaseId = UUID.randomUUID()
                MBReleaseTable.insert {
                    it[id] = EntityID(releaseId, MBReleaseTable)
                    it[title] = albumName
                    it[date] = linkedReleaseDate
                }
                AlbumMusicBrainzTable.insert {
                    it[albumId] = newAlbumId
                    it[musicBrainzId] = EntityID(releaseId, MBReleaseTable)
                }
            }
        }
        return newAlbumId
    }

    private fun insertSong(album: UUID, songDate: String?, importedOn: LocalDate) {
        transaction(database) {
            SongTable.insert {
                it[title] = "Song"
                it[albumId] = album
                it[filePath] = "/music/${UUID.randomUUID()}.flac"
                it[releaseDate] = songDate
                it[inserted] = importedOn.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
    }

    private fun stored(): Map<UUID, Pair<String?, Boolean>> = transaction(database) {
        AlbumTable.selectAll().associate {
            it[AlbumTable.id].value to (it[AlbumTable.releaseDate] to it[AlbumTable.releaseDateEstimated])
        }
    }

    private fun trackingStarts(): List<Long> = transaction(database) {
        EntityChangeTrackingTable.selectAll().map { it[EntityChangeTrackingTable.startedAt] }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fills every album without a date from its release, its songs or the import day`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val imported = LocalDate.of(2021, 3, 4)
            val later = LocalDate.of(2022, 6, 7)

            val linked = insertAlbum("Linked", linkedReleaseDate = "2016-05-20")
            insertSong(linked, "2001-01-01", imported)
            val linkedMonth = insertAlbum("Linked month", linkedReleaseDate = "2016-05")
            val linkedYear = insertAlbum("Linked year", linkedReleaseDate = "2014")
            val linkedUndated = insertAlbum("Linked undated", linkedReleaseDate = "soon")
            insertSong(linkedUndated, "2018-03-02", imported)
            val fromSongs = insertAlbum("From songs")
            insertSong(fromSongs, "2018-03-02", imported)
            insertSong(fromSongs, "2017", later)
            insertSong(fromSongs, null, later)
            val fromImport = insertAlbum("From import")
            insertSong(fromImport, null, later)
            insertSong(fromImport, "soon", imported)
            val empty = insertAlbum("Empty")
            val partial = insertAlbum("Partial", storedDate = "2016-05")
            val unreadable = insertAlbum("Unreadable", storedDate = "soon")
            val dated = insertAlbum("Dated", storedDate = "2010-03-04", linkedReleaseDate = "2016-05-20")
            val yearOnly = insertAlbum("Year only", storedDate = "2014")
            val estimated = insertAlbum("Estimated", storedDate = "2020-01-01", storedAsEstimated = true)
            transaction(database) {
                recorder.updated(EntityType.ALBUM, listOf(dated))
                EntityChangeTrackingTable.insert {
                    it[id] = EntityChangeTrackingTable.ROW_ID
                    it[startedAt] = 1_000L
                }
            }

            val before = LocalDate.now()
            FillAlbumReleaseDates().migrate()
            val after = LocalDate.now()

            val rows = stored()
            assertEquals("2016-05-20" to false, rows[linked])
            assertEquals("2016-05-01" to false, rows[linkedMonth])
            assertEquals("2014-01-01" to false, rows[linkedYear])
            assertEquals("2018-03-02" to false, rows[linkedUndated])
            assertEquals("2017-01-01" to false, rows[fromSongs])
            assertEquals("2021-03-04" to true, rows[fromImport])
            assertEquals("2016-05-01" to false, rows[partial])
            assertEquals("2010-03-04" to false, rows[dated])
            assertEquals("2014" to false, rows[yearOnly])
            assertEquals("2020-01-01" to true, rows[estimated])
            for (album in listOf(empty, unreadable)) {
                assertTrue(rows.getValue(album).first in setOf(before.toString(), after.toString()))
                assertTrue(rows.getValue(album).second)
            }
            assertEquals(
                listOf(linked, linkedMonth, linkedYear, linkedUndated, fromSongs, fromImport, empty, partial, unreadable, dated)
                    .map { updated(EntityType.ALBUM, it) }
                    .toSet(),
                recordedChanges(database)
            )
            assertEquals(listOf(1_000L), trackingStarts())
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second run changes nothing, records nothing and keeps the recorded changes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val fromSongs = insertAlbum("From songs")
        insertSong(fromSongs, "2018-03-02", LocalDate.of(2021, 3, 4))
        val fromImport = insertAlbum("From import")
        insertSong(fromImport, null, LocalDate.of(2021, 3, 4))

        FillAlbumReleaseDates().migrate()
        val rows = stored()
        val started = trackingStarts()
        val recordedByFirstRun = recordedChanges(database)
        transaction(database) { EntityChangeTable.update { it[changedAt] = 1_000L } }

        FillAlbumReleaseDates().migrate()

        assertEquals(mapOf(fromSongs to ("2018-03-02" to false), fromImport to ("2021-03-04" to true)), rows)
        assertEquals(rows, stored())
        assertEquals(emptyList<Long>(), started)
        assertEquals(started, trackingStarts())
        assertEquals(setOf(updated(EntityType.ALBUM, fromSongs), updated(EntityType.ALBUM, fromImport)), recordedByFirstRun)
        assertEquals(recordedByFirstRun, recordedChanges(database))
        assertEquals(
            listOf(1_000L, 1_000L),
            transaction(database) { EntityChangeTable.selectAll().map { it[EntityChangeTable.changedAt] } }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a library where every album has a date keeps its recorded changes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val dated = insertAlbum("Dated", storedDate = "2010-03-04")
        transaction(database) { recorder.updated(EntityType.ALBUM, listOf(dated)) }

        FillAlbumReleaseDates().migrate()

        assertEquals(mapOf(dated to ("2010-03-04" to false)), stored())
        assertEquals(emptyList<Long>(), trackingStarts())
        assertEquals(setOf(updated(EntityType.ALBUM, dated)), recordedChanges(database))
    }
}
