package dev.dertyp.migrations.custom

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.audio.AudioProbe
import dev.dertyp.data.AudioInfo
import dev.dertyp.db.*
import dev.dertyp.services.ScheduledTaskLogService
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.util.UUID

class BackfillSongAudioInfoTest : KoinTest {
    private lateinit var database: Database

    fun setup(dialect: DbDialect) {
        val logService = mockk<ScheduledTaskLogService>(relaxed = true)
        every { logService.startLog(any(), any()) } returns EntityID(UUID.randomUUID(), ScheduledTaskLogTable)
        startKoin { modules(module { single { logService } }) }

        database = TestDatabase.connect(dialect, "backfill_audio_info_test")
        transaction(database) {
            SchemaUtils.create(ImageTable, AlbumTable, SongTable, FlacInfoTable, PcmInfoTable, SongVariantTable, ScheduledTaskLogTable)
        }
        mockkObject(AudioProbe)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AudioProbe)
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun insertSong(path: String, channels: Int, flacChannels: Int?): UUID {
        val songId = UUID.randomUUID()
        transaction(database) {
            val album = AlbumTable.insertAndGetId { it[name] = "Album" }
            SongTable.insert {
                it[id] = songId
                it[title] = path
                it[albumId] = album
                it[filePath] = path
                it[this.channels] = channels
            }
            if (flacChannels != null) {
                FlacInfoTable.insert {
                    it[this.songId] = songId
                    it[sampleRate] = 48000
                    it[bitDepth] = 24
                    it[this.channels] = flacChannels
                    it[duration] = 1.0
                    it[fileSize] = 1
                    it[bitrateAvg] = 1
                    it[seekpointCount] = 0
                    it[seekIntervalMax] = 0.0
                    it[paddingBytes] = 0
                    it[audioMd5] = "0"
                }
            }
        }
        return songId
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `fills channels from analysis rows or probing and probes variants`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val analysed = insertSong("/a.flac", 0, 6)
        val probedSong = insertSong("/b.flac", 0, null)
        val already = insertSong("/c.flac", 2, null)
        val unreadable = insertSong("/d.flac", 0, null)
        transaction(database) {
            SongVariantTable.insert {
                it[songId] = analysed
                it[kind] = SongVariantKind.ATMOS
                it[path] = "/a.atmos.m4a"
            }
        }

        every { AudioProbe.probeChannels(File("/b.flac")) } returns 2
        every { AudioProbe.probeChannels(File("/d.flac")) } returns 0
        every { AudioProbe.probe(File("/a.atmos.m4a")) } returns AudioInfo("eac3", 48000, 0, 768000, 123, 6)

        BackfillSongAudioInfo().migrate()

        val channels = transaction(database) {
            SongTable.select(SongTable.id, SongTable.channels).associate { it[SongTable.id].value to it[SongTable.channels] }
        }
        assertEquals(6, channels[analysed])
        assertEquals(2, channels[probedSong])
        assertEquals(2, channels[already])
        assertEquals(0, channels[unreadable])

        val variant = transaction(database) {
            SongVariantTable.selectAll().where { SongVariantTable.songId eq analysed }.single()
        }
        assertEquals("eac3", variant[SongVariantTable.codec])
        assertEquals(6, variant[SongVariantTable.channels])
        assertEquals(768000L, variant[SongVariantTable.bitRate])
        assertEquals(123L, variant[SongVariantTable.fileSize])
    }
}
