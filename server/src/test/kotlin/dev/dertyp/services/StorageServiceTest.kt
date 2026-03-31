package dev.dertyp.services

import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class StorageServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `getTotalStorage should sum up file sizes`() = runBlocking {
        val tracksDir = File(tempDir.toFile(), "tracks").apply { mkdirs() }
        val albumsDir = File(tempDir.toFile(), "albums").apply { mkdirs() }
        val playlistsDir = File(tempDir.toFile(), "playlists").apply { mkdirs() }
        val customDir = File(tempDir.toFile(), "custom").apply { mkdirs() }
        val imagesDir = File(tempDir.toFile(), "images").apply { mkdirs() }

        File(tracksDir, "song1.mp3").apply { writeText("hello") } // 5 bytes
        File(albumsDir, "cover.jpg").apply { writeText("abc") } // 3 bytes

        val target = File(tempDir.toFile(), "ignored_target").apply { writeText("ignored content") }
        Files.createSymbolicLink(File(playlistsDir, "linked.m3u").toPath(), target.toPath())
        
        val config = MapApplicationConfig(
            "audio.tracks" to tracksDir.absolutePath,
            "audio.albums" to albumsDir.absolutePath,
            "audio.playlists" to playlistsDir.absolutePath,
            "audio.custom" to customDir.absolutePath,
            "data.images" to imagesDir.absolutePath
        )
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config

        val service = StorageService(environment)
        
        val size = service.getTotalStorage()
        assertEquals(8L, size)
    }
}
