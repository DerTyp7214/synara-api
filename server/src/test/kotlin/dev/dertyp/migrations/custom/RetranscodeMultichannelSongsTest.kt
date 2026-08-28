package dev.dertyp.migrations.custom

import dev.dertyp.AudioUtils
import dev.dertyp.DbDialect
import dev.dertyp.StreamInfo
import dev.dertyp.TestDatabase
import dev.dertyp.data.AudioFormat
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.nio.file.Files
import java.util.UUID

class RetranscodeMultichannelSongsTest : KoinTest {
    private lateinit var database: Database
    private lateinit var tempDir: File

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)

        startKoin {
            modules(module {
                single { logService }
                single { mockk<ApplicationEnvironment>(relaxed = true) }
            })
        }

        tempDir = Files.createTempDirectory("retranscode-test").toFile()
        database = TestDatabase.connect(dialect, "retranscode_multichannel_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, AlbumTable, SongTable, SongVariantTable, FlacInfoTable, PcmInfoTable, TranscodedSongTable, ScheduledTaskLogTable)
        }
        mockkObject(AudioUtils)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AudioUtils)
        stopKoin()
        TestDatabase.cleanUp()
        tempDir.deleteRecursively()
    }

    private fun insertSong(name: String, channels: Int?, format: AudioFormat): Pair<UUID, File> {
        val source = File(tempDir, "$name.flac").apply { writeText("source") }
        val cached = File(tempDir, "$name.ogg").apply { writeText("old") }
        val songId = UUID.randomUUID()
        transaction(database) {
            val album = AlbumTable.insertAndGetId { it[this.name] = "Album" }
            SongTable.insert {
                it[id] = songId
                it[title] = name
                it[albumId] = album
                it[filePath] = source.absolutePath
            }
            if (channels != null) {
                FlacInfoTable.insert {
                    it[this.songId] = songId
                    it[sampleRate] = 48000
                    it[bitDepth] = 24
                    it[this.channels] = channels
                    it[duration] = 180.0
                    it[fileSize] = 1
                    it[bitrateAvg] = 1
                    it[seekpointCount] = 0
                    it[seekIntervalMax] = 0.0
                    it[paddingBytes] = 0
                    it[audioMd5] = "0"
                }
            }
            TranscodedSongTable.insert {
                it[this.songId] = songId
                it[bitrate] = 128
                it[this.format] = format
                it[path] = cached.absolutePath
                it[fileSize] = 3
            }
        }
        return songId to cached
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `only multichannel transcodes are redone`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val (stereoId, stereoCached) = insertSong("stereo", 2, AudioFormat.OPUS)
        val (surroundId, surroundCached) = insertSong("surround", 6, AudioFormat.AAC)
        val (unknownId, unknownCached) = insertSong("unknown", null, AudioFormat.OPUS)

        val newFile = File(tempDir, "surround.new.m4a").apply { writeText("new transcode") }
        coEvery { AudioUtils.transcodeAudio(any(), any(), any(), any(), any()) } returns
                StreamInfo(newFile, ContentType.Audio.MP4, newFile.length(), newFile.name)

        RetranscodeMultichannelSongs().migrate()

        coVerify(exactly = 1) { AudioUtils.transcodeAudio(any(), match { it.name == "surround.flac" }, 128, true, AudioFormat.AAC) }
        assertFalse(surroundCached.exists())
        assertTrue(stereoCached.exists())
        assertTrue(unknownCached.exists())

        val rows = transaction(database) {
            TranscodedSongTable.selectAll().associate { it[TranscodedSongTable.songId].value to (it[TranscodedSongTable.path] to it[TranscodedSongTable.fileSize]) }
        }
        assertEquals(newFile.absolutePath to newFile.length(), rows[surroundId])
        assertEquals(stereoCached.absolutePath to 3L, rows[stereoId])
        assertEquals(unknownCached.absolutePath to 3L, rows[unknownId])
    }
}
