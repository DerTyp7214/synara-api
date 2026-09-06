package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.AudioScale
import dev.dertyp.data.SongAudioData
import dev.dertyp.data.SongAudioTimeline
import dev.dertyp.db.PersonTable
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.AudioTimelineSource
import dev.dertyp.db.AudioTimelineStatus
import dev.dertyp.db.SongAudioTimelineTable
import dev.dertyp.db.SongComposerTable
import dev.dertyp.db.SongLyricistTable
import dev.dertyp.db.SongProducerTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.services.audio.AudioAnalysisPostProcessor
import dev.dertyp.services.audio.AudioTimelineCodec
import dev.dertyp.services.audio.RmsEnvelopeExtractor
import dev.dertyp.services.audio.ValencePostProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.orWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.days

@OptIn(ExperimentalSerializationApi::class)
open class AudioAnalysisService : IAudioAnalysisService, Service() {
    protected open val essentiaExtractorPath = findInPath("essentia_streaming_extractor_music")

    protected open val postProcessors: List<AudioAnalysisPostProcessor> = listOf(
        ValencePostProcessor()
    )

    companion object {
        const val ENVELOPE_HZ = 10
        val TIMELINE_RETRY_INTERVAL = 7.days.inWholeMilliseconds
    }

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

        val essentiaResult = if (essentiaExtractorPath != null) {
            analyzeWithEssentia(filePath)
        } else null
        var audioData = essentiaResult?.first

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
        saveTimeline(songId, filePath, essentiaResult?.second)
    }

    suspend fun getUnanalyzedSongIds(): List<PlatformUUID> = dbQuery {
        SongTable
            .leftJoin(SongAudioDataTable)
            .select(SongTable.id)
            .where { SongAudioDataTable.songId.isNull() }
            .map { it[SongTable.id].value }
    }

    suspend fun getSongIdsMissingTimeline(limit: Int, retryFailedAfterMs: Long = TIMELINE_RETRY_INTERVAL): List<PlatformUUID> = dbQuery {
        val cutoff = System.currentTimeMillis() - retryFailedAfterMs
        SongTable
            .leftJoin(SongAudioTimelineTable)
            .select(SongTable.id)
            .where { SongAudioTimelineTable.songId.isNull() }
            .orWhere { (SongAudioTimelineTable.status eq AudioTimelineStatus.FAILED) and (SongAudioTimelineTable.analyzedAt less cutoff) }
            .limit(limit)
            .map { it[SongTable.id].value }
    }

    override suspend fun getAudioTimeline(songId: PlatformUUID): SongAudioTimeline? = dbQuery {
        SongAudioTimelineTable.selectAll()
            .where { SongAudioTimelineTable.songId eq songId }
            .singleOrNull()
            ?.let { mapTimeline(it) }
    }

    suspend fun getSongIdsWithStaleTimeline(limit: Int): List<PlatformUUID> = dbQuery {
        SongAudioTimelineTable
            .select(SongAudioTimelineTable.songId)
            .where { SongAudioTimelineTable.status neq AudioTimelineStatus.FAILED }
            .andWhere { SongAudioTimelineTable.version less AudioTimelineCodec.VERSION }
            .limit(limit)
            .map { it[SongAudioTimelineTable.songId].value }
    }

    suspend fun refreshEnvelopes(songId: PlatformUUID) {
        val filePath = dbQuery {
            SongTable.select(SongTable.filePath)
                .where { SongTable.id eq songId }
                .singleOrNull()?.get(SongTable.filePath)
        } ?: return

        val envelopes = extractEnvelopes(filePath) ?: return

        dbQuery {
            SongAudioTimelineTable.update({ SongAudioTimelineTable.songId eq songId }) {
                it[envelope] = encodeEnvelope(envelopes.rmsDb)
                it[bassEnvelope] = encodeEnvelope(envelopes.bassDb)
                it[envelopeHz] = ENVELOPE_HZ
                it[envelopeMinDb] = RmsEnvelopeExtractor.MIN_DB.toDouble()
                it[envelopeMaxDb] = RmsEnvelopeExtractor.MAX_DB.toDouble()
                it[version] = AudioTimelineCodec.VERSION
                it[analyzedAt] = System.currentTimeMillis()
            }
        }
    }

    private suspend fun extractEnvelopes(filePath: String): RmsEnvelopeExtractor.Envelopes? = withContext(Dispatchers.IO) {
        runCatching { RmsEnvelopeExtractor.extract(File(filePath), ENVELOPE_HZ) }
            .onFailure { logger.warn("Loudness envelope extraction failed for $filePath: ${it.message}") }
            .getOrNull()
    }

    private fun encodeEnvelope(values: FloatArray): ByteArray =
        AudioTimelineCodec.encodeEnvelope(values, RmsEnvelopeExtractor.MIN_DB, RmsEnvelopeExtractor.MAX_DB)

    private suspend fun saveTimeline(songId: PlatformUUID, filePath: String, essentia: EssentiaOutput?) {
        val beats = essentia?.rhythm?.beatsPosition?.takeIf { it.isNotEmpty() }
        val envelope = extractEnvelopes(filePath)
        val status = when {
            beats != null && envelope != null -> AudioTimelineStatus.OK
            beats == null && envelope == null -> AudioTimelineStatus.FAILED
            else -> AudioTimelineStatus.PARTIAL
        }
        val source = when {
            beats != null -> AudioTimelineSource.ESSENTIA
            envelope != null -> AudioTimelineSource.RMS
            else -> AudioTimelineSource.NONE
        }

        dbQuery {
            SongAudioTimelineTable.upsert(SongAudioTimelineTable.songId) {
                it[SongAudioTimelineTable.songId] = songId
                it[version] = AudioTimelineCodec.VERSION
                it[SongAudioTimelineTable.status] = status
                it[SongAudioTimelineTable.beatSource] = source
                it[analyzedAt] = System.currentTimeMillis()
                it[SongAudioTimelineTable.beats] = beats?.let { positions -> AudioTimelineCodec.encodeBeats(positions) }
                it[beatsCount] = essentia?.rhythm?.beatsCount?.toInt() ?: beats?.size
                it[onsetRate] = essentia?.rhythm?.onsetRate
                it[beatsLoudnessMean] = essentia?.rhythm?.beatsLoudness?.mean
                it[beatsLoudnessMax] = essentia?.rhythm?.beatsLoudness?.max
                it[SongAudioTimelineTable.envelope] = envelope?.let { values -> encodeEnvelope(values.rmsDb) }
                it[bassEnvelope] = envelope?.let { values -> encodeEnvelope(values.bassDb) }
                it[envelopeHz] = ENVELOPE_HZ
                it[envelopeMinDb] = RmsEnvelopeExtractor.MIN_DB.toDouble()
                it[envelopeMaxDb] = RmsEnvelopeExtractor.MAX_DB.toDouble()
                it[loudnessRange] = essentia?.lowLevel?.loudnessEbu128?.loudnessRange
                it[dynamicComplexity] = essentia?.lowLevel?.dynamicComplexity
            }
        }
    }

    private fun mapTimeline(row: ResultRow): SongAudioTimeline {
        val minDb = row[SongAudioTimelineTable.envelopeMinDb].toFloat()
        val maxDb = row[SongAudioTimelineTable.envelopeMaxDb].toFloat()
        return SongAudioTimeline(
            songId = row[SongAudioTimelineTable.songId].value,
            beatsMs = row[SongAudioTimelineTable.beats]?.let { AudioTimelineCodec.decodeBeats(it).toList() } ?: emptyList(),
            beatsCount = row[SongAudioTimelineTable.beatsCount],
            onsetRate = row[SongAudioTimelineTable.onsetRate],
            envelopeHz = row[SongAudioTimelineTable.envelopeHz],
            envelopeDb = row[SongAudioTimelineTable.envelope]?.let { AudioTimelineCodec.decodeEnvelope(it, minDb, maxDb).toList() } ?: emptyList(),
            bassEnvelopeDb = row[SongAudioTimelineTable.bassEnvelope]?.let { AudioTimelineCodec.decodeEnvelope(it, minDb, maxDb).toList() } ?: emptyList(),
            loudnessRange = row[SongAudioTimelineTable.loudnessRange],
            dynamicComplexity = row[SongAudioTimelineTable.dynamicComplexity],
            source = row[SongAudioTimelineTable.beatSource].name.lowercase(),
            version = row[SongAudioTimelineTable.version],
        )
    }

    private suspend fun analyzeWithEssentia(filePath: String): Pair<SongAudioData, EssentiaOutput>? {
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("essentia_", ".json")
        }
        try {
            val exitCode = runEssentia(filePath, tempFile.absolutePath)

            if (exitCode == 0 && tempFile.exists()) {
                val jsonText = tempFile.readText()
                val essentia = ApplicationScope.json.decodeFromString<EssentiaOutput>(jsonText)

                var audioData = essentia.toSongAudioData()
                postProcessors.forEach {
                    audioData = it.process(essentia, audioData)
                }

                return audioData to essentia
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
            it[scale] = audioData.scale?.name?.lowercase()
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
        @Suppress("UNCHECKED_CAST")
        val songIdCol = table.columns.first { it.name == "songId" } as Column<EntityID<UUID>>
        @Suppress("UNCHECKED_CAST")
        val personIdCol = table.columns.first { it.name == "personId" } as Column<EntityID<UUID>>

        if (names == null) return

        table.deleteWhere { songIdCol eq songId }

        names.filter { it.isNotBlank() }.distinct().forEach { name ->
            PersonTable.insertIgnore {
                it[this.name] = name
            }
            val personId = PersonTable.select(PersonTable.id).where { PersonTable.name eq name }.single()[PersonTable.id]

            table.insert {
                it[songIdCol] = EntityID(songId, SongTable)
                it[personIdCol] = personId
            }
        }
    }

    private fun mapAudioData(row: ResultRow): SongAudioData {
        val songId = row[SongAudioDataTable.songId].value
        return SongAudioData(
            bpm = row[SongAudioDataTable.bpm],
            key = row[SongAudioDataTable.key],
            scale = AudioScale.fromString(row[SongAudioDataTable.scale]),
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
        val songIdCol = table.columns.first { it.name == "songId" } as Column<EntityID<UUID>>
        @Suppress("UNCHECKED_CAST")
        val personIdCol = table.columns.first { it.name == "personId" } as Column<EntityID<UUID>>

        return table.innerJoin(PersonTable, onColumn = { personIdCol }, otherColumn = { PersonTable.id })
            .select(PersonTable.name)
            .where { songIdCol eq songId }
            .map { it[PersonTable.name] }
    }
}
