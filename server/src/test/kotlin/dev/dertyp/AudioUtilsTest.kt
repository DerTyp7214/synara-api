package dev.dertyp

import dev.dertyp.data.AudioFormat
import dev.dertyp.data.TranscodedVersion
import dev.dertyp.db.*
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.stopKoin
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

class AudioUtilsTest {

    private lateinit var database: Database

    fun setupDb(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "audioutils_test")
        transaction(database) {
            SchemaUtils.create(ArtistTable, AlbumTable, SongTable, TranscodedSongTable, ImageTable)
        }
    }

    @AfterEach
    fun tearDown() {
        if (::database.isInitialized) {
            TestDatabase.cleanUp()
        }
        stopKoin()
        unmockkAll()
    }

    @ParameterizedTest
    @CsvSource(
        "44100, 48000",
        "48000, 48000",
        "22050, 24000",
        "8000, 8000",
        "11025, 12000",
        "16000, 16000",
        "96000, 48000",
        "192000, 48000"
    )
    fun `closestSampleRate should return nearest supported rate`(input: Int, expected: Int) {
        val result = AudioUtils.closestSampleRate(input)
        assertEquals(expected, result)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getSongsWithTranscodingInfo should return songs with their transcoded bitrates`(dialect: DbDialect) = runBlocking {
        setupDb(dialect)

        val songId = UUID.randomUUID()
        transaction(database) {
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[this.albumId] = albumId
                it[filePath] = "path.flac"
            }
            TranscodedSongTable.insert {
                it[this.songId] = songId
                it[bitrate] = 128
                it[path] = "path_128.ogg"
            }
            TranscodedSongTable.insert {
                it[this.songId] = songId
                it[bitrate] = 192
                it[path] = "path_192.ogg"
            }
        }

        val songs = AudioUtils.getSongsWithTranscodingInfo()
        assertEquals(1, songs.size)
        assertEquals(
            listOf(TranscodedVersion(128, AudioFormat.OPUS), TranscodedVersion(192, AudioFormat.OPUS)),
            songs[0].transcodedTo.sortedBy { it.bitrate }
        )
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `insertTranscodedSong should add record to DB`(dialect: DbDialect) = runBlocking {
        setupDb(dialect)
        val songId = UUID.randomUUID()
        transaction(database) {
            val albumId = AlbumTable.insert { it[name] = "Album" }[AlbumTable.id]
            SongTable.insert {
                it[id] = songId
                it[title] = "Song"
                it[this.albumId] = albumId
                it[filePath] = "path.flac"
            }
        }

        AudioUtils.insertTranscodedSong(songId, File("transcoded.ogg"), 320)

        val songs = AudioUtils.getSongsWithTranscodingInfo()
        assertEquals(1, songs.size)
        assertEquals(listOf(TranscodedVersion(320, AudioFormat.OPUS)), songs[0].transcodedTo)
    }

    @Test
    fun `transcodeAudio should create ogg file`(@TempDir tempDir: Path) = runBlocking {
        val flacFile = tempDir.resolve("test.flac").toFile().apply { writeText("fake flac content") }
        val environment = mockk<ApplicationEnvironment>()
        val config = MapApplicationConfig(
            "audio.tracks" to tempDir.toString(),
            "audio.transcode" to tempDir.resolve("transcode").toString()
        )
        every { environment.config } returns config

        mockkConstructor(FFmpegFrameGrabber::class)
        every { anyConstructed<FFmpegFrameGrabber>().start() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().stop() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().release() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().audioChannels } returns 2
        every { anyConstructed<FFmpegFrameGrabber>().metadata } returns HashMap<String, String>()
        every { anyConstructed<FFmpegFrameGrabber>().sampleRate } returns 44100
        every { anyConstructed<FFmpegFrameGrabber>().lengthInTime } returns 1000000

        val mockFrame = mockk<Frame>(relaxed = true)
        every { anyConstructed<FFmpegFrameGrabber>().grabFrame(any(), any(), any(), any()) } returnsMany listOf(mockFrame, null)

        mockkConstructor(FFmpegFrameRecorder::class)
        every { anyConstructed<FFmpegFrameRecorder>().start() } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().stop() } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().release() } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().record(any<Frame>()) } just Runs

        every { anyConstructed<FFmpegFrameRecorder>().setImageWidth(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setImageHeight(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setVideoCodec(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setAudioCodec(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setFormat(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setSampleRate(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setAudioBitrate(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setSampleFormat(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setFrameRate(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setMetadata(any(), any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setOption(any(), any()) } just Runs

        val streamInfo = AudioUtils.transcodeAudio(environment, flacFile, 128)

        assertTrue(streamInfo.file.exists())
        assertEquals("test.ogg", streamInfo.file.name)

        verify { anyConstructed<FFmpegFrameRecorder>().record(any<Frame>()) }
    }

    @Test
    fun `transcodeAudio should create aac file`(@TempDir tempDir: Path) = runBlocking {
        val flacFile = tempDir.resolve("test.flac").toFile().apply { writeText("fake flac content") }
        val environment = mockk<ApplicationEnvironment>()
        val config = MapApplicationConfig(
            "audio.tracks" to tempDir.toString(),
            "audio.transcode" to tempDir.resolve("transcode").toString()
        )
        every { environment.config } returns config

        mockkConstructor(FFmpegFrameGrabber::class)
        every { anyConstructed<FFmpegFrameGrabber>().start() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().stop() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().release() } just Runs
        every { anyConstructed<FFmpegFrameGrabber>().audioChannels } returns 2
        every { anyConstructed<FFmpegFrameGrabber>().metadata } returns HashMap<String, String>()
        every { anyConstructed<FFmpegFrameGrabber>().sampleRate } returns 44100
        every { anyConstructed<FFmpegFrameGrabber>().lengthInTime } returns 1000000

        val mockFrame = mockk<Frame>(relaxed = true)
        every { anyConstructed<FFmpegFrameGrabber>().grabFrame(any(), any(), any(), any()) } returnsMany listOf(mockFrame, null)

        mockkConstructor(FFmpegFrameRecorder::class)
        every { anyConstructed<FFmpegFrameRecorder>().start() } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().stop() } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().release() } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().record(any<Frame>()) } just Runs

        every { anyConstructed<FFmpegFrameRecorder>().setImageWidth(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setImageHeight(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setVideoCodec(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setAudioCodec(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setFormat(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setSampleRate(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setAudioBitrate(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setSampleFormat(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setFrameRate(any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setMetadata(any(), any()) } just Runs
        every { anyConstructed<FFmpegFrameRecorder>().setOption(any(), any()) } just Runs

        val streamInfo = AudioUtils.transcodeAudio(environment, flacFile, 128, audioFormat = AudioFormat.AAC)

        assertTrue(streamInfo.file.exists())
        assertEquals("test.m4a", streamInfo.file.name)

        verify { anyConstructed<FFmpegFrameRecorder>().record(any<Frame>()) }
    }

    @Test
    fun `transcodeAudio should throw FileNotFoundException when file does not exist`() {
        runBlocking {
            val environment = mockk<ApplicationEnvironment>(relaxed = true)
            val nonExistentFile = File("non_existent_file.flac")

            assertThrows<FileNotFoundException> {
                AudioUtils.transcodeAudio(environment, nonExistentFile, 128)
            }
        }
    }

    @Test
    fun `transcodeAudio should throw IOException when file is empty`(@TempDir tempDir: Path) {
        runBlocking {
            val emptyFile = tempDir.resolve("empty.flac").toFile().apply { createNewFile() }
            val environment = mockk<ApplicationEnvironment>(relaxed = true)

            assertThrows<IOException> {
                AudioUtils.transcodeAudio(environment, emptyFile, 128)
            }
        }
    }

    @Test
    fun `transcodeAudio should throw IOException when file is a directory`(@TempDir tempDir: Path) {
        runBlocking {
            val directory = tempDir.resolve("dir").toFile().apply { mkdir() }
            val environment = mockk<ApplicationEnvironment>(relaxed = true)

            assertThrows<IOException> {
                AudioUtils.transcodeAudio(environment, directory, 128)
            }
        }
    }
}
