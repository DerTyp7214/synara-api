package dev.dertyp.services

import dev.dertyp.data.BackupImage
import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylistBackup
import dev.dertyp.serializers.AppJson
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class RpcUserPlaylistBackupService(
    private val user: User,
    private val userPlaylistBackupService: UserPlaylistBackupService
) : IUserPlaylistBackupService {
    override suspend fun createBackup() {
        userPlaylistBackupService.createBackup(user)
    }

    override suspend fun listBackups(): List<BackupInfo> {
        return userPlaylistBackupService.listBackups(user)
    }

    override suspend fun restoreBackup(fileName: String?) {
        userPlaylistBackupService.restoreBackup(user, fileName)
    }

    override suspend fun getBackupContent(fileName: String): UserPlaylistBackup? {
        return userPlaylistBackupService.getBackupContent(user, fileName)
    }

    override suspend fun deleteBackup(fileName: String) {
        userPlaylistBackupService.deleteBackup(user, fileName)
    }
}

class UserPlaylistBackupService(
    private val userPlaylistService: UserPlaylistService,
    private val imageService: ImageService,
    environment: ApplicationEnvironment
) : Service() {
    private val backupDir =
        (environment.config.propertyOrNull("backup.dir")?.getString()?.ifBlank { null }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".config", "backups"))
            .resolve("user-playlists").toFile()

    init {
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
    }

    suspend fun createBackup(user: User) = withContext(Dispatchers.IO) {
        logger.info("Creating user playlist backup for user: ${user.username} (${user.id})")
        val playlists = userPlaylistService.allPlaylistsFlow(user.id).toList()

        val imageIds = playlists.mapNotNull { it.imageId }.distinct()
        val images = imageIds.mapNotNull { id ->
            val image = imageService.byId(id)
            val data = imageService.getImageData(id, 0)
            if (image != null && data != null) {
                BackupImage(image, data)
            } else null
        }

        val backup = UserPlaylistBackup(user.id, playlists, images)

        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"))
        val backupFile = File(backupDir, "playlists-${user.id}-$timestamp.json")

        backupFile.writeText(AppJson.encodeToString(backup))
        logger.info("Backup created: ${backupFile.absolutePath}")
        rotateBackups(user)
    }

    private fun rotateBackups(user: User) {
        val backups = backupDir.listFiles { it.isFile && it.name.startsWith("playlists-${user.id}") }
            ?.sortedBy { it.lastModified() }
            ?: return

        if (backups.size > 10) {
            val toDelete = backups.take(backups.size - 10)
            logger.info("Deleting ${toDelete.size} old user playlist backups for user: ${user.id}")
            toDelete.forEach {
                it.delete()
            }
        }
    }

    suspend fun backupAllUsers(): Int {
        val userService by inject<UserService>()
        val users = userService.queryUser()
        for (user in users) {
            createBackup(user)
        }
        return users.size
    }

    suspend fun listBackups(user: User): List<BackupInfo> = withContext(Dispatchers.IO) {
        backupDir.listFiles { it.isFile && it.name.startsWith("playlists-${user.id}") }
            ?.map {
                BackupInfo(
                    name = it.name,
                    size = it.length(),
                    date = it.lastModified()
                )
            }?.sortedByDescending { it.date }
            ?: emptyList()
    }

    suspend fun restoreBackup(user: User, fileName: String? = null) = withContext(Dispatchers.IO) {
        val backupFile = if (fileName != null) {
            val file = File(backupDir, fileName)
            if (file.exists() && file.name.startsWith("playlists-${user.id}")) file else null
        } else {
            backupDir.listFiles { it.isFile && it.name.startsWith("playlists-${user.id}") }
                ?.maxByOrNull { it.lastModified() }
        }

        if (backupFile == null || !backupFile.exists()) {
            logger.warn("No backup found for user: ${user.id}")
            return@withContext
        }

        logger.info("Restoring user playlist backup from ${backupFile.name} for user: ${user.username} (${user.id})")
        val backup = AppJson.decodeFromString<UserPlaylistBackup>(backupFile.readText())

        backup.images?.forEach { backupImage ->
            imageService.upsertImage(backupImage.image, backupImage.data)
        }

        backup.playlists.forEach { playlist ->
            userPlaylistService.upsertUserPlaylist(playlist, creatorOverride = user.id)
        }
        logger.info("Restored ${backup.playlists.size} playlists for user: ${user.id}")
    }

    suspend fun getBackupContent(user: User, fileName: String): UserPlaylistBackup? = withContext(Dispatchers.IO) {
        val file = File(backupDir, fileName)
        if (!file.exists() || !file.name.startsWith("playlists-${user.id}")) {
            return@withContext null
        }
        logger.info("Reading backup content from ${file.name} for user: ${user.id}")
        AppJson.decodeFromString<UserPlaylistBackup>(file.readText())
    }

    suspend fun deleteBackup(user: User, fileName: String) = withContext(Dispatchers.IO) {
        val file = File(backupDir, fileName)
        if (file.exists() && file.name.startsWith("playlists-${user.id}")) {
            logger.info("Deleting user playlist backup ${file.name} for user: ${user.id}")
            file.delete()
        }
    }
}
