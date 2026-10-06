package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.db.*
import dev.dertyp.services.import.Type
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.testing.relaxedTaskLogService
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
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

class FillAlbumBarcodesTest : KoinTest {
    private lateinit var database: Database
    private val tidalService = mockk<MetadataService>()
    private val appleService = mockk<MetadataService>()

    private fun setup(dialect: DbDialect) {
        val logService = relaxedTaskLogService()
        startKoin {
            modules(module {
                single { logService }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
            })
        }

        mockkObject(MetadataService)
        every { MetadataService.getMetadataService(IMetadataService.MetadataType.tidal, any()) } returns tidalService
        every {
            MetadataService.getMetadataService(IMetadataService.MetadataType.appleMusic, any())
        } returns appleService
        every { tidalService.supported() } returns true
        every { appleService.supported() } returns true
        coEvery { tidalService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) } returns emptyList()
        coEvery { appleService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) } returns emptyList()

        database = TestDatabase.connect(dialect, "fill_album_barcodes_test")
        transaction(database) {
            SchemaUtils.create(
                ImageTable,
                AnimatedImageTable,
                AlbumTable,
                AlbumProviderTable,
                MBReleaseGroupTable,
                MBReleaseTable,
                AlbumMusicBrainzTable,
                ScheduledTaskLogTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertAlbum(
        storedBarcode: String? = null,
        releaseBarcode: String? = null,
        providerName: String? = null,
        providerAlbumId: String? = null,
        providerType: String? = Type.ALBUM.value,
    ): UUID {
        val newAlbumId = UUID.randomUUID()
        transaction(database) {
            AlbumTable.insert {
                it[id] = newAlbumId
                it[name] = "Album"
                it[barcode] = storedBarcode
            }
            if (releaseBarcode != null) {
                val releaseId = UUID.randomUUID()
                MBReleaseTable.insert {
                    it[id] = EntityID(releaseId, MBReleaseTable)
                    it[title] = "Album"
                    it[barcode] = releaseBarcode
                }
                AlbumMusicBrainzTable.insert {
                    it[albumId] = newAlbumId
                    it[musicBrainzId] = EntityID(releaseId, MBReleaseTable)
                }
            }
            if (providerName != null && providerAlbumId != null) {
                AlbumProviderTable.insert {
                    it[albumId] = newAlbumId
                    it[provider] = providerName
                    it[externalId] = providerAlbumId
                    it[type] = providerType
                    it[rawUrl] = "$providerName:$providerAlbumId"
                }
            }
        }
        return newAlbumId
    }

    private fun barcodesById(): Map<UUID, String?> = transaction(database) {
        AlbumTable.selectAll().associate { it[AlbumTable.id].value to it[AlbumTable.barcode] }
    }

    private fun providerAlbum(albumId: String, albumBarcode: String?) = IMetadataService.Album(
        id = albumId,
        title = "Album",
        barcode = albumBarcode
    )

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fills missing barcodes from the linked MusicBrainz release`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val missing = insertAlbum(releaseBarcode = "0602547933515")
        val blank = insertAlbum(storedBarcode = "", releaseBarcode = "093624814337")
        val placeholder = insertAlbum(storedBarcode = "BARCODE", releaseBarcode = "602445790000")
        val oversized = insertAlbum(releaseBarcode = "9".repeat(40))
        val unusable = insertAlbum(releaseBarcode = "1234")
        val existing = insertAlbum(storedBarcode = "0093624814337", releaseBarcode = "0602547933515")

        FillAlbumBarcodes().migrate()

        val barcodes = barcodesById()
        assertEquals("0602547933515", barcodes[missing])
        assertEquals("093624814337", barcodes[blank])
        assertEquals("602445790000", barcodes[placeholder])
        assertEquals("9".repeat(32), barcodes[oversized])
        assertEquals(null, barcodes[unusable])
        assertEquals("0093624814337", barcodes[existing])
        coVerify(exactly = 0) { tidalService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) }
        coVerify(exactly = 0) { appleService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fills the remaining barcodes from the providers`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val fromTidal = insertAlbum(providerName = "tidal", providerAlbumId = "111")
        val fromApple = insertAlbum(providerName = "apple", providerAlbumId = "222")
        val fromRelease = insertAlbum(releaseBarcode = "0602547933515", providerName = "tidal", providerAlbumId = "333")
        val withoutProviderBarcode = insertAlbum(providerName = "tidal", providerAlbumId = "444")
        val trackLink = insertAlbum(providerName = "tidal", providerAlbumId = "555", providerType = Type.SONG.value)
        val existing = insertAlbum(storedBarcode = "0093624814337", providerName = "tidal", providerAlbumId = "666")

        coEvery { tidalService.getAlbumsByIds(any<List<String>>(), HttpClientPriority.LOW) } answers {
            firstArg<List<String>>().mapNotNull { requested ->
                when (requested) {
                    "111" -> providerAlbum("111", "0602547933522")
                    "444" -> providerAlbum("444", null)
                    else -> null
                }
            }
        }
        coEvery { appleService.getAlbumsByIds(listOf("222"), HttpClientPriority.LOW) } returns listOf(
            providerAlbum("222", "093624814337")
        )

        FillAlbumBarcodes().migrate()

        val barcodes = barcodesById()
        assertEquals("0602547933522", barcodes[fromTidal])
        assertEquals("093624814337", barcodes[fromApple])
        assertEquals("0602547933515", barcodes[fromRelease])
        assertEquals(null, barcodes[withoutProviderBarcode])
        assertEquals(null, barcodes[trackLink])
        assertEquals("0093624814337", barcodes[existing])
        coVerify(exactly = 1) {
            tidalService.getAlbumsByIds(match { it.toSet() == setOf("111", "444") }, HttpClientPriority.LOW)
        }
        coVerify(exactly = 1) { appleService.getAlbumsByIds(listOf("222"), HttpClientPriority.LOW) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `skips a failing provider chunk and an unavailable provider`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val failing = insertAlbum(providerName = "tidal", providerAlbumId = "111")
        val unavailable = insertAlbum(providerName = "apple", providerAlbumId = "222")

        coEvery { tidalService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) } throws IllegalStateException("provider down")
        every { appleService.supported() } returns false

        FillAlbumBarcodes().migrate()

        val barcodes = barcodesById()
        assertEquals(null, barcodes[failing])
        assertEquals(null, barcodes[unavailable])
        coVerify(exactly = 0) { appleService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second run changes nothing`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        insertAlbum(releaseBarcode = "0602547933515")
        insertAlbum(providerName = "tidal", providerAlbumId = "111")
        insertAlbum(storedBarcode = "0093624814337", releaseBarcode = "0602547933515")
        insertAlbum()

        coEvery { tidalService.getAlbumsByIds(listOf("111"), HttpClientPriority.LOW) } returns listOf(
            providerAlbum("111", "0602547933522")
        )

        FillAlbumBarcodes().migrate()
        val first = barcodesById()

        coEvery { tidalService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) } returns listOf(providerAlbum("111", "0000000000000"))

        FillAlbumBarcodes().migrate()

        assertEquals(first, barcodesById())
        coVerify(exactly = 1) { tidalService.getAlbumsByIds(any<List<String>>(), any<HttpClientPriority>()) }
    }
}
