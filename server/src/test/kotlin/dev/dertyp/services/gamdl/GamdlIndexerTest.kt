package dev.dertyp.services.gamdl

import dev.dertyp.data.InsertableAlbum
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.plugins.PluginContext
import dev.dertyp.services.metadata.IMetadataService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class GamdlIndexerTest {
    private val context = mockk<PluginContext>(relaxed = true)
    private val storage = mockk<IServerStorageService>(relaxed = true)
    private lateinit var indexer: GamdlIndexer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        mockkStatic(AudioFileIO::class)
        mockkStatic("dev.dertyp.core.UtilsKt")
        mockkStatic("dev.dertyp.core.Sha256Kt")
        every { context.storageService.forImporter(any()) } returns storage
        every { storage.tracksPath } returns tempDir.toString()
        indexer = GamdlIndexer(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun flacAt(relative: String): Path {
        val p = tempDir.resolve(relative)
        Files.createDirectories(p.parent)
        Files.createFile(p)
        return p
    }

    private fun mockAudio(file: Path, tagValues: Map<FieldKey, String>, albumArtists: List<String> = listOf("Artist")): AudioFile {
        val tag = mockk<Tag>(relaxed = true)
        val audio = mockk<AudioFile>(relaxed = true)
        every { audio.tag } returns tag
        every { AudioFileIO.read(file.toFile()) } returns audio
        every { tag.getFirst(any<FieldKey>()) } answers { tagValues[it.invocation.args[0] as FieldKey] ?: "" }
        every { tag.getAll(FieldKey.ALBUM_ARTIST) } returns albumArtists
        return audio
    }

    @Test
    fun `canHandle accepts flac under the gamdl tracks path and rejects others`() {
        val flac = flacAt("123/456.flac")
        assertTrue(indexer.canHandle(flac))

        val mp3 = flacAt("123/x.mp3")
        assertFalse(indexer.canHandle(mp3)) // mp3 is not a lossless library format

        val outside = Files.createTempFile("outside", ".flac")
        assertFalse(indexer.canHandle(outside)) // not under the gamdl tracks path
    }

    @Test
    fun `groupByAlbum derives originalId from the numeric album-id folder`() = runBlocking {
        val flac = flacAt("789/1.flac")
        mockAudio(flac, mapOf(FieldKey.ALBUM to "Test Album", FieldKey.YEAR to "2020"))

        val (_, albums) = indexer.groupByAlbum(listOf(flac))

        assertEquals(1, albums.size)
        val album = albums.keys.first()
        assertEquals("Test Album", album.name)
        assertEquals("appleMusic:789", album.originalId)
    }

    @Test
    fun `groupByAlbum reads the musicBrainz release id from the tag`() = runBlocking {
        val mbReleaseId = UUID.randomUUID()
        val flac = flacAt("100/1.flac")
        mockAudio(
            flac,
            mapOf(
                FieldKey.ALBUM to "Album",
                FieldKey.YEAR to "2021",
                FieldKey.MUSICBRAINZ_RELEASEID to mbReleaseId.toString()
            )
        )

        val (_, albums) = indexer.groupByAlbum(listOf(flac))

        assertEquals(mbReleaseId, albums.keys.first().musicBrainzId)
    }

    @Test
    fun `insertableSongFromFile resolves a musicBrainz id from the ISRC when the tag has none`() = runBlocking {
        val mbId = UUID.randomUUID()
        val isrc = "USABC1234567"
        val flac = flacAt("55/track.flac")
        val audio = mockAudio(flac, mapOf(FieldKey.ISRC to isrc))
        every { audio.file } returns flac.toFile()
        every { audio.audioHeader } returns mockk(relaxed = true)

        coEvery {
            context.metadataService.getTrackByIsrc(IMetadataService.MetadataType.musicBrainz, isrc)
        } returns IMetadataService.Track(id = mbId.toString(), title = "x", duration = 3.minutes, images = emptyList())

        val album = InsertableAlbum(name = "Album", artists = listOf("Artist"), songCount = 1)
        val song = indexer.insertableSongFromFile(audio, album)

        assertEquals(mbId, song.musicBrainzId)
        assertEquals(isrc, song.isrc)
    }
}
