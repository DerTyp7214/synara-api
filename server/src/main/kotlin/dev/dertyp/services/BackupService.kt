package dev.dertyp.services

import dev.dertyp.data.User
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.name

class RpcBackupService(
    private val user: User,
    private val backupService: BackupService
) : IBackupService {
    private fun checkAdmin() {
        if (!user.isAdmin) {
            throw SecurityException("Only admins can perform backup operations")
        }
    }

    override suspend fun createBackup() {
        checkAdmin()
        backupService.createBackup()
    }

    override suspend fun listBackups(): List<BackupInfo> {
        checkAdmin()
        return backupService.listBackups()
    }

    override suspend fun loadBackup(fileName: String) {
        checkAdmin()
        backupService.loadBackup(fileName)
    }
}

class BackupService(
    private val dbManagementService: DbManagementService,
    environment: ApplicationEnvironment
) : IBackupService {
    private val backupDir =
        environment.config.propertyOrNull("backup.dir")?.getString()?.ifBlank { null }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".config", "backups")
    private val maxBackups = 10

    init {
        if (!Files.exists(backupDir)) {
            Files.createDirectories(backupDir)
        }
    }

    override suspend fun createBackup() {
        withContext(Dispatchers.IO) {
            val backupData = dbManagementService.exportData()
            val timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val backupFile = backupDir.resolve("backup-$timestamp.cbor.zst")
            Files.write(backupFile, backupData)
            rotateBackups()
        }
    }

    override suspend fun listBackups(): List<BackupInfo> {
        return withContext(Dispatchers.IO) {
            Files.list(backupDir).use { stream ->
                stream
                    .filter { it.name.endsWith(".cbor.zst") }
                    .map {
                        BackupInfo(
                            name = it.name,
                            size = Files.size(it),
                            date = Files.getLastModifiedTime(it).toMillis()
                        )
                    }
                    .sorted(Comparator.comparing { it.date })
                    .toList()
            }
        }
    }

    override suspend fun loadBackup(fileName: String) {
        withContext(Dispatchers.IO) {
            val backupFile = backupDir.resolve(fileName)
            if (Files.exists(backupFile)) {
                val backupData = Files.readAllBytes(backupFile)
                dbManagementService.importData(backupData)
            } else {
                throw IllegalArgumentException("Backup file not found: $fileName")
            }
        }
    }

    private fun rotateBackups() {
        Files.list(backupDir).use { stream ->
            val backups = stream
                .filter { it.name.endsWith(".cbor.zst") }
                .sorted(Comparator.comparing { Files.getLastModifiedTime(it) })
                .toList()

            if (backups.size > maxBackups) {
                val toDelete = backups.take(backups.size - maxBackups)
                toDelete.forEach { Files.delete(it) }
            }
        }
    }
}
