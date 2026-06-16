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
    fun `getTotalStorage should sum up file sizes and include downloader subdirectories`() = runBlocking {
        // Setup directory structure:
        // tempDir/
        //   audio/
        //     tracks/
        //       core/song1.mp3 (5 bytes)
        //       youtube/song2.mp3 (10 bytes)
        //     albums/
        //       core/cover.jpg (3 bytes)
        //     playlists/
        //       linked.m3u (symlink to ignored_target, should NOT be counted)
        //   secondary/
        //     other.mp3 (7 bytes)
        //   custom/
        //     custom.mp3 (4 bytes)
        //   images/
        //     image.jpg (100 bytes - should NOT be counted if outside audio parent)
        //   ignored_target (15 bytes)

        val audioDir = File(tempDir.toFile(), "audio").apply { mkdirs() }
        val tracksDir = File(audioDir, "tracks").apply { mkdirs() }
        val albumsDir = File(audioDir, "albums").apply { mkdirs() }
        val playlistsDir = File(audioDir, "playlists").apply { mkdirs() }
        
        val coreTracksDir = File(tracksDir, "core").apply { mkdirs() }
        File(coreTracksDir, "song1.mp3").apply { writeText("12345") }
        
        val youtubeTracksDir = File(tracksDir, "youtube").apply { mkdirs() }
        File(youtubeTracksDir, "song2.mp3").apply { writeText("1234567890") }
        
        val coreAlbumsDir = File(albumsDir, "core").apply { mkdirs() }
        File(coreAlbumsDir, "cover.jpg").apply { writeText("abc") }

        val target = File(tempDir.toFile(), "ignored_target").apply { writeText("ignored content") }
        Files.createSymbolicLink(File(playlistsDir, "linked.m3u").toPath(), target.toPath())

        val secondaryDir = File(tempDir.toFile(), "secondary").apply { mkdirs() }
        File(secondaryDir, "other.mp3").apply { writeText("1234567") }

        val customDir = File(tempDir.toFile(), "custom").apply { mkdirs() }
        File(customDir, "custom.mp3").apply { writeText("1234") }

        val imagesDir = File(tempDir.toFile(), "images").apply { mkdirs() }
        File(imagesDir, "image.jpg").apply { writeText("x".repeat(100)) }

        val config = MapApplicationConfig().apply {
            put("audio.tracks", tracksDir.absolutePath)
            put("audio.albums", albumsDir.absolutePath)
            put("audio.playlists", playlistsDir.absolutePath)
            put("audio.secondary-tracks", listOf(secondaryDir.absolutePath))
            put("audio.custom", customDir.absolutePath)
            put("data.images", imagesDir.absolutePath)
            put("data.animated-images", File(tempDir.toFile(), "animated-images").absolutePath)
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config

        val service = StorageService(environment)
        
        // expected: 
        // mainParents (audioDir) size: 5 + 10 + 3 = 18
        // secondarySize: 7
        // customSize: 4
        // total: 18 + 7 + 4 = 29
        val size = service.getTotalStorage()
        assertEquals(29L, size)
    }

    @Test
    fun `getTotalStorage should handle non-existent paths`() = runBlocking {
        val config = MapApplicationConfig().apply {
            put("audio.tracks", "/non/existent/tracks")
            put("audio.albums", "/non/existent/albums")
            put("audio.playlists", "/non/existent/playlists")
            put("audio.custom", "/non/existent/custom")
            put("data.images", "/non/existent/images")
            put("data.animated-images", "/non/existent/animated-images")
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config

        val service = StorageService(environment)
        assertEquals(0L, service.getTotalStorage())
    }

    @Test
    fun `getTotalStorage should handle overlapping paths by deduplicating parents`() = runBlocking {
        // Setup:
        // tempDir/
        //   audio/
        //     tracks/ (5 bytes)
        //     albums/ (3 bytes)
        // Both tracks and albums have the same parent 'audio'.
        // It should only be counted once.

        val audioDir = File(tempDir.toFile(), "audio").apply { mkdirs() }
        val tracksDir = File(audioDir, "tracks").apply { mkdirs() }
        val albumsDir = File(audioDir, "albums").apply { mkdirs() }
        
        File(tracksDir, "song.mp3").apply { writeText("12345") }
        File(albumsDir, "cover.jpg").apply { writeText("abc") }

        val config = MapApplicationConfig().apply {
            put("audio.tracks", tracksDir.absolutePath)
            put("audio.albums", albumsDir.absolutePath)
            put("audio.playlists", albumsDir.absolutePath) // Same as albums
            put("audio.custom", File(tempDir.toFile(), "nonexistent").absolutePath)
            put("data.images", File(tempDir.toFile(), "images").absolutePath)
            put("data.animated-images", File(tempDir.toFile(), "animated-images").absolutePath)
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config

        val service = StorageService(environment)

        // Parent is 'audio', total size 8.
        assertEquals(8L, service.getTotalStorage())
    }

    @Test
    fun `getTotalStorage should not double count if custom audio is inside a main parent`() = runBlocking {
        // Setup:
        // tempDir/
        //   audio/
        //     tracks/ (5 bytes)
        //     custom/ (4 bytes)
        // Parent of tracks is 'audio'. 'custom' is also inside 'audio'.
        // It should only be counted once.

        val audioDir = File(tempDir.toFile(), "audio").apply { mkdirs() }
        val tracksDir = File(audioDir, "tracks").apply { mkdirs() }
        val customDir = File(audioDir, "custom").apply { mkdirs() }
        
        File(tracksDir, "song.mp3").apply { writeText("12345") }
        File(customDir, "custom.mp3").apply { writeText("1234") }

        val config = MapApplicationConfig().apply {
            put("audio.tracks", tracksDir.absolutePath)
            put("audio.custom", customDir.absolutePath)
            put("audio.albums", File(tempDir.toFile(), "nonexistent_albums").absolutePath)
            put("audio.playlists", File(tempDir.toFile(), "nonexistent_playlists").absolutePath)
            put("data.images", File(tempDir.toFile(), "images").absolutePath)
            put("data.animated-images", File(tempDir.toFile(), "animated-images").absolutePath)
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config

        val service = StorageService(environment)
        
        // Parent of tracks is 'audio'. 'custom' is also in 'audio'.
        // total size should be 9.
        assertEquals(9L, service.getTotalStorage())
    }
}
