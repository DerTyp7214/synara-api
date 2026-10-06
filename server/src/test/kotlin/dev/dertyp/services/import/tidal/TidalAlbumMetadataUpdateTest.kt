package dev.dertyp.services.import.tidal

import dev.dertyp.DbDialect
import dev.dertyp.core.process.ExternalTool
import dev.dertyp.data.EntityType
import dev.dertyp.db.AlbumTable
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.EntityChangeLibraryTest
import dev.dertyp.services.import.ProcessExecutionResult
import dev.dertyp.services.import.TidalBaseImporter
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.testing.clearRecordedChanges
import dev.dertyp.testing.recordedChanges
import dev.dertyp.testing.updated
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.time.LocalDate
import java.util.UUID

class TidalAlbumMetadataUpdateTest : EntityChangeLibraryTest() {
    private class TestTidalImporter(
        indexer: IPluginIndexer,
        storageService: IServerStorageService
    ) : TidalBaseImporter(indexer, storageService) {
        override val id: String = "test"
        override val enabled: Boolean = true
        override val tool = ExternalTool("test")
        override val loginCommand: MutableList<String> = mutableListOf()
        override val importCommand: MutableList<String> = mutableListOf()
        override val favImportCommand: MutableList<String> = mutableListOf()
        override fun authorizedCheck(result: ProcessExecutionResult): Boolean = true
        override fun tokenFileExists(): Boolean = true
        override fun canHandle(url: String): Boolean = true
        override suspend fun executeImporter(
            command: Collection<String>,
            aliveCheck: suspend () -> Boolean,
            directory: File?,
            onLineReceived: suspend (String) -> Unit
        ): ProcessExecutionResult = ProcessExecutionResult(0, "", "")
    }

    private val tidalService = mockk<MetadataService>(relaxed = true)

    @AfterEach
    fun releaseMetadataService() {
        unmockkObject(MetadataService)
    }

    private fun importer(): TestTidalImporter {
        mockkObject(MetadataService)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        return TestTidalImporter(mockk(relaxed = true), mockk(relaxed = true))
    }

    private fun providerAlbum(tidalId: String, tracks: Int, released: LocalDate?) {
        coEvery { tidalService.getAlbumsByIds(listOf(tidalId), any()) } returns listOf(
            IMetadataService.Album(id = tidalId, title = "Album", trackCount = tracks, releaseDate = released)
        )
    }

    private fun stored(album: UUID): Triple<Int, String?, Boolean> = db {
        AlbumTable.selectAll().where { AlbumTable.id eq album }.single().let {
            Triple(it[AlbumTable.songCount], it[AlbumTable.releaseDate], it[AlbumTable.releaseDateEstimated])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the provider date replaces an estimated date and is recorded`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val album = album("Estimated", tracks = 3, released = "2026-01-01")
        db { AlbumTable.update({ AlbumTable.id eq album }) { it[releaseDateEstimated] = true } }
        providerAlbum("901", 3, LocalDate.of(2012, 6, 7))

        assertTrue(importer().updateAlbumMetadata(album, "tidal:901"))

        assertEquals(Triple(3, "2012-06-07", false), stored(album))
        assertEquals(setOf(updated(EntityType.ALBUM, album)), recordedChanges(database))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `the provider date never replaces a real date and no provider date keeps the stored one`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val dated = album("Dated", tracks = 3, released = "2010-03-04")
            val estimated = album("Estimated", tracks = 3, released = "2026-01-01")
            db { AlbumTable.update({ AlbumTable.id eq estimated }) { it[releaseDateEstimated] = true } }
            providerAlbum("902", 5, LocalDate.of(2012, 6, 7))
            providerAlbum("903", 3, null)
            val importer = importer()

            assertTrue(importer.updateAlbumMetadata(dated, "tidal:902"))
            assertEquals(Triple(5, "2010-03-04", false), stored(dated))
            assertEquals(setOf(updated(EntityType.ALBUM, dated)), recordedChanges(database))

            clearRecordedChanges(database)
            assertTrue(importer.updateAlbumMetadata(estimated, "tidal:903"))
            assertEquals(Triple(3, "2026-01-01", true), stored(estimated))
            assertEquals(emptySet<Any>(), recordedChanges(database))
        }
}
