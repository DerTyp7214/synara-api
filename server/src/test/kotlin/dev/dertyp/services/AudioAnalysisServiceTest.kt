package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.db.AlbumTable
import dev.dertyp.db.PersonTable
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.SongComposerTable
import dev.dertyp.db.SongLyricistTable
import dev.dertyp.db.SongProducerTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class AudioAnalysisServiceTest {

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_analysis_test")
        dbQuery {
            SchemaUtils.create(
                AlbumTable, SongTable, SongAudioDataTable,
                PersonTable, SongComposerTable, SongLyricistTable, SongProducerTable
            )
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @Test
    fun `should parse essentia output correctly`() {
        val jsonText = this::class.java.classLoader.getResource("essentia_output.json")?.readText()
        assertNotNull(jsonText, "Test data essentia_output.json not found")

        val output = ApplicationScope.json.decodeFromString<EssentiaOutput>(jsonText)
        val audioData = output.toSongAudioData()

        assertNotNull(audioData.bpm)
        assertNotNull(audioData.key)
        assertNotNull(audioData.scale)
        assertNotNull(audioData.loudness)
        assertNotNull(audioData.energy)
        assertNotNull(audioData.danceability)
        assertNotNull(audioData.composer)
        assertNotNull(audioData.lyricist)
        assertNotNull(audioData.producers)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should trigger analysis and correctly persist data to database`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val songId = UUID.randomUUID()
        val albumId = UUID.randomUUID()

        dbQuery {
            AlbumTable.insert {
                it[id] = albumId
                it[name] = "Test Album"
            }
            SongTable.insert {
                it[id] = songId
                it[title] = "Test Song"
                it[filePath] = "/tmp/test.flac"
                it[duration] = 1000
                it[explicit] = false
                it[this.albumId] = albumId
            }
        }

        val jsonText = this::class.java.classLoader.getResource("essentia_output.json")?.readText()
        assertNotNull(jsonText)
        val expectedData = ApplicationScope.json.decodeFromString<EssentiaOutput>(jsonText).toSongAudioData()

        val service = spyk<AudioAnalysisService>(recordPrivateCalls = true)
        every { service getProperty "essentiaExtractorPath" } returns "/usr/bin/mock_essentia"

        coEvery { service["runEssentia"](any<String>(), any<String>()) } coAnswers {
            val outputPath = secondArg<String>()
            File(outputPath).writeText(jsonText)
            0
        }

        val result = service.getAudioData(songId)
        assertNotNull(result)

        assertEquals(expectedData.bpm, result.bpm)
        assertEquals(expectedData.composer, result.composer)
        assertEquals(expectedData.lyricist, result.lyricist)
        assertEquals(expectedData.producers, result.producers)

        dbQuery {
            val dbRow = SongAudioDataTable.selectAll().where { SongAudioDataTable.songId eq songId }.single()
            assertEquals(expectedData.bpm, dbRow[SongAudioDataTable.bpm])
            assertEquals(expectedData.energy, dbRow[SongAudioDataTable.energy])
        }

        dbQuery {
            val persons = PersonTable.selectAll().map { it[PersonTable.name] }
            expectedData.composer?.forEach { assertTrue(persons.contains(it)) }
            expectedData.lyricist?.forEach { assertTrue(persons.contains(it)) }
            expectedData.producers?.forEach { assertTrue(persons.contains(it)) }
        }
    }
}
