package dev.dertyp.services

import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class BackupServiceTest {
    private val dbManagementService = mockk<DbManagementService>()
    private val environment = mockk<ApplicationEnvironment>()
    private lateinit var tempDir: File
    private lateinit var backupDir: File
    private lateinit var imagesDir: File
    private lateinit var tracksDir: File
    private lateinit var blobsDir: File

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("backup_test_root").toFile()
        backupDir = tempDir.resolve("backups")
        imagesDir = tempDir.resolve("images")
        tracksDir = tempDir.resolve("tracks")
        blobsDir = backupDir.resolve("blobs")
        
        backupDir.mkdirs()
        imagesDir.mkdirs()
        tracksDir.mkdirs()
        blobsDir.mkdirs()

        val config = MapApplicationConfig(
            "backup.dir" to backupDir.absolutePath,
            "data.images" to imagesDir.absolutePath,
            "audio.tracks" to tracksDir.absolutePath
        )
        every { environment.config } returns config
    }

    @Test
    fun `createBackup should create a zip file with expected entries and blobs`() = runBlocking {
        val service = BackupService(dbManagementService, environment)
        val dummyDbData = byteArrayOf(1, 2, 3)
        coEvery { dbManagementService.exportData() } returns dummyDbData

        val imgHash = "abcdef1234567890"
        val imgSubPath = "ab/cd/ef/12"
        val imgFileDir = imagesDir.resolve(imgSubPath)
        imgFileDir.mkdirs()
        val imgFile = imgFileDir.resolve("34567890.jpg")
        imgFile.writeBytes(byteArrayOf(10, 11, 12))

        val result = service.createBackup()
        
        val backupFile = backupDir.resolve(result.fileName)
        assertTrue(backupFile.exists(), "Backup file should exist")
        assertEquals(1, result.imageCount)

        ZipFile(backupFile).use { zip ->
            assertNotNull(zip.getEntry("database.cbor.zst"))
            assertNotNull(zip.getEntry("files.tree.cbor.zst"))
            assertNotNull(zip.getEntry("images.index.cbor.zst"))
        }

        val blobPath = blobsDir.resolve("ab").resolve("cd").resolve("ef").resolve("12").resolve(imgHash)
        assertTrue(blobPath.exists(), "Blob file should exist at $blobPath")
    }

    @Test
    fun `loadBackup should restore database and images`() = runBlocking {
        val service = BackupService(dbManagementService, environment)
        val dummyDbData = byteArrayOf(1, 2, 3)
        coEvery { dbManagementService.exportData() } returns dummyDbData
        coEvery { dbManagementService.importData(any()) } just Runs

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

        coVerify { dbManagementService.importData(any()) }
        assertTrue(imgFile.exists(), "Image file should have been restored")
        assertArrayEquals(imgData, imgFile.readBytes())
    }

    @Test
    fun `rotateBackups should delete old backups and unreferenced blobs`() = runBlocking {
        val service = BackupService(dbManagementService, environment)
        coEvery { dbManagementService.exportData() } returns byteArrayOf(0)

        repeat(11) { i ->
            imagesDir.deleteRecursively()
            imagesDir.mkdirs()

            val hash = String.format("%016x", i)
            val dir = imagesDir.resolve("${hash.substring(0,2)}/${hash.substring(2,4)}/${hash.substring(4,6)}/${hash.substring(6,8)}")
            dir.mkdirs()
            dir.resolve("${hash.substring(8)}.jpg").writeBytes(byteArrayOf(i.toByte()))
            
            service.createBackup()
            Thread.sleep(1005)
        }

        val backups = backupDir.listFiles { it.extension == "zip" }
        assertEquals(10, backups?.size, "Should only keep 10 backups")

        val firstBlobHash = String.format("%016x", 0)
        val firstBlob = blobsDir.resolve("00/00/00/00/$firstBlobHash")
        
        assertFalse(firstBlob.exists(), "Oldest blob $firstBlobHash should have been cleaned up as it is no longer referenced by any existing backup")
    }
}
