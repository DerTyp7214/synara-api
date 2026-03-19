package dev.dertyp.services

import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylistBackup
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
}

class UserPlaylistBackupService(
    private val userPlaylistService: UserPlaylistService,
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
        val backup = UserPlaylistBackup(user.id, playlists)
        val json = Json { prettyPrint = true }

        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val backupFile = File(backupDir, "playlists-${user.id}-$timestamp.json")

        backupFile.writeText(json.encodeToString(backup))
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

    suspend fun backupAllUsers() {
        val userService by inject<UserService>()
        val users = userService.queryUser()
        for (user in users) {
            createBackup(user)
        }
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
        val json = Json { ignoreUnknownKeys = true }
        val backup = json.decodeFromString<UserPlaylistBackup>(backupFile.readText())

        backup.playlists.forEach { playlist ->
            userPlaylistService.upsertUserPlaylist(playlist, creatorOverride = user.id)
        }
        logger.info("Restored ${backup.playlists.size} playlists for user: ${user.id}")
    }
}
