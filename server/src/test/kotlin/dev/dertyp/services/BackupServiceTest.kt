package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.config.ServerConfig
import dev.dertyp.db.*
import dev.dertyp.db.BackupSchemaException.Reason
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.import.ImportBackend
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.*
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BackupServiceTest {
    private val dbManagementService = mockk<DbManagementService>()
    private val databaseManager = mockk<DatabaseManager> { every { schemaVersion() } returns "1.109" }
    private val storageService = mockk<StorageService>(relaxed = true)
    private val pluginManager = mockk<PluginManager>(relaxed = true)
    private val environment = mockk<ApplicationEnvironment>()
    private lateinit var tempDir: File
    private lateinit var backupDir: File
    private lateinit var imagesDir: File
    private lateinit var tracksDir: File
    private lateinit var downloaderTracksDir: File
    private lateinit var blobsDir: File
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "backup_test")
        transaction(database) {
            SchemaUtils.create(
                SongTable,
                SongVariantTable,
                FlacInfoTable,
                PcmInfoTable,
                SongMusicBrainzTable,
                MBRecordingTable,
                AlbumTable,
                ImageTable
            )
        }

        tempDir = Files.createTempDirectory("backup_test_root").toFile()
        backupDir = tempDir.resolve("backups")
        imagesDir = tempDir.resolve("images")
        tracksDir = tempDir.resolve("tracks")
        downloaderTracksDir = tempDir.resolve("tiddl").resolve("tracks")
        blobsDir = backupDir.resolve("blobs")

        backupDir.mkdirs()
        imagesDir.mkdirs()
        tracksDir.mkdirs()
        downloaderTracksDir.mkdirs()
        blobsDir.mkdirs()

        val config = MapApplicationConfig(
            "backup.dir" to backupDir.absolutePath,
            "data.images" to imagesDir.absolutePath,
            "audio.tracks" to tracksDir.absolutePath
        )
        every { environment.config } returns config

        every { storageService.tracksPath } returns tracksDir.absolutePath
        every { storageService.imagesPath } returns imagesDir.absolutePath

        val mockDownloader = mockk<IImporter>()
        every { mockDownloader.id } returns "tiddl"
        every { pluginManager.getAllImporters() } returns listOf(mockDownloader)

        val downloaderStorage = mockk<StorageService>(relaxed = true)
        every { downloaderStorage.tracksPath } returns downloaderTracksDir.absolutePath
        every { storageService.forImporter(ImportBackend("tiddl")) } returns downloaderStorage
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBackup should create a zip file with expected entries and blobs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service =
            BackupService(dbManagementService, databaseManager, storageService, pluginManager, ServerConfig(environment.config))
        val dummyDbData = byteArrayOf(1, 2, 3)
        coEvery { dbManagementService.exportData(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write(
                dummyDbData
            )
        }

        val imgHash = "abcdef1234567890"
        val imgSubPath = "ab/cd/ef/12"
        val imgFileDir = imagesDir.resolve(imgSubPath)
        imgFileDir.mkdirs()
        val imgFile = imgFileDir.resolve("34567890.jpg")
        imgFile.writeBytes(byteArrayOf(10, 11, 12))

        val downloaderFile = downloaderTracksDir.resolve("song.mp3")
        downloaderFile.writeText("test content")
        val wavFile = downloaderTracksDir.resolve("song.wav")
        wavFile.writeText("wav content")

        val songId = UUID.randomUUID()
        val wavSongId = UUID.randomUUID()
        val mbId = UUID.randomUUID()
        transaction(database) {
            val albumId = UUID.randomUUID()
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[filePath] = downloaderFile.absolutePath
                it[this.albumId] = albumId
            }
            FlacInfoTable.insert {
                it[this.songId] = songId
                it[audioMd5] = "test-hash"
                it[sampleRate] = 44100
                it[bitDepth] = 16
                it[channels] = 2
                it[duration] = 180.0
                it[fileSize] = 1024
                it[bitrateAvg] = 1411
                it[seekpointCount] = 0
                it[seekIntervalMax] = 0.0
                it[paddingBytes] = 0
            }
            MBRecordingTable.insert {
                it[id] = mbId
                it[title] = "Song"
            }
            SongMusicBrainzTable.insert {
                it[this.songId] = songId
                it[musicBrainzId] = mbId
            }
            SongTable.insert {
                it[id] = wavSongId
                it[title] = "Wav Song"
                it[filePath] = wavFile.absolutePath
                it[format] = "wav"
                it[this.albumId] = albumId
            }
            PcmInfoTable.insert {
                it[this.songId] = wavSongId
                it[container] = "wav"
                it[sampleRate] = 48000
                it[bitDepth] = 24
                it[channels] = 2
                it[duration] = 10.0
                it[fileSize] = 2048
                it[bitrateAvg] = 2304
                it[codec] = "pcm_s24le"
                it[isFloat] = false
                it[isBigEndian] = false
                it[dataOffset] = 44
                it[dataSize] = 2004
                it[hasId3] = false
                it[hasInfoChunk] = true
                it[audioMd5] = "pcm-hash"
            }
        }

        val result = service.createBackup()

        val backupFile = backupDir.resolve(result.fileName)
        assertTrue(backupFile.exists(), "Backup file should exist")
        assertEquals(1, result.imageCount)

        ZipFile(backupFile).use { zip ->
            assertNotNull(zip.getEntry("database.cbor.zst"))
            val treeEntry = zip.getEntry("files.tree.cbor.zst")
            assertNotNull(treeEntry)
            assertNotNull(zip.getEntry("images.index.cbor.zst"))

            zip.getInputStream(treeEntry).use { input ->
                val compressedBytes = input.readBytes()
                val decompressedBytes = ZstdInputStream(ByteArrayInputStream(compressedBytes)).use { it.readBytes() }
                val tree = Cbor.decodeFromByteArray<Map<String, FileNode?>>(decompressedBytes)

                assertTrue(tree.containsKey("tiddl/tracks"), "Tree should contain downloader tracks")
                val downloaderNode = tree["tiddl/tracks"]
                assertNotNull(downloaderNode)
                val songNode = downloaderNode!!.children?.find { it.name == "song.mp3" }
                assertNotNull(songNode)
                assertEquals("test-hash", songNode?.hash)
                assertEquals(mbId.toString(), songNode?.mbid)
                val wavNode = downloaderNode.children?.find { it.name == "song.wav" }
                assertNotNull(wavNode)
                assertEquals("pcm-hash", wavNode?.hash)
            }
        }

        val blobPath = blobsDir.resolve("ab").resolve("cd").resolve("ef").resolve("12").resolve(imgHash)
        assertTrue(blobPath.exists(), "Blob file should exist at $blobPath")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `loadBackup should restore database and images`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service =
            BackupService(dbManagementService, databaseManager, storageService, pluginManager, ServerConfig(environment.config))
        val dummyDbData = byteArrayOf(1, 2, 3)
        var capturedDbData: ByteArray? = null
        coEvery { dbManagementService.exportData(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write(
                dummyDbData
            )
        }
        coEvery { dbManagementService.importData(any<InputStream>()) } answers {
            capturedDbData = firstArg<InputStream>().readBytes()
        }

        val imgSubPath = "ab/cd/ef/12"
        val imgFileDir = imagesDir.resolve(imgSubPath)
        imgFileDir.mkdirs()
        val imgFile = imgFileDir.resolve("34567890.jpg")
        val imgData = byteArrayOf(10, 11, 12)
        imgFile.writeBytes(imgData)

        val backupResult = service.createBackup()

        imagesDir.deleteRecursively()
        imagesDir.mkdirs()
        assertFalse(imgFile.exists())

        service.loadBackup(backupResult.fileName)

        coVerify { dbManagementService.importData(any<InputStream>()) }
        assertArrayEquals(dummyDbData, capturedDbData)
        assertTrue(imgFile.exists(), "Image file should have been restored")
        assertArrayEquals(imgData, imgFile.readBytes())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `loadBackup should restore database and images from File`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service =
            BackupService(dbManagementService, databaseManager, storageService, pluginManager, ServerConfig(environment.config))
        val dummyDbData = byteArrayOf(1, 2, 3)
        var capturedDbData: ByteArray? = null
        coEvery { dbManagementService.exportData(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write(
                dummyDbData
            )
        }
        coEvery { dbManagementService.importData(any<InputStream>()) } answers {
            capturedDbData = firstArg<InputStream>().readBytes()
        }

        val imgSubPath = "ab/cd/ef/12"
        val imgFileDir = imagesDir.resolve(imgSubPath)
        imgFileDir.mkdirs()
        val imgFile = imgFileDir.resolve("34567890.jpg")
        val imgData = byteArrayOf(10, 11, 12)
        imgFile.writeBytes(imgData)

        val backupResult = service.createBackup()
        val backupFile = backupDir.resolve(backupResult.fileName)

        imagesDir.deleteRecursively()
        imagesDir.mkdirs()
        assertFalse(imgFile.exists())

        service.loadBackup(backupFile)

        coVerify { dbManagementService.importData(any<InputStream>()) }
        assertArrayEquals(dummyDbData, capturedDbData)
        assertTrue(imgFile.exists(), "Image file should have been restored")
        assertArrayEquals(imgData, imgFile.readBytes())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBackup should remove the partial zip when the export fails`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service =
            BackupService(dbManagementService, databaseManager, storageService, pluginManager, ServerConfig(environment.config))
        coEvery { dbManagementService.exportData(any<OutputStream>()) } throws IllegalStateException("export failed")

        var thrown: Throwable? = null
        try {
            service.createBackup()
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue(thrown is IllegalStateException, "Export failure should propagate")
        assertEquals("export failed", thrown?.message)

        val leftovers = backupDir.listFiles { it.extension == "zip" }
        assertEquals(0, leftovers?.size ?: 0, "No partial backup zip should remain")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `rotateBackups should delete old backups and unreferenced blobs`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val service =
            BackupService(dbManagementService, databaseManager, storageService, pluginManager, ServerConfig(environment.config))
        coEvery { dbManagementService.exportData(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write(
                byteArrayOf(0)
            )
        }

        repeat(11) { i ->
            imagesDir.deleteRecursively()
            imagesDir.mkdirs()

            val hash = String.format("%016x", i)
            val dir = imagesDir.resolve(
                "${hash.substring(0, 2)}/${hash.substring(2, 4)}/${
                    hash.substring(
                        4,
                        6
                    )
                }/${hash.substring(6, 8)}"
            )
            dir.mkdirs()
            dir.resolve("${hash.substring(8)}.jpg").writeBytes(byteArrayOf(i.toByte()))

            service.createBackup()
            Thread.sleep(1005)
        }

        val backups = backupDir.listFiles { it.extension == "zip" }
        assertEquals(10, backups?.size, "Should only keep 10 backups")

        val firstBlobHash = String.format("%016x", 0)
        val firstBlob = blobsDir.resolve("00/00/00/00/$firstBlobHash")

        assertFalse(
            firstBlob.exists(),
            "Oldest blob $firstBlobHash should have been cleaned up as it is no longer referenced by any existing backup"
        )
    }

    private fun backupService() =
        BackupService(dbManagementService, databaseManager, storageService, pluginManager, ServerConfig(environment.config))

    private fun zipBackup(version: String?): File {
        val file = backupDir.resolve("handmade-${UUID.randomUUID()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            if (version != null) {
                zip.putNextEntry(ZipEntry("schema.version"))
                zip.write(version.toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("database.cbor.zst"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        return file
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `createBackup should write the schema version as the first entry`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        coEvery { dbManagementService.exportData(any<OutputStream>()) } answers {
            firstArg<OutputStream>().write(byteArrayOf(1))
        }

        val result = backupService().createBackup()

        ZipFile(backupDir.resolve(result.fileName)).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals("schema.version", names.first())
            assertEquals("1.109", zip.getInputStream(zip.getEntry("schema.version")).use { it.readBytes().decodeToString() })
            assertTrue("database.cbor.zst" in names)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `loadBackup accepts a backup of the current and of a lower schema version and one without a version`(
        dialect: DbDialect
    ) = runBlocking {
        setup(dialect)
        coEvery { dbManagementService.importData(any<InputStream>()) } returns Unit
        val service = backupService()

        service.loadBackup(zipBackup("1.109"))
        every { databaseManager.schemaVersion() } returns "1.111"
        service.loadBackup(zipBackup("1.110"))
        service.loadBackup(zipBackup(null))

        coVerify(exactly = 3) { dbManagementService.importData(any<InputStream>()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `loadBackup refuses a backup older than the migration base before it imports anything`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val service = backupService()
            val file = zipBackup("1.108")

            val refusal = assertThrows(BackupSchemaException::class.java) {
                runBlocking { service.loadBackup(file) }
            }

            assertEquals(Reason.BELOW_BASE, refusal.reason)
            assertEquals("1.108", refusal.backupVersion)
            coVerify(exactly = 0) { dbManagementService.importData(any<InputStream>()) }
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `loadBackup refuses a backup from a newer server before it imports anything`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val service = backupService()
            val file = zipBackup("1.110")

            val refusal = assertThrows(BackupSchemaException::class.java) {
                runBlocking { service.loadBackup(file.name) }
            }

            assertEquals(Reason.NEWER_THAN_SERVER, refusal.reason)
            assertEquals("1.110", refusal.backupVersion)
            assertEquals("1.109", refusal.serverVersion)
            coVerify(exactly = 0) { dbManagementService.importData(any<InputStream>()) }
        }
}
