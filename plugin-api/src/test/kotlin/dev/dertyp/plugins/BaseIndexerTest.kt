package dev.dertyp.plugins

import dev.dertyp.PlatformUUID
import dev.dertyp.data.Album
import dev.dertyp.data.InsertableAlbum
import dev.dertyp.data.InsertableImage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioHeader
import org.jaudiotagger.tag.Tag
import org.junit.jupiter.api.Test
import java.io.File
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
        assertTrue(indexer.canHandle(Path.of("test.wav")))
        assertTrue(indexer.canHandle(Path.of("test.aiff")))
        assertTrue(indexer.canHandle(Path.of("test.aif")))
        assertTrue(indexer.canHandle(Path.of("TEST.WAV")))
        assertFalse(indexer.canHandle(Path.of("test.mp3")))
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

    @Test
    fun testInsertableSongFromFileSanitization() = runBlocking {
        val audioFile = mockk<AudioFile>(relaxed = true)
        val tag = mockk<Tag>(relaxed = true)
        val header = mockk<AudioHeader>(relaxed = true)
        val album = InsertableAlbum("Album", listOf("Artist"))

        every { audioFile.tag } returns tag
        every { audioFile.audioHeader } returns header
        every { audioFile.file } returns File("test.flac")
        every { header.preciseTrackLength } returns 180.0

        every { tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE) } returns "Song Title \uD83C\uDD74"
        
        val testIndexer = object : BaseIndexer(context) {
            override val id = "test"
            override val name = "test"
            suspend fun testInsertable(af: AudioFile, a: InsertableAlbum) = insertableSongFromFile(af, a)
        }

        val song = testIndexer.testInsertable(audioFile, album)
        
        assertEquals("Song Title", song.title)
        assertTrue(song.explicit)
        verify { tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, "Song Title") }
    }

    @Test
    fun testInsertableSongFromFileAtmosSibling() = runBlocking {
        val tempDir = Files.createTempDirectory("indexer-atmos-test")
        try {
            val flac = Files.createFile(tempDir.resolve("123.flac"))
            val audioFile = mockk<AudioFile>(relaxed = true)
            every { audioFile.tag } returns mockk<Tag>(relaxed = true)
            every { audioFile.audioHeader } returns mockk<AudioHeader>(relaxed = true)
            every { audioFile.file } returns flac.toFile()

            val testIndexer = object : BaseIndexer(context) {
                override val id = "test"
                override val name = "test"
                suspend fun testInsertable(af: AudioFile, a: InsertableAlbum) = insertableSongFromFile(af, a)
            }
            val album = InsertableAlbum("Album", listOf("Artist"))

            assertEquals(null, testIndexer.testInsertable(audioFile, album).atmosPath)

            val atmos = Files.createFile(tempDir.resolve("123.atmos.m4a"))
            assertEquals(atmos.toAbsolutePath().toString(), testIndexer.testInsertable(audioFile, album).atmosPath)
            assertEquals(atmos, flac.atmosSibling)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `start should call syncMusicBrainzForAlbums when albums have musicBrainzId`() = runBlocking {
        val tempDir = Files.createTempDirectory("base-indexer-mb-test")
        try {
            val mbId = UUID.randomUUID()
            val albumDbId = UUID.randomUUID()
            val album = InsertableAlbum("MB Album", listOf("Artist"), musicBrainzId = mbId)
            val dbAlbum = mockk<Album>(relaxed = true)
            every { dbAlbum.id } returns albumDbId

            val testIndexer = object : BaseIndexer(context) {
                override val id = "test"
                override val name = "test"
                override suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
                    emptyMap<String, InsertableImage>() to mapOf(album to emptyList())
            }

            coEvery { context.albumLibrary.byMusicBrainzId(mbId) } returns listOf(dbAlbum)
            coEvery { context.songLibrary.createBatch(any()) } returns emptyMap()

            testIndexer.start(listOf(tempDir), emptyList()) {}

            coVerify { context.albumLibrary.syncMusicBrainzForAlbums(listOf(albumDbId)) }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `start should not call syncMusicBrainzForAlbums when albums have no musicBrainzId`() = runBlocking {
        val tempDir = Files.createTempDirectory("base-indexer-no-mb-test")
        try {
            val album = InsertableAlbum("Album Without MB", listOf("Artist"))

            val testIndexer = object : BaseIndexer(context) {
                override val id = "test"
                override val name = "test"
                override suspend fun groupByAlbum(files: List<Path>): Pair<Map<String, InsertableImage>, Map<InsertableAlbum, List<AudioFile>>> =
                    emptyMap<String, InsertableImage>() to mapOf(album to emptyList())
            }

            coEvery { context.songLibrary.createBatch(any()) } returns emptyMap()

            testIndexer.start(listOf(tempDir), emptyList()) {}

            coVerify(exactly = 0) { context.albumLibrary.syncMusicBrainzForAlbums(any()) }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
