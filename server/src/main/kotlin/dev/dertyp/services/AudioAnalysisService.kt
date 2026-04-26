package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.SongAudioData
import dev.dertyp.db.PersonTable
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.SongComposerTable
import dev.dertyp.db.SongLyricistTable
import dev.dertyp.db.SongProducerTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.File
import java.util.UUID

@Serializable
internal data class EssentiaOutput(
    @SerialName("lowlevel")
    val lowLevel: LowLevel? = null,
    @SerialName("rhythm")
    val rhythm: Rhythm? = null,
    @SerialName("tonal")
    val tonal: Tonal? = null,
    @SerialName("metadata")
    val metadata: Metadata? = null
) {
    fun toSongAudioData() = SongAudioData(
        bpm = rhythm?.bpm,
        key = tonal?.keyEdma?.key,
        scale = tonal?.keyEdma?.scale,
        loudness = lowLevel?.loudnessEbu128?.integrated,
        energy = lowLevel?.spectralEnergy?.mean,
        danceability = rhythm?.danceability,
        composer = metadata?.tags?.composer,
        lyricist = metadata?.tags?.lyricist,
        producers = metadata?.tags?.producer
    )

    @Serializable
    data class Metadata(
        @SerialName("tags")
        val tags: Tags? = null
    )

    @Serializable
    data class Tags(
        @SerialName("composer")
        val composer: List<String>? = null,
        @SerialName("lyricist")
        val lyricist: List<String>? = null,
        @SerialName("producer")
        val producer: List<String>? = null
    )

    @Serializable
    data class LowLevel(
        @SerialName("loudness_ebu128")
        val loudnessEbu128: LoudnessEbu128? = null,
        @SerialName("spectral_energy")
        val spectralEnergy: Statistics? = null,
        @SerialName("average_loudness")
        val averageLoudness: Double? = null,
        @SerialName("dissonance")
        val dissonance: Statistics? = null
    )

    @Serializable
    data class LoudnessEbu128(
        @SerialName("integrated")
        val integrated: Double? = null
    )

    @Serializable
    data class Statistics(
        @SerialName("mean")
        val mean: Double? = null,
        @SerialName("stdev")
        val stdev: Double? = null
    )

    @Serializable
    data class Rhythm(
        @SerialName("bpm")
        val bpm: Double? = null,
        @SerialName("danceability")
        val danceability: Double? = null
    )

    @Serializable
    data class Tonal(
        @SerialName("key_edma")
        val keyEdma: KeyEdma? = null
    )

    @Serializable
    data class KeyEdma(
        @SerialName("key")
        val key: String? = null,
        @SerialName("scale")
        val scale: String? = null
    )
}

@OptIn(ExperimentalSerializationApi::class)
open class AudioAnalysisService : IAudioAnalysisService, Service() {
    protected open val essentiaExtractorPath = findInPath("essentia_streaming_extractor_music")

    override suspend fun getAudioData(songId: PlatformUUID): SongAudioData? = dbQuery {
        val existing = SongAudioDataTable.select(SongAudioDataTable.columns)
            .where { SongAudioDataTable.songId eq songId }
            .singleOrNull()

        if (existing != null) return@dbQuery mapAudioData(existing)

        analyzeSong(songId)

        SongAudioDataTable.select(SongAudioDataTable.columns)
            .where { SongAudioDataTable.songId eq songId }
            .singleOrNull()?.let { mapAudioData(it) }
    }

    suspend fun getAudioDataBatch(songIds: Collection<PlatformUUID>): Map<PlatformUUID, SongAudioData> =
        songIds.chunked(1000).flatMap { chunk ->
            dbQuery {
                SongAudioDataTable.select(SongAudioDataTable.columns)
                    .where { SongAudioDataTable.songId inList chunk }
                    .map { row -> row[SongAudioDataTable.songId].value to mapAudioData(row) }
            }
        }.toMap()

    override suspend fun analyzeSong(songId: PlatformUUID) {
        val filePath = dbQuery {
            SongTable.select(SongTable.filePath)
                .where { SongTable.id eq songId }
                .singleOrNull()?.get(SongTable.filePath)
        } ?: return

        var audioData = if (essentiaExtractorPath != null) {
            analyzeWithEssentia(filePath)
        } else null

        if (audioData == null) {
            val tags = try {
                val audioFile = AudioFileIO.read(File(filePath))
                audioFile.tag
            } catch (e: Exception) {
                logger.error("Failed to read tags from $filePath: ${e.message}")
                null
            }

            val bpm = tags?.getFirst(FieldKey.BPM)?.toDoubleOrNull()
            val key = tags?.getFirst(FieldKey.KEY)

            audioData = SongAudioData(
                bpm = bpm,
                key = key?.takeIf { it.isNotBlank() }
            )
        }

        saveAudioData(songId, audioData)
    }

    suspend fun getUnanalyzedSongIds(): List<PlatformUUID> = dbQuery {
        SongTable
            .leftJoin(SongAudioDataTable)
            .select(SongTable.id)
            .where { SongAudioDataTable.songId.isNull() }
            .map { it[SongTable.id].value }
    }

    private suspend fun analyzeWithEssentia(filePath: String): SongAudioData? {
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("essentia_", ".json")
        }
        try {
            val exitCode = runEssentia(filePath, tempFile.absolutePath)

            if (exitCode == 0 && tempFile.exists()) {
                val jsonText = tempFile.readText()
                val essentia = ApplicationScope.json.decodeFromString<EssentiaOutput>(jsonText)

                return essentia.toSongAudioData()
            }
        } catch (e: Exception) {
            logger.error("Essentia analysis failed: ${e.message}")
        } finally {
            tempFile.delete()
        }
        return null
    }

    protected open suspend fun runEssentia(inputPath: String, outputPath: String): Int {
        val path = essentiaExtractorPath ?: return -1
        return executeCommand(
            command = listOf(path, inputPath, outputPath),
            aliveCheck = { true },
            logger = logger
        ).exitCode
    }

    private suspend fun saveAudioData(songId: PlatformUUID, audioData: SongAudioData) = dbQuery {
        SongAudioDataTable.upsert(SongAudioDataTable.songId) {
            it[SongAudioDataTable.songId] = songId
            it[bpm] = audioData.bpm
            it[key] = audioData.key
            it[scale] = audioData.scale
            it[loudness] = audioData.loudness
            it[energy] = audioData.energy
            it[valence] = audioData.valence
            it[danceability] = audioData.danceability
            it[acousticness] = audioData.acousticness
            it[instrumentalness] = audioData.instrumentalness
            it[speechiness] = audioData.speechiness
        }

        saveCredits(songId, audioData.composer, SongComposerTable)
        saveCredits(songId, audioData.lyricist, SongLyricistTable)
        saveCredits(songId, audioData.producers, SongProducerTable)
    }

    private fun saveCredits(songId: PlatformUUID, names: List<String>?, table: Table) {
        if (names == null) return
        names.forEach { name ->
            val personId = PersonTable.insertIgnore {
                it[this.name] = name
            }.getOrNull(PersonTable.id) ?: PersonTable.select(PersonTable.id).where { PersonTable.name eq name }.single()[PersonTable.id]

            table.insertIgnore {
                @Suppress("UNCHECKED_CAST")
                val sId = table.columns[0] as Column<EntityID<UUID>>
                @Suppress("UNCHECKED_CAST")
                val pId = table.columns[1] as Column<EntityID<UUID>>
                it[sId] = EntityID(songId, SongTable)
                it[pId] = personId
            }
        }
    }

    private fun mapAudioData(row: ResultRow): SongAudioData {
        val songId = row[SongAudioDataTable.songId].value
        return SongAudioData(
            bpm = row[SongAudioDataTable.bpm],
            key = row[SongAudioDataTable.key],
            scale = row[SongAudioDataTable.scale],
            loudness = row[SongAudioDataTable.loudness],
            energy = row[SongAudioDataTable.energy],
            valence = row[SongAudioDataTable.valence],
            danceability = row[SongAudioDataTable.danceability],
            acousticness = row[SongAudioDataTable.acousticness],
            instrumentalness = row[SongAudioDataTable.instrumentalness],
            speechiness = row[SongAudioDataTable.speechiness],
            composer = getCredits(songId, SongComposerTable),
            lyricist = getCredits(songId, SongLyricistTable),
            producers = getCredits(songId, SongProducerTable)
        )
    }

    private fun getCredits(songId: PlatformUUID, table: Table): List<String> {
        @Suppress("UNCHECKED_CAST")
        val songIdCol = table.columns[0] as Column<EntityID<UUID>>
        @Suppress("UNCHECKED_CAST")
        val personIdCol = table.columns[1] as Column<EntityID<UUID>>

        return table.join(PersonTable, JoinType.INNER, personIdCol, PersonTable.id)
            .select(PersonTable.name)
            .where { songIdCol eq songId }
            .map { it[PersonTable.name] }
    }
}
