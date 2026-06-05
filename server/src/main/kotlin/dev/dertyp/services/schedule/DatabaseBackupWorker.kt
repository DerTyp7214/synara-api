package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.BackupService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.DATABASE_BACKUP, "Database Backup")
class DatabaseBackupWorker : Worker("DatabaseBackupWorker") {
    private val backupService by inject<BackupService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val res = backupService.createBackup { p, l -> onProgress(p, l) }
        return mapOf("fileName" to res.fileName, "size" to res.size, "imageCount" to res.imageCount)
    }
}
