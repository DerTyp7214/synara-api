package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.UserPlaylistBackupService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.USER_PLAYLIST_BACKUP, "User Playlist Backup")
class UserPlaylistBackupWorker : Worker("UserPlaylistBackupWorker") {
    private val userPlaylistBackupService by inject<UserPlaylistBackupService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = userPlaylistBackupService.backupAllUsers { p, l -> onProgress(p, l) }
        return mapOf("userCount" to count)
    }
}
