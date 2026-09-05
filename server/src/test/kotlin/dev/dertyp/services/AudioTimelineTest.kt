package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.SongAudioTimelineTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.SongVariantTable
import dev.dertyp.dbQuery
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
            fun timeline(id: UUID, status: String, analyzedAt: Long) = SongAudioTimelineTable.insert {
                it[songId] = id
                it[version] = 1
                it[SongAudioTimelineTable.status] = status
                it[beatSource] = "essentia"
                it[SongAudioTimelineTable.analyzedAt] = analyzedAt
            }
            timeline(songs[0], AudioAnalysisService.TIMELINE_STATUS_OK, now)
            timeline(songs[1], AudioAnalysisService.TIMELINE_STATUS_FAILED, now)
            timeline(songs[2], AudioAnalysisService.TIMELINE_STATUS_FAILED, now - 10 * 24 * 60 * 60 * 1000L)
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
}
