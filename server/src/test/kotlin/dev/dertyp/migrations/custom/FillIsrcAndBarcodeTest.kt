package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
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

class FillIsrcAndBarcodeTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
            })
        }

        database = TestDatabase.connect(dialect, "fill_isrc_barcode_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                AlbumTable,
                SongTable, SongVariantTable,
                MBRecordingTable,
                MBReleaseGroupTable,
                MBReleaseTable,
                SongMusicBrainzTable,
                AlbumMusicBrainzTable,
                MBRecordingIsrcTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `migration should fill isrc and barcode from musicbrainz tables`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        
        val songId = UUID.randomUUID()
        val mbRecordingId = UUID.randomUUID()
        val albumId = UUID.randomUUID()
        val mbReleaseId = UUID.randomUUID()
        val existingSongId = UUID.randomUUID()
        val existingAlbumId = UUID.randomUUID()
        
        val isrcValue = "USAT20300184"
        val barcodeValue = "123456789012"

        transaction(database) {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album with MB"
                it[barcode] = null
            }
            MBReleaseTable.insert {
                it[id] = mbReleaseId
                it[title] = "MB Release"
                it[barcode] = barcodeValue
            }
            AlbumMusicBrainzTable.insert {
                it[this.albumId] = albumId
                it[this.musicBrainzId] = mbReleaseId
            }

            SongTable.insert {
                it[id] = songId
                it[title] = "Song with MB"
                it[this.albumId] = albumId
                it[isrc] = null
            }
            MBRecordingTable.insert {
                it[id] = mbRecordingId
                it[title] = "MB Recording"
            }
            SongMusicBrainzTable.insert {
                it[this.songId] = songId
                it[this.musicBrainzId] = mbRecordingId
            }
            MBRecordingIsrcTable.insert {
                it[this.recordingId] = mbRecordingId
                it[this.isrc] = isrcValue
            }

            AlbumTable.insert {
                it[id] = existingAlbumId
                it[name] = "Album with existing Barcode"
                it[barcode] = "EXISTINGBAR"
            }

            SongTable.insert {
                it[id] = existingSongId
                it[title] = "Song with existing ISRC"
                it[this.albumId] = existingAlbumId
                it[isrc] = "EXISTINGISRC"
            }
        }

        val migration = FillIsrcAndBarcode()
        migration.migrate()

        transaction(database) {
            val song = SongTable.selectAll().where { SongTable.id eq songId }.single()
            assertEquals(isrcValue, song[SongTable.isrc])

            val album = AlbumTable.selectAll().where { AlbumTable.id eq albumId }.single()
            assertEquals(barcodeValue, album[AlbumTable.barcode])

            val existingSong = SongTable.selectAll().where { SongTable.id eq existingSongId }.single()
            assertEquals("EXISTINGISRC", existingSong[SongTable.isrc])

            val existingAlbum = AlbumTable.selectAll().where { AlbumTable.id eq existingAlbumId }.single()
            assertEquals("EXISTINGBAR", existingAlbum[AlbumTable.barcode])
        }
    }
}
