package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.AudioBand
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.AudioTimelineSource
import dev.dertyp.db.AudioTimelineStatus
import dev.dertyp.db.SongAudioTimelineTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.dbQuery
import dev.dertyp.services.audio.AudioTimelineCodec
import dev.dertyp.services.audio.highHz
import dev.dertyp.services.audio.lowHz
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.UUID
import kotlin.math.abs

@OptIn(ExperimentalSerializationApi::class)
class AudioTimelineTest {
    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @Test
    fun `essentia fixture exposes beats and loudness descriptors`() {
        val jsonText = this::class.java.classLoader.getResource("essentia_output.json")!!.readText()
        val output = ApplicationScope.json.decodeFromString<EssentiaOutput>(jsonText)
        assertEquals(206, output.rhythm?.beatsPosition?.size)
        assertEquals(206, output.rhythm?.beatsCount?.toInt())
        assertNotNull(output.rhythm?.onsetRate)
        assertNotNull(output.rhythm?.beatsLoudness?.mean)
        assertNotNull(output.rhythm?.beatsLoudness?.max)
        assertNotNull(output.lowLevel?.dynamicComplexity)
        assertNotNull(output.lowLevel?.loudnessEbu128?.loudnessRange)
    }

    @Test
    fun `minimal essentia json decodes`() {
        val output = ApplicationScope.json.decodeFromString<EssentiaOutput>("""{"rhythm":{"beats_position":[0.5,1.0]},"lowlevel":{"dynamic_complexity":3.0}}""")
        assertEquals(listOf(0.5, 1.0), output.rhythm?.beatsPosition)
        assertEquals(3.0, output.lowLevel?.dynamicComplexity)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `missing timeline query skips ok rows and retries stale failures`(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_timeline_test")
        val now = System.currentTimeMillis()
        val songs = List(4) { UUID.randomUUID() }
        dbQuery {
            SchemaUtils.create(AlbumTable, SongTable, SongVariantTable, SongAudioTimelineTable)
            val album = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]
            songs.forEachIndexed { index, id ->
                SongTable.insert {
                    it[SongTable.id] = id
                    it[title] = "Song $index"
                    it[albumId] = album
                }
            }
            fun timeline(id: UUID, status: AudioTimelineStatus, analyzedAt: Long) = SongAudioTimelineTable.insert {
                it[songId] = id
                it[version] = 1
                it[SongAudioTimelineTable.status] = status
                it[beatSource] = AudioTimelineSource.ESSENTIA
                it[SongAudioTimelineTable.analyzedAt] = analyzedAt
            }
            timeline(songs[0], AudioTimelineStatus.OK, now)
            timeline(songs[1], AudioTimelineStatus.FAILED, now)
            timeline(songs[2], AudioTimelineStatus.FAILED, now - 10 * 24 * 60 * 60 * 1000L)
        }
        val service = AudioAnalysisService()
        val missing = service.getSongIdsMissingTimeline(100)
        assertEquals(setOf(songs[2], songs[3]), missing.toSet())
        assertEquals(1, service.getSongIdsMissingTimeline(1).size)
        assertTrue(service.getAudioTimeline(songs[3]) == null)
        val ok = service.getAudioTimeline(songs[0])
        assertNotNull(ok)
        assertEquals("essentia", ok!!.source)
        assertTrue(ok.beatsMs.isEmpty())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `band levels round trip through the timeline row`(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_timeline_bands_test")
        val songId = UUID.randomUUID()
        val fiveBands = AudioBand.entries.mapIndexed { index, _ -> FloatArray(20) { -70f + (index * 4 + it) * 2f } }
        dbQuery {
            SchemaUtils.create(AlbumTable, SongTable, SongVariantTable, SongAudioTimelineTable)
            val album = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]
            SongTable.insert {
                it[SongTable.id] = songId
                it[title] = "Song"
                it[albumId] = album
            }
            SongAudioTimelineTable.insert {
                it[SongAudioTimelineTable.songId] = songId
                it[version] = 3
                it[SongAudioTimelineTable.status] = AudioTimelineStatus.OK
                it[beatSource] = AudioTimelineSource.ESSENTIA
                it[analyzedAt] = System.currentTimeMillis()
                it[bands] = AudioTimelineCodec.encodeBands(fiveBands, -70f, 0f)
                it[bandCount] = 5
                it[bandHz] = 50
                it[envelopeMinDb] = -70.0
                it[envelopeMaxDb] = 0.0
            }
        }
        val service = AudioAnalysisService()
        val timeline = service.getAudioTimeline(songId)
        assertNotNull(timeline)
        assertEquals(50, timeline!!.bandHz)
        assertEquals(AudioBand.entries.size, timeline.bands.size)
        val step = 70f / 255f
        timeline.bands.forEachIndexed { index, band ->
            val expectedBand = AudioBand.entries[index]
            assertEquals(expectedBand, band.band)
            assertEquals(expectedBand.lowHz, band.lowHz)
            assertEquals(expectedBand.highHz, band.highHz)
            val expectedValues = fiveBands[index]
            band.levelsDb.forEachIndexed { i, value -> assertTrue(abs(value - expectedValues[i]) <= step, "band $index index $i") }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a row without bands returns empty bands`(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_timeline_no_bands_test")
        val songId = UUID.randomUUID()
        dbQuery {
            SchemaUtils.create(AlbumTable, SongTable, SongVariantTable, SongAudioTimelineTable)
            val album = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]
            SongTable.insert {
                it[SongTable.id] = songId
                it[title] = "Song"
                it[albumId] = album
            }
            SongAudioTimelineTable.insert {
                it[SongAudioTimelineTable.songId] = songId
                it[version] = 3
                it[SongAudioTimelineTable.status] = AudioTimelineStatus.OK
                it[beatSource] = AudioTimelineSource.ESSENTIA
                it[analyzedAt] = System.currentTimeMillis()
            }
        }
        val service = AudioAnalysisService()
        val timeline = service.getAudioTimeline(songId)
        assertNotNull(timeline)
        assertTrue(timeline!!.bands.isEmpty())
        assertEquals(0, timeline.bandHz)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `stale timelines are those below the codec version`(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_timeline_stale_version_test")
        val staleSongId = UUID.randomUUID()
        val currentSongId = UUID.randomUUID()
        dbQuery {
            SchemaUtils.create(AlbumTable, SongTable, SongVariantTable, SongAudioTimelineTable)
            val album = AlbumTable.insert {
                it[id] = UUID.randomUUID()
                it[name] = "Album"
            }[AlbumTable.id]
            listOf(staleSongId, currentSongId).forEach { id ->
                SongTable.insert {
                    it[SongTable.id] = id
                    it[title] = "Song $id"
                    it[albumId] = album
                }
            }
            SongAudioTimelineTable.insert {
                it[songId] = staleSongId
                it[version] = 2
                it[status] = AudioTimelineStatus.OK
                it[beatSource] = AudioTimelineSource.ESSENTIA
                it[analyzedAt] = System.currentTimeMillis()
            }
            SongAudioTimelineTable.insert {
                it[songId] = currentSongId
                it[version] = 3
                it[status] = AudioTimelineStatus.OK
                it[beatSource] = AudioTimelineSource.ESSENTIA
                it[analyzedAt] = System.currentTimeMillis()
            }
        }
        val service = AudioAnalysisService()
        assertEquals(listOf(staleSongId), service.getSongIdsWithStaleTimeline())
    }
}
