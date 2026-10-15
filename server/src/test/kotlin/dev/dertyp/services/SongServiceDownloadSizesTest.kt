package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.audio.TranscodedSongRepository
import dev.dertyp.audio.Transcoder
import dev.dertyp.core.ClientFeature
import dev.dertyp.core.ClientInfo
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.SongDownloadSize
import dev.dertyp.db.*
import dev.dertyp.testing.insertAlbum
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.nio.file.Path
import java.util.UUID

class SongServiceDownloadSizesTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var database: Database
    private lateinit var songService: SongService
    private val transcoder = mockk<Transcoder>()

    private val wavClient = ClientInfo(ClientFeature.LOSSLESS_WAV_AIFF.minApiVersion)

    fun setup(dialect: DbDialect) {
        startKoin {
            modules(module {
                single { transcoder }
                single { TranscodedSongRepository() }
            })
        }
        database = TestDatabase.connect(
            dialect, "song_download_sizes_test",
            ImageTable,
            AnimatedImageTable,
            AlbumTable,
            SongTable,
            TranscodedSongTable,
        )
        songService = SongService()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun writeFile(name: String, bytes: Int): File =
        tempDir.resolve(name).toFile().apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun insertSongAt(sourcePath: String): UUID = transaction(database) {
        val album = insertAlbum()
        val sid = UUID.randomUUID()
        SongTable.insert {
            it[id] = sid
            it[albumId] = album
            it[filePath] = sourcePath
        }
        sid
    }

    private fun insertTranscode(sid: UUID, transcodeBitrate: Int, transcodeFormat: AudioFormat, transcodePath: String) =
        transaction(database) {
            TranscodedSongTable.insert {
                it[songId] = sid
                it[bitrate] = transcodeBitrate
                it[format] = transcodeFormat
                it[path] = transcodePath
                it[fileSize] = 999_999L
            }
        }

    private fun verifyNoTranscoding() {
        coVerify(exactly = 0) { transcoder.transcodeAudio(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { transcoder.losslessFlacFallback(any(), any()) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `recorded transcodes report their file length and ignore other bitrates and formats`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val source = writeFile("a.flac", 5000)
            val sid = insertSongAt(source.absolutePath)
            insertTranscode(sid, 128, AudioFormat.OPUS, writeFile("a_128.opus", 1234).absolutePath)
            insertTranscode(sid, 320, AudioFormat.OPUS, writeFile("a_320.opus", 4321).absolutePath)
            insertTranscode(sid, 128, AudioFormat.AAC, writeFile("a_128.m4a", 2222).absolutePath)

            assertEquals(
                listOf(SongDownloadSize(sid, 1234)),
                songService.getDownloadSizes(listOf(sid), 128, AudioFormat.OPUS)
            )
            assertEquals(
                listOf(SongDownloadSize(sid, 2222)),
                songService.getDownloadSizes(listOf(sid), 128, AudioFormat.AAC)
            )
            verifyNoTranscoding()
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `missing transcode files, unknown ids and songs without a transcode are omitted`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val missing = insertSongAt(writeFile("m.flac", 100).absolutePath)
            insertTranscode(missing, 128, AudioFormat.OPUS, tempDir.resolve("gone.opus").toString())
            val untranscoded = insertSongAt(writeFile("u.flac", 100).absolutePath)
            val present = insertSongAt(writeFile("p.flac", 100).absolutePath)
            insertTranscode(present, 128, AudioFormat.OPUS, writeFile("p.opus", 77).absolutePath)

            val result = songService.getDownloadSizes(
                listOf(missing, UUID.randomUUID(), untranscoded, present),
                128,
                AudioFormat.OPUS
            )

            assertEquals(listOf(SongDownloadSize(present, 77)), result)
            verifyNoTranscoding()
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `source quality reports source length and hides wav for clients without wav support`(dialect: DbDialect) =
        runBlocking {
            setup(dialect)
            val flac = insertSongAt(writeFile("s.flac", 3000).absolutePath)
            val wav = insertSongAt(writeFile("s.wav", 6000).absolutePath)
            val gone = insertSongAt(tempDir.resolve("gone.flac").toString())

            assertEquals(
                listOf(SongDownloadSize(flac, 3000)),
                songService.getDownloadSizes(listOf(flac, wav, gone), 0, client = ClientInfo.LEGACY)
            )
            assertEquals(
                listOf(SongDownloadSize(flac, 3000), SongDownloadSize(wav, 6000)),
                songService.getDownloadSizes(listOf(flac, wav, gone), 0, client = wavClient)
            )
            verifyNoTranscoding()
        }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `duplicate ids yield one entry in request order`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val first = insertSongAt(writeFile("1.flac", 10).absolutePath)
        val second = insertSongAt(writeFile("2.flac", 20).absolutePath)
        val third = insertSongAt(writeFile("3.flac", 30).absolutePath)

        val result = songService.getDownloadSizes(listOf(third, first, third, second, first), 0)

        assertEquals(
            listOf(SongDownloadSize(third, 30), SongDownloadSize(first, 10), SongDownloadSize(second, 20)),
            result
        )
        assertEquals(emptyList<SongDownloadSize>(), songService.getDownloadSizes(emptyList(), 128))
        verifyNoTranscoding()
    }
}
