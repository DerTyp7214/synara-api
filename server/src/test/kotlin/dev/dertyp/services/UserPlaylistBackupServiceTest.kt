package dev.dertyp.services

import dev.dertyp.data.User
import dev.dertyp.data.UserPlaylist
import dev.dertyp.data.UserPlaylistBackup
import dev.dertyp.serializers.AppJson
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Path
import java.util.UUID

class UserPlaylistBackupServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `createBackup should save playlists and rotate`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "hash")
        val userPlaylistService = mockk<UserPlaylistService>()
        val imageService = mockk<ImageService>()
        val environment = mockk<ApplicationEnvironment>()

        every { environment.config } returns MapApplicationConfig("backup.dir" to tempDir.toString())
        
        val playlist = UserPlaylist(
            id = UUID.randomUUID(),
            name = "My Playlist",
            songs = emptyList(),
            creator = user.id,
            description = ""
        )
        every { userPlaylistService.allPlaylistsFlow(user.id) } returns listOf(playlist).asFlow()
        
        val service = UserPlaylistBackupService(userPlaylistService, imageService, environment)

        for (i in 1..12) {
            service.createBackup(user)
            Thread.sleep(2)
        }

        val backupFiles = File(tempDir.toFile(), "user-playlists").listFiles()
        assertEquals(10, backupFiles?.size)

        val latestBackup = backupFiles?.maxByOrNull { it.lastModified() }
        val backup = AppJson.decodeFromString<UserPlaylistBackup>(latestBackup!!.readText())
        assertEquals(user.id, backup.userId)
        assertEquals(1, backup.playlists.size)
        assertEquals("My Playlist", backup.playlists[0].name)
    }

    @Test
    fun `restoreBackup should call upsert on service`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "hash")
        val userPlaylistService = mockk<UserPlaylistService>(relaxed = true)
        val imageService = mockk<ImageService>(relaxed = true)
        val environment = mockk<ApplicationEnvironment>()

        every { environment.config } returns MapApplicationConfig("backup.dir" to tempDir.toString())
        
        val service = UserPlaylistBackupService(userPlaylistService, imageService, environment)
        
        val playlist = UserPlaylist(
            id = UUID.randomUUID(),
            name = "Restored",
            songs = emptyList(),
            creator = user.id,
            description = ""
        )
        val backup = UserPlaylistBackup(user.id, listOf(playlist), emptyList())
        
        val backupFile = File(tempDir.toFile(), "user-playlists/playlists-${user.id}-now.json")
        backupFile.parentFile.mkdirs()
        backupFile.writeText(AppJson.encodeToString(backup))

        coEvery { userPlaylistService.upsertUserPlaylist(any(), any()) } returns mockk()

        service.restoreBackup(user, backupFile.name)

        coVerify { userPlaylistService.upsertUserPlaylist(match { it.name == "Restored" }, user.id) }
    }

    @Test
    fun `listBackups should return all backups for user`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "hash")
        val userPlaylistService = mockk<UserPlaylistService>()
        val imageService = mockk<ImageService>()
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns MapApplicationConfig("backup.dir" to tempDir.toString())

        val service = UserPlaylistBackupService(userPlaylistService, imageService, environment)
        
        val backupDir = File(tempDir.toFile(), "user-playlists")
        backupDir.mkdirs()
        File(backupDir, "playlists-${user.id}-1.json").writeText("{}")
        File(backupDir, "playlists-${user.id}-2.json").writeText("{}")
        File(backupDir, "playlists-${UUID.randomUUID()}-3.json").writeText("{}")

        val backups = service.listBackups(user)
        assertEquals(2, backups.size)
    }

    @Test
    fun `deleteBackup should remove file`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "hash")
        val userPlaylistService = mockk<UserPlaylistService>()
        val imageService = mockk<ImageService>()
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns MapApplicationConfig("backup.dir" to tempDir.toString())

        val service = UserPlaylistBackupService(userPlaylistService, imageService, environment)
        
        val backupDir = File(tempDir.toFile(), "user-playlists")
        backupDir.mkdirs()
        val file = File(backupDir, "playlists-${user.id}-1.json")
        file.writeText("{}")

        service.deleteBackup(user, file.name)
        assertFalse(file.exists())
    }

    @Test
    fun `getBackupContent should return decoded backup`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "hash")
        val userPlaylistService = mockk<UserPlaylistService>()
        val imageService = mockk<ImageService>()
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns MapApplicationConfig("backup.dir" to tempDir.toString())

        val service = UserPlaylistBackupService(userPlaylistService, imageService, environment)
        
        val backup = UserPlaylistBackup(user.id, emptyList(), emptyList())
        val backupDir = File(tempDir.toFile(), "user-playlists")
        backupDir.mkdirs()
        val file = File(backupDir, "playlists-${user.id}-1.json")
        file.writeText(AppJson.encodeToString(backup))

        val content = service.getBackupContent(user, file.name)
        assertNotNull(content)
        assertEquals(user.id, content?.userId)
    }

    @Test
    fun `backupAllUsers should create backups for all users`() = runBlocking {
        val user1 = User(id = UUID.randomUUID(), username = "user1", passwordHash = "hash")
        val user2 = User(id = UUID.randomUUID(), username = "user2", passwordHash = "hash")
        
        val userService = mockk<UserService>()
        coEvery { userService.queryUser() } returns listOf(user1, user2)
        
        val userPlaylistService = mockk<UserPlaylistService>()
        every { userPlaylistService.allPlaylistsFlow(any()) } returns emptyList<UserPlaylist>().asFlow()
        
        val imageService = mockk<ImageService>()
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns MapApplicationConfig("backup.dir" to tempDir.toString())

        startKoin {
            modules(module {
                single { userService }
            })
        }

        val service = UserPlaylistBackupService(userPlaylistService, imageService, environment)
        val count = service.backupAllUsers()

        assertEquals(2, count)
        val backupFiles = File(tempDir.toFile(), "user-playlists").listFiles()
        assertTrue(backupFiles?.any { it.name.contains(user1.id.toString()) } == true)
        assertTrue(backupFiles?.any { it.name.contains(user2.id.toString()) } == true)
    }
}
