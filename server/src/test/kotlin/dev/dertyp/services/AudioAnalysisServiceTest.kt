package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.AudioScale
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.services.audio.ValencePostProcessor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID
import kotlin.test.*

@OptIn(ExperimentalSerializationApi::class)
class AudioAnalysisServiceTest {

    private fun setup(dialect: DbDialect) = runBlocking {
        TestDatabase.connect(dialect, "audio_analysis_test")
        dbQuery {
            SchemaUtils.create(
                AlbumTable, SongTable, SongVariantTable, SongAudioDataTable, SongAudioTimelineTable,
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
        assertEquals(AudioScale.Minor, audioData.scale)
        assertNotNull(audioData.loudness)
        assertNotNull(audioData.energy)
        assertNotNull(audioData.danceability)
        assertNotNull(audioData.composer)
        assertNotNull(audioData.lyricist)
        assertNotNull(audioData.producers)
        assertNull(audioData.valence)
    }

    @Test
    fun `ValencePostProcessor should calculate mood correctly`() {
        val processor = ValencePostProcessor()
        val essentia = EssentiaOutput(
            lowLevel = EssentiaOutput.LowLevel(
                dissonance = EssentiaOutput.Statistics(mean = 0.1)
            ),
            rhythm = EssentiaOutput.Rhythm(
                bpm = 128.0,
                danceability = 2.0
            ),
            tonal = EssentiaOutput.Tonal(
                keyEdma = EssentiaOutput.KeyEdma(scale = AudioScale.Major)
            )
        )

        val rawData = essentia.toSongAudioData()
        val processedData = processor.process(essentia, rawData)

        assertNotNull(processedData.energy)
        assertNotNull(processedData.valence)
        assertTrue(processedData.energy!! > 0.6, "Energy should be high for 128 BPM/2.0 Danceability")
        assertTrue(processedData.valence!! > 0.7, "Valence should be high for Major/Low Dissonance")
    }

    @Test
    fun `ValencePostProcessor should handle Gloomy tracks correctly`() {
        val processor = ValencePostProcessor()
        val essentia = EssentiaOutput(
            lowLevel = EssentiaOutput.LowLevel(
                dissonance = EssentiaOutput.Statistics(mean = 0.5)
            ),
            rhythm = EssentiaOutput.Rhythm(
                bpm = 70.0,
                danceability = 0.6
            ),
            tonal = EssentiaOutput.Tonal(
                keyEdma = EssentiaOutput.KeyEdma(scale = AudioScale.Minor)
            )
        )

        val rawData = essentia.toSongAudioData()
        val processedData = processor.process(essentia, rawData)

        assertTrue(processedData.energy!! < 0.4, "Energy should be low")
        assertTrue(processedData.valence!! < 0.4, "Valence should be low")
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should trigger analysis and correctly persist data to database`(dialect: DbDialect): Unit = runBlocking {
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
        assertEquals(expectedData.composer?.sorted(), result.composer?.sorted())
        assertEquals(expectedData.lyricist?.sorted(), result.lyricist?.sorted())
        assertEquals(expectedData.producers?.sorted(), result.producers?.sorted())
        assertNotNull(result.valence, "Valence should be calculated via post-processor")
        assertNotEquals(expectedData.energy, result.energy, "Energy should have been recalculated by post-processor")

        dbQuery {
            val dbRow = SongAudioDataTable.selectAll().where { SongAudioDataTable.songId eq songId }.single()
            assertEquals(result.bpm, dbRow[SongAudioDataTable.bpm])
            assertEquals(result.energy, dbRow[SongAudioDataTable.energy])
            assertEquals(result.valence, dbRow[SongAudioDataTable.valence])
        }

        dbQuery {
            val persons = PersonTable.selectAll().map { it[PersonTable.name] }.sorted()
            val expectedPersons = (expectedData.composer.orEmpty() + expectedData.lyricist.orEmpty() + expectedData.producers.orEmpty()).distinct().sorted()
            
            for (person in expectedPersons) {
                assertTrue(persons.contains(person), "Person $person should be in the database")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `should handle missing data by persisting nulls`(dialect: DbDialect) = runBlocking {
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
                it[title] = "Null Song"
                it[filePath] = "/tmp/null.flac"
                it[duration] = 1000
                it[explicit] = false
                it[this.albumId] = albumId
            }
        }

        val jsonText = "{}"

        val service = spyk<AudioAnalysisService>(recordPrivateCalls = true)
        every { service getProperty "essentiaExtractorPath" } returns "/usr/bin/mock_essentia"

        coEvery { service["runEssentia"](any<String>(), any<String>()) } coAnswers {
            val outputPath = secondArg<String>()
            File(outputPath).writeText(jsonText)
            0
        }

        val result = service.getAudioData(songId)
        assertNotNull(result)

        assertNull(result.bpm)
        assertNull(result.energy)
        assertNull(result.valence)

        dbQuery {
            val dbRow = SongAudioDataTable.selectAll().where { SongAudioDataTable.songId eq songId }.single()
            assertNull(dbRow[SongAudioDataTable.bpm])
            assertNull(dbRow[SongAudioDataTable.energy])
            assertNull(dbRow[SongAudioDataTable.valence])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `saveCredits should overwrite old credits and handle duplicate persons`(dialect: DbDialect) = runBlocking {
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

        val service = spyk<AudioAnalysisService>(recordPrivateCalls = true)
        every { service getProperty "essentiaExtractorPath" } returns "/usr/bin/mock_essentia"

        val firstJson = """
            {
                "metadata": {
                    "tags": {
                        "composer": ["Composer A", "Composer B"],
                        "lyricist": ["Lyricist A"],
                        "producer": ["Producer A"]
                    }
                }
            }
        """.trimIndent()

        coEvery { service["runEssentia"](any<String>(), any<String>()) } coAnswers {
            val outputPath = secondArg<String>()
            File(outputPath).writeText(firstJson)
            0
        }

        service.analyzeSong(songId)

        dbQuery {
            assertEquals(2, SongComposerTable.selectAll().where { SongComposerTable.songId eq songId }.count())
            assertEquals(1, SongLyricistTable.selectAll().where { SongLyricistTable.songId eq songId }.count())
            assertEquals(1, SongProducerTable.selectAll().where { SongProducerTable.songId eq songId }.count())
            assertEquals(4, PersonTable.selectAll().count())
        }

        val secondJson = """
            {
                "metadata": {
                    "tags": {
                        "composer": ["Composer B", "Composer C"],
                        "lyricist": ["Lyricist B"],
                        "producer": []
                    }
                }
            }
        """.trimIndent()

        coEvery { service["runEssentia"](any<String>(), any<String>()) } coAnswers {
            val outputPath = secondArg<String>()
            File(outputPath).writeText(secondJson)
            0
        }

        service.analyzeSong(songId)

        dbQuery {
            val composers = SongComposerTable
                .innerJoin(PersonTable, onColumn = { SongComposerTable.personId }, otherColumn = { PersonTable.id })
                .select(PersonTable.name)
                .where { SongComposerTable.songId eq songId }
                .map { it[PersonTable.name] }

            assertEquals(2, composers.size)
            assertTrue(composers.contains("Composer B"))
            assertTrue(composers.contains("Composer C"))

            val lyricists = SongLyricistTable
                .innerJoin(PersonTable, onColumn = { SongLyricistTable.personId }, otherColumn = { PersonTable.id })
                .select(PersonTable.name)
                .where { SongLyricistTable.songId eq songId }
                .map { it[PersonTable.name] }
            assertEquals(1, lyricists.size)
            assertEquals("Lyricist B", lyricists.first())

            val producers = SongProducerTable.selectAll().where { SongProducerTable.songId eq songId }.count()
            assertEquals(0, producers)

            assertEquals(6, PersonTable.selectAll().count())
        }
    }
}
