package dev.dertyp.services.import

import dev.dertyp.plugins.PluginContext
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class TidalIndexerTest {
    private val context = mockk<PluginContext>(relaxed = true)
    private lateinit var indexer: TidalIndexer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        mockkStatic(AudioFileIO::class)
        mockkStatic("dev.dertyp.core.UtilsKt")
        mockkStatic("dev.dertyp.core.Sha256Kt")
        
        indexer = TidalIndexer(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `groupByAlbum should group tracks with different artist order into the same album`() = runBlocking {
        val file1 = tempDir.resolve("track1.flac")
        val file2 = tempDir.resolve("track2.flac")
        Files.createFile(file1)
        Files.createFile(file2)

        val tag1 = mockk<Tag>(relaxed = true)
        val tag2 = mockk<Tag>(relaxed = true)

        val audio1 = mockk<AudioFile>(relaxed = true)
        val audio2 = mockk<AudioFile>(relaxed = true)
        
        every { audio1.tag } returns tag1
        every { audio2.tag } returns tag2
        
        every { AudioFileIO.read(file1.toFile()) } returns audio1
        every { AudioFileIO.read(file2.toFile()) } returns audio2

        every { tag1.getFirst(any<FieldKey>()) } answers {
            when (it.invocation.args[0] as FieldKey) {
                FieldKey.ALBUM -> "Discovery"
                FieldKey.YEAR -> "2001"
                FieldKey.TRACK_TOTAL -> "14"
                else -> ""
            }
        }
        every { tag2.getFirst(any<FieldKey>()) } answers {
            when (it.invocation.args[0] as FieldKey) {
                FieldKey.ALBUM -> "Discovery"
                FieldKey.YEAR -> "2001"
                FieldKey.TRACK_TOTAL -> "14"
                else -> ""
            }
        }

        every { tag1.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Daft Punk; Romanthony")
        every { tag2.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Romanthony; Daft Punk")

        val (_, albums) = indexer.groupByAlbum(listOf(file1, file2))

        assertEquals(1, albums.size)
        val album = albums.keys.first()
        assertEquals("Discovery", album.name)
        assertEquals(listOf("Daft Punk", "Romanthony"), album.artists)
    }

    @Test
    fun `groupByAlbum should limit concurrency using semaphore`() = runBlocking {
        val files = (1..10).map { 
            val f = tempDir.resolve("track$it.flac")
            Files.createFile(f)
            f
        }

        val activeRequests = AtomicInteger(0)
        val maxActiveRequests = AtomicInteger(0)

        coEvery { AudioFileIO.read(any<File>()) } answers {
            val active = activeRequests.incrementAndGet()
            synchronized(maxActiveRequests) {
                if (active > maxActiveRequests.get()) {
                    maxActiveRequests.set(active)
                }
            }
            Thread.sleep(50)
            activeRequests.decrementAndGet()
            mockk(relaxed = true)
        }

        indexer.groupByAlbum(files)

        assertTrue(maxActiveRequests.get() <= 2, "Max active requests was ${maxActiveRequests.get()}, expected <= 2")
    }
}
