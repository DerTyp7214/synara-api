package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaseIndexerTest {
    private val context = mockk<PluginContext>(relaxed = true)
    private val indexer = object : BaseIndexer(context) {
        override val id: String = "test"
        override val name: String = "Test Indexer"

        public override fun buildMap(paths: List<Path>): List<Path> {
            return super.buildMap(paths)
        }

        public override suspend fun parsePlaylists(files: List<Path>, userId: PlatformUUID?): Int {
            return super.parsePlaylists(files, userId)
        }
    }

    @Test
    fun testCanHandle() {
        val flacFile = Path.of("test.flac")
        val m3uFile = Path.of("test.m3u")
        val txtFile = Path.of("test.txt")

        assertTrue(indexer.canHandle(flacFile))
        assertTrue(indexer.canHandle(m3uFile))
        assertFalse(indexer.canHandle(txtFile))
    }

    @Test
    fun testBuildMap() {
        val tempDir = Files.createTempDirectory("indexer-test")
        try {
            val flacFile = Files.createFile(tempDir.resolve("test.flac"))
            val m3uFile = Files.createFile(tempDir.resolve("test.m3u"))
            val txtFile = Files.createFile(tempDir.resolve("test.txt"))
            val subDir = Files.createDirectory(tempDir.resolve("subdir"))
            val subFlac = Files.createFile(subDir.resolve("sub.flac"))

            val map = indexer.buildMap(listOf(tempDir))
            
            assertTrue(map.contains(flacFile.toAbsolutePath()))
            assertTrue(map.contains(m3uFile.toAbsolutePath()))
            assertTrue(map.contains(subFlac.toAbsolutePath()))
            assertFalse(map.contains(txtFile.toAbsolutePath()))
            assertEquals(3, map.size)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun testParsePlaylists() = runBlocking {
        val tempDir = Files.createTempDirectory("indexer-playlist-test")
        try {
            val m3uFile = Files.createFile(tempDir.resolve("_MyPlaylist.m3u"))
            Files.writeString(m3uFile, "song1.flac\nsong2.flac")

            coEvery { context.playlistLibrary.createBatch(any(), any()) } returns listOf(UUID.randomUUID())

            val count = indexer.parsePlaylists(listOf(m3uFile))
            
            assertEquals(1, count)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
