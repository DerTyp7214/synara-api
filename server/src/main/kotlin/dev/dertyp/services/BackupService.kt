package dev.dertyp.services

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import dev.dertyp.data.User
import dev.dertyp.db.FlacInfoTable
import dev.dertyp.db.SongMusicBrainzTable
import dev.dertyp.db.SongTable
import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.download.DownloadBackend
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.name

@Serializable
enum class FileType {
    @SerialName("file")
    FILE,

    @SerialName("dir")
    DIRECTORY
}

@Serializable
data class FileNode(
    val name: String,
    val type: FileType,
    val size: Long,
    val hash: String? = null,
    val mbid: String? = null,
    val children: List<FileNode>? = null
)

@Serializable
data class ImageEntry(
    val path: String,
    val hash: String
)

class RpcBackupService(
    private val user: User,
    private val backupService: BackupService
) : IBackupService {
    private fun checkAdmin() {
        if (!user.isAdmin) {
            throw SecurityException("Only admins can perform backup operations")
        }
    }

    override suspend fun createBackup(): BackupResult {
        checkAdmin()
        return backupService.createBackup()
    }

    override suspend fun listBackups(): List<BackupInfo> {
        checkAdmin()
        return backupService.listBackups()
    }

    override suspend fun loadBackup(fileName: String) {
        checkAdmin()
        backupService.loadBackup(fileName)
    }

    override suspend fun deleteBackup(fileName: String) {
        checkAdmin()
        backupService.deleteBackup(fileName)
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class BackupService(
    private val dbManagementService: DbManagementService,
    private val storageService: StorageService,
    private val pluginManager: PluginManager,
    private val environment: ApplicationEnvironment
) : Service(), IBackupService {
    private val backupDir =
        (environment.config.propertyOrNull("backup.dir")?.getString()?.ifBlank { null }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".config", "backups")).toFile()

    private val blobsDir = backupDir.resolve("blobs")
    private val maxBackups = 10

    private val imagePath =
        environment.config.propertyOrNull("data.images")?.getString()?.let { Paths.get(it) }?.toFile()

    private fun getAllAudioPaths(): Map<String, Path> {
        val paths = mutableMapOf<String, Path>()

        fun addPath(name: String, path: String?) {
            if (path != null) {
                paths[name] = Paths.get(path)
            }
        }

        addPath("tracks", storageService.tracksPath)
        addPath("albums", storageService.albumsPath)
        addPath("playlists", storageService.playlistsPath)
        addPath("custom", storageService.customAudioPath)
        addPath("transcode", environment.config.propertyOrNull("audio.transcode")?.getString())

        storageService.secondaryTracksPaths.forEachIndexed { index, path ->
            addPath("secondary-tracks-$index", path)
        }

        pluginManager.getAllDownloaders().forEach { downloader ->
            val downloaderStorage = storageService.forDownloader(DownloadBackend(downloader.id))
            addPath("${downloader.id}/tracks", downloaderStorage.tracksPath)
            addPath("${downloader.id}/albums", downloaderStorage.albumsPath)
            addPath("${downloader.id}/playlists", downloaderStorage.playlistsPath)
        }

        return paths
    }

    init {
        backupDir.mkdirs()
        blobsDir.mkdirs()
    }

    override suspend fun createBackup(): BackupResult = createBackup { _, _ -> }

    suspend fun createBackup(onProgress: suspend (Double, String) -> Unit): BackupResult = withContext(Dispatchers.IO) {
        logger.info("Starting backup creation")
        onProgress(0.0, "Starting backup creation")
        
        val dbData = dbManagementService.exportData()
        logger.info("Database exported")
        onProgress(10.0, "Database exported")

        onProgress(15.0, "Fetching song metadata...")
        val metadataMap = fetchSongMetadata()

        val allAudioPaths = getAllAudioPaths()
        val totalPaths = allAudioPaths.size
        var currentPathIndex = 0
        val fileTrees = allAudioPaths.mapValues { (name, path) ->
            currentPathIndex++
            logger.debug("Generating file tree for {} ({})", name, path)
            onProgress(20.0 + (currentPathIndex.toDouble() / totalPaths) * 20.0, "Generating file tree for $name")
            generateFileTree(path, metadataMap)
        }
        val fileTreeBytes = compressZstd(Cbor.encodeToByteArray(fileTrees))
        logger.debug("File tree compressed")
        onProgress(45.0, "File tree compressed")

        val imageIndex = if (imagePath != null && imagePath.exists()) {
            logger.info("Backing up images from $imagePath")
            onProgress(50.0, "Backing up images...")
            backupImages(imagePath)
        } else {
            logger.debug("No images path specified or exists")
            emptyList()
        }
        val imageIndexBytes = compressZstd(Cbor.encodeToByteArray(imageIndex))
        logger.debug("Image index compressed")
        onProgress(75.0, "Image index compressed")

        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val backupFile = backupDir.resolve("backup-$timestamp.zip")

        logger.info("Writing backup to $backupFile")
        onProgress(80.0, "Writing backup to zip...")
        ZipOutputStream(backupFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("database.cbor.zst"))
            zip.write(dbData)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("files.tree.cbor.zst"))
            zip.write(fileTreeBytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("images.index.cbor.zst"))
            zip.write(imageIndexBytes)
            zip.closeEntry()
        }

        logger.info("Backup created: ${backupFile.name}")
        onProgress(95.0, "Rotating old backups...")
        rotateBackups()

        onProgress(100.0, "Backup created: ${backupFile.name}")
        BackupResult(
            fileName = backupFile.name,
            size = backupFile.length(),
            imageCount = imageIndex.size
        )
    }

    private fun generateFileTree(path: Path, metadataMap: Map<String, Pair<String?, String?>>): FileNode? {
        if (!Files.exists(path)) return null

        val children = if (Files.isDirectory(path)) {
            val entries = Files.list(path).use { it.toList() }
            entries.mapNotNull { generateFileTree(it, metadataMap) }
                .sortedBy { it.name }
        } else {
            null
        }

        val metadata = if (!Files.isDirectory(path)) metadataMap[path.absolutePathString()] else null

        return FileNode(
            name = path.name,
            type = if (Files.isDirectory(path)) FileType.DIRECTORY else FileType.FILE,
            size = if (Files.isDirectory(path)) 0 else Files.size(path),
            hash = metadata?.first,
            mbid = metadata?.second,
            children = children
        )
    }

    private fun fetchSongMetadata(): Map<String, Pair<String?, String?>> = transaction {
        val result = mutableMapOf<String, Pair<String?, String?>>()
        
        val songMetadata = SongTable
            .leftJoin(FlacInfoTable, onColumn = { SongTable.id }, otherColumn = { FlacInfoTable.songId })
            .leftJoin(SongMusicBrainzTable, onColumn = { SongTable.id }, otherColumn = { SongMusicBrainzTable.songId })
            .selectAll()
            .map { row ->
                val filePath = row[SongTable.filePath]
                val hash = row.getOrNull(FlacInfoTable.audioMd5)
                val mbid = row.getOrNull(SongMusicBrainzTable.musicBrainzId)?.value?.toString()
                filePath to (hash to mbid)
            }
        
        result.putAll(songMetadata)
        result
    }

    private fun getBlobPath(hash: String): File {
        return blobsDir
            .resolve(hash.substring(0, 2))
            .resolve(hash.substring(2, 4))
            .resolve(hash.substring(4, 6))
            .resolve(hash.substring(6, 8))
            .resolve(hash)
    }

    private fun backupImages(root: File): List<ImageEntry> {
        val entries = mutableListOf<ImageEntry>()
        if (!root.exists()) return entries

        root.walk().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(root)

            val parts = relativePath.path.split(File.separator)

            if (parts.size >= 5) {
                val relevantParts = parts.takeLast(5)
                val hashParts = relevantParts.take(4)
                val filename = relevantParts.last()
                val filenameWithoutExt = filename.substringBeforeLast(".")

                val hash = hashParts.joinToString("") + filenameWithoutExt

                getBlobPath(hash).let { blob ->
                    if (!blob.exists()) file.copyTo(blob)
                }

                entries.add(ImageEntry(relativePath.toString(), hash))
            }
        }
        return entries
    }

    private fun compressZstd(data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { it.write(data) }
        return baos.toByteArray()
    }

    private fun decompressZstd(data: ByteArray): ByteArray {
        val bais = ByteArrayInputStream(data)
        return ZstdInputStream(bais).use { it.readBytes() }
    }

    override suspend fun listBackups(): List<BackupInfo> {
        return withContext(Dispatchers.IO) {
            backupDir.listFiles {
                it.extension == "zip"
            }.map {
                BackupInfo(
                    name = it.name,
                    size = it.length(),
                    date = it.lastModified()
                )
            }.sortedBy { it.date }
        }
    }

    override suspend fun loadBackup(fileName: String) {
        val backupFile = backupDir.resolve(fileName)
        loadBackup(backupFile)
    }

    suspend fun loadBackup(backupFile: File) {
        logger.info("Loading backup: ${backupFile.absolutePath}")
        withContext(Dispatchers.IO) {
            if (!backupFile.exists()) {
                logger.error("Backup file not found: ${backupFile.absolutePath}")
                throw IllegalArgumentException("Backup file not found: ${backupFile.absolutePath}")
            }

            ZipInputStream(backupFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        "database.cbor.zst" -> {
                            logger.info("Restoring database from ${backupFile.name}")
                            val dbData = zip.readBytes()
                            dbManagementService.importData(dbData)
                        }

                        "images.index.cbor.zst" -> {
                            if (imagePath != null) {
                                logger.info("Restoring images from ${backupFile.name}")
                                val indexCborBytes = decompressZstd(zip.readBytes())
                                val index = Cbor.decodeFromByteArray<List<ImageEntry>>(indexCborBytes)
                                restoreImages(index)
                            }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
            logger.info("Backup loaded successfully: ${backupFile.absolutePath}")
        }
    }

    override suspend fun deleteBackup(fileName: String) {
        logger.info("Deleting backup: $fileName")
        withContext(Dispatchers.IO) {
            val backupFile = backupDir.resolve(fileName)
            if (backupFile.exists()) {
                backupFile.delete()
                logger.info("Backup file deleted: $fileName")
                rotateBackups()
            } else {
                logger.warn("Backup file not found for deletion: $fileName")
            }
        }
    }

    private fun restoreImages(index: List<ImageEntry>) {
        if (imagePath == null) return

        imagePath.mkdirs()

        index.forEach { entry ->
            val blobFile = getBlobPath(entry.hash)
            val targetFile = imagePath.resolve(entry.path)

            if (blobFile.exists()) {
                targetFile.parentFile.mkdirs()

                if (!targetFile.exists()) {
                    blobFile.copyTo(targetFile, true)
                }
            }
        }
    }

    private fun rotateBackups() {
        logger.debug("Rotating backups")
        val backups = backupDir
            .listFiles { it.isFile && it.name.endsWith(".zip") }
            .map { it to it.lastModified() }
            .sortedBy { it.second }
            .map { it.first }

        if (backups.size > maxBackups) {
            val toDelete = backups.take(backups.size - maxBackups)
            logger.info("Deleting ${toDelete.size} old backups")
            toDelete.forEach {
                logger.debug("Deleting backup file: ${it.name}")
                it.delete()
            }
        }

        val remainingBackups = if (backups.size > maxBackups) {
            backups.drop(backups.size - maxBackups)
        } else {
            backups
        }

        val referencedHashes = mutableSetOf<String>()

        remainingBackups.forEach { backupFile ->
            try {
                ZipInputStream(backupFile.inputStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "images.index.cbor.zst") {
                            val indexCborBytes = decompressZstd(zip.readBytes())
                            val index = Cbor.decodeFromByteArray<List<ImageEntry>>(indexCborBytes)
                            referencedHashes.addAll(index.map { it.hash })
                            break
                        }
                        entry = zip.nextEntry
                    }
                }
            } catch (e: Exception) {
                logger.error("Error checking referenced hashes in backup ${backupFile.name}", e)
            }
        }

        if (blobsDir.exists()) {
            logger.debug("Cleaning up unreferenced blobs")
            var deletedCount = 0
            blobsDir.walk()
                .filter { it.isFile }
                .forEach { blob ->
                    if (blob.name !in referencedHashes) {
                        blob.delete()
                        deletedCount++
                    }
                }
            if (deletedCount > 0) {
                logger.info("Deleted $deletedCount unreferenced blobs")
            }
        }
    }
}
