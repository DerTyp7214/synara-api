package dev.dertyp.services.import.tidal

import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.plugins.PluginContext
import dev.dertyp.services.import.TidalIndexer
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
import java.time.LocalDate
import java.util.UUID
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
    fun `groupByAlbum should set musicBrainzId on InsertableAlbum from MUSICBRAINZ_RELEASEID tag`() = runBlocking {
        val file1 = tempDir.resolve("track1.flac")
        Files.createFile(file1)

        val mbReleaseId = UUID.randomUUID()
        val tag1 = mockk<Tag>(relaxed = true)
        val audio1 = mockk<AudioFile>(relaxed = true)

        every { audio1.tag } returns tag1
        every { AudioFileIO.read(file1.toFile()) } returns audio1

        every { tag1.getFirst(any<FieldKey>()) } answers {
            when (it.invocation.args[0] as FieldKey) {
                FieldKey.ALBUM -> "Test Album"
                FieldKey.YEAR -> "2020"
                FieldKey.MUSICBRAINZ_RELEASEID -> mbReleaseId.toString()
                else -> ""
            }
        }
        every { tag1.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Artist")

        val (_, albums) = indexer.groupByAlbum(listOf(file1))

        assertEquals(1, albums.size)
        assertEquals(mbReleaseId, albums.keys.first().musicBrainzId)
    }

    @Test
    fun `groupByAlbum should preserve musicBrainzId in merge step`() = runBlocking {
        val file1 = tempDir.resolve("track1.flac")
        val file2 = tempDir.resolve("track2.flac")
        Files.createFile(file1)
        Files.createFile(file2)

        val mbReleaseId = UUID.randomUUID()
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
                FieldKey.ALBUM -> "Test Album"
                FieldKey.YEAR -> "2020"
                FieldKey.MUSICBRAINZ_RELEASEID -> mbReleaseId.toString()
                else -> ""
            }
        }

        every { tag2.getFirst(any<FieldKey>()) } answers {
            when (it.invocation.args[0] as FieldKey) {
                FieldKey.ALBUM -> "Test Album"
                FieldKey.YEAR -> "2020"
                else -> ""
            }
        }
        every { tag1.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Artist")
        every { tag2.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Artist")

        val (_, albums) = indexer.groupByAlbum(listOf(file1, file2))

        assertEquals(1, albums.size)
        assertEquals(
            mbReleaseId,
            albums.keys.first().musicBrainzId,
            "Merged album should retain musicBrainzId from the track that had it"
        )
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

    private fun mockTidalTrack(fileName: String, tidalAlbumId: String, barcodeTag: String): Path {
        val file = tempDir.resolve(fileName)
        Files.createFile(file)

        val tag = mockk<Tag>(relaxed = true)
        val audio = mockk<AudioFile>(relaxed = true)
        every { audio.tag } returns tag
        every { AudioFileIO.read(file.toFile()) } returns audio
        every { tag.getFirst(any<FieldKey>()) } answers {
            when (it.invocation.args[0] as FieldKey) {
                FieldKey.ALBUM -> "Test Album"
                FieldKey.YEAR -> "2020-01-01"
                FieldKey.TRACK_TOTAL -> "10"
                FieldKey.BARCODE -> barcodeTag
                else -> ""
            }
        }
        every { tag.getFirst("URL") } returns "https://tidal.com/album/$tidalAlbumId/track/1"
        every { tag.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Artist")
        return file
    }

    @Test
    fun `groupByAlbum takes the provider barcode for a file without a BARCODE tag`() = runBlocking {
        val file = mockTidalTrack("untagged.flac", "4711", "")
        coEvery {
            context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.tidal, listOf("4711"))
        } returns listOf(
            IMetadataService.Album(id = "4711", title = "Test Album", trackCount = 10, barcode = "0602547933522")
        )

        val (_, albums) = indexer.groupByAlbum(listOf(file))

        assertEquals("tidal:4711", albums.keys.single().originalId)
        assertEquals("0602547933522", albums.keys.single().barcode)
    }

    @Test
    fun `groupByAlbum keeps a tagged barcode`() = runBlocking {
        val file = mockTidalTrack("tagged.flac", "4712", "0093624814337")
        coEvery {
            context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.tidal, any())
        } returns listOf(
            IMetadataService.Album(id = "tidal:4712", title = "Test Album", trackCount = 10, barcode = "0602547933522")
        )

        val (_, albums) = indexer.groupByAlbum(listOf(file))

        assertEquals("0093624814337", albums.keys.single().barcode)
        coVerify(exactly = 0) { context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.tidal, any()) }
    }

    @Test
    fun `groupByAlbum asks the provider for the bare album id and takes its song count and release date`() =
        runBlocking {
            val file = tempDir.resolve("bare.flac")
            Files.createFile(file)

            val tag = mockk<Tag>(relaxed = true)
            val audio = mockk<AudioFile>(relaxed = true)
            every { audio.tag } returns tag
            every { AudioFileIO.read(file.toFile()) } returns audio
            every { tag.getFirst(any<FieldKey>()) } answers {
                when (it.invocation.args[0] as FieldKey) {
                    FieldKey.ALBUM -> "Test Album"
                    else -> ""
                }
            }
            every { tag.getFirst("URL") } returns "https://tidal.com/album/4713/track/1"
            every { tag.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Artist")

            val requested = slot<List<String>>()
            coEvery {
                context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.tidal, capture(requested))
            } answers {
                requested.captured.filter { it == "4713" }.map { albumId ->
                    IMetadataService.Album(
                        id = albumId,
                        title = "Test Album",
                        trackCount = 13,
                        releaseDate = LocalDate.of(2026, 9, 16),
                        barcode = "0602547933522"
                    )
                }
            }

            val (_, albums) = indexer.groupByAlbum(listOf(file))

            val album = albums.keys.single()
            assertEquals(listOf("4713"), requested.captured)
            assertEquals("tidal:4713", album.originalId)
            assertEquals(13, album.songCount)
            assertEquals(LocalDate.of(2026, 9, 16), album.releaseDate)
            assertEquals("0602547933522", album.barcode)
        }

    private fun releaseDateOf(albumId: String, dateTag: String): LocalDate? = runBlocking {
        val file = tempDir.resolve("$albumId.flac")
        Files.createFile(file)

        val tag = mockk<Tag>(relaxed = true)
        val audio = mockk<AudioFile>(relaxed = true)
        every { audio.tag } returns tag
        every { AudioFileIO.read(file.toFile()) } returns audio
        every { tag.getFirst(any<FieldKey>()) } answers {
            when (it.invocation.args[0] as FieldKey) {
                FieldKey.ALBUM -> "Test Album $albumId"
                FieldKey.YEAR -> dateTag
                else -> ""
            }
        }
        every { tag.getFirst("URL") } returns "https://tidal.com/album/$albumId/track/1"
        every { tag.getAll(FieldKey.ALBUM_ARTIST) } returns listOf("Artist")
        coEvery {
            context.metadataService.getAlbumsByIds(IMetadataService.MetadataType.tidal, listOf(albumId))
        } returns listOf(
            IMetadataService.Album(
                id = albumId,
                title = "Test Album $albumId",
                trackCount = 13,
                releaseDate = LocalDate.of(2026, 9, 16)
            )
        )

        indexer.groupByAlbum(listOf(file)).second.keys.single().releaseDate
    }

    @Test
    fun `groupByAlbum takes full, year-month and year-only date tags before the provider date`() {
        assertEquals(LocalDate.of(2016, 5, 20), releaseDateOf("4720", "2016-05-20"))
        assertEquals(LocalDate.of(2016, 5, 1), releaseDateOf("4721", "2016-05"))
        assertEquals(LocalDate.of(2016, 1, 1), releaseDateOf("4722", "2016"))
        assertEquals(LocalDate.of(2026, 9, 16), releaseDateOf("4723", "soon"))
    }
}
