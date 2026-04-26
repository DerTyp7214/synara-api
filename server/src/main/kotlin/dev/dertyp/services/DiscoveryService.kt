package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.SongAudioData
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.SongComposerTable
import dev.dertyp.db.SongLyricistTable
import dev.dertyp.db.SongProducerTable
import dev.dertyp.dbQuery
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.koin.core.component.inject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class DiscoveryRpcService(
    private val user: User,
    private val discoveryService: DiscoveryService
) : IDiscoveryService {
    override suspend fun getSimilarSongs(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSimilarSongs(seedSongIds, limit, user.id)
    }

    override suspend fun getSimilarSongsByPlaylist(playlistId: PlatformUUID, limit: Int): List<UserSong> {
        return discoveryService.getSimilarSongsByPlaylist(playlistId, limit, user.id)
    }

    override suspend fun getSimilarSongsByBpm(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSimilarSongsByBpm(seedSongIds, limit, user.id)
    }

    override suspend fun getSimilarSongsByEnergy(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSimilarSongsByEnergy(seedSongIds, limit, user.id)
    }

    override suspend fun getSimilarSongsByMood(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSimilarSongsByMood(seedSongIds, limit, user.id)
    }

    override suspend fun getSongsBySameComposers(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSongsBySameComposers(seedSongIds, limit, user.id)
    }

    override suspend fun getSongsBySameLyricists(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSongsBySameLyricists(seedSongIds, limit, user.id)
    }

    override suspend fun getSongsBySameProducers(seedSongIds: List<PlatformUUID>, limit: Int): List<UserSong> {
        return discoveryService.getSongsBySameProducers(seedSongIds, limit, user.id)
    }
}

class DiscoveryService : Service() {
    private val songService: SongService by inject()
    private val audioAnalysisService: AudioAnalysisService by inject()

    suspend fun getSongsBySameComposers(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSongsBySameCredits(seedSongIds, SongComposerTable, limit, userId)
    }

    suspend fun getSongsBySameLyricists(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSongsBySameCredits(seedSongIds, SongLyricistTable, limit, userId)
    }

    suspend fun getSongsBySameProducers(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSongsBySameCredits(seedSongIds, SongProducerTable, limit, userId)
    }

    suspend fun getSimilarSongsByPlaylist(
        playlistId: PlatformUUID,
        limit: Int,
        userId: PlatformUUID
    ): List<UserSong> {
        val songIds = songService.songIdsByUserPlaylist(playlistId).toList()
        return getSimilarSongs(songIds, limit, userId)
    }

    suspend fun getSimilarSongsByBpm(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSimilarSongsByFeature(seedSongIds, limit, userId) { target, candidate ->
            1.0 - abs(normalize(target.bpm, 50.0, 200.0) - normalize(candidate.bpm, 50.0, 200.0))
        }
    }

    suspend fun getSimilarSongsByEnergy(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSimilarSongsByFeature(seedSongIds, limit, userId) { target, candidate ->
            1.0 - abs(target.energy - candidate.energy)
        }
    }

    suspend fun getSimilarSongsByMood(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSimilarSongsByFeature(seedSongIds, limit, userId) { target, candidate ->
            1.0 - abs(target.valence - candidate.valence)
        }
    }

    private suspend fun getSimilarSongsByFeature(
        seedSongIds: List<PlatformUUID>,
        limit: Int,
        userId: PlatformUUID,
        scoring: (FeatureVector, FeatureVector) -> Double
    ): List<UserSong> {
        val seedSet = seedSongIds.toSet()
        val seeds = audioAnalysisService.getAudioDataBatch(seedSongIds).values.map(::mapToFeatureVector)
        if (seeds.isEmpty()) return emptyList()

        val scoreBoard = mutableMapOf<PlatformUUID, Double>()
        val allIds = dbQuery {
            SongAudioDataTable.select(SongAudioDataTable.songId).map { it[SongAudioDataTable.songId].value }
        }

        allIds.chunked(1000).forEach { chunk ->
            dbQuery {
                SongAudioDataTable.select(SongAudioDataTable.columns)
                    .where { SongAudioDataTable.songId inList chunk }
                    .forEach { row ->
                        val candidateId = row[SongAudioDataTable.songId].value
                        if (candidateId in seedSet) return@forEach

                        val candidateVector = mapAudioDataToFeatureVector(row)
                        seeds.forEach { seed ->
                            val score = scoring(seed, candidateVector)
                            if (score > 0.6) {
                                scoreBoard[candidateId] = (scoreBoard[candidateId] ?: 0.0) + score
                            }
                        }
                    }
            }
        }

        val topIds = scoreBoard.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

        return topIds.chunked(10000).flatMap { songService.byIds(it, userId) }
    }

    suspend fun getSimilarSongs(
        seedSongIds: List<PlatformUUID>,
        limit: Int,
        userId: PlatformUUID
    ): List<UserSong> {
        val seedSet = seedSongIds.toSet()
        val seeds = audioAnalysisService.getAudioDataBatch(seedSongIds).values.map(::mapToFeatureVector)
        if (seeds.isEmpty()) return emptyList()

        val scoreBoard = mutableMapOf<PlatformUUID, Double>()

        val allIds = dbQuery {
            SongAudioDataTable.select(SongAudioDataTable.songId).map { it[SongAudioDataTable.songId].value }
        }

        allIds.chunked(1000).forEach { chunk ->
            dbQuery {
                SongAudioDataTable.select(SongAudioDataTable.columns)
                    .where { SongAudioDataTable.songId inList chunk }
                    .forEach { row ->
                        val candidateId = row[SongAudioDataTable.songId].value
                        if (candidateId in seedSet) return@forEach

                        val candidateVector = mapAudioDataToFeatureVector(row)
                        seeds.forEach { seed ->
                            val score = calculateSimilarity(seed, candidateVector)
                            if (score > 0.6) {
                                scoreBoard[candidateId] = (scoreBoard[candidateId] ?: 0.0) + score
                            }
                        }
                    }
            }
        }

        val topIds = scoreBoard.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

        return topIds.chunked(10000).flatMap { songService.byIds(it, userId) }
    }

    suspend fun getSongsBySameCredits(
        seedSongIds: List<PlatformUUID>,
        creditTable: Table,
        limit: Int,
        userId: PlatformUUID
    ): List<UserSong> = dbQuery {
        @Suppress("UNCHECKED_CAST")
        val songIdCol = creditTable.columns[0] as Column<EntityID<UUID>>
        @Suppress("UNCHECKED_CAST")
        val personIdCol = creditTable.columns[1] as Column<EntityID<UUID>>

        val seedSet = seedSongIds.toSet()
        
        val personIds = seedSongIds.chunked(1000).flatMap { chunk ->
            creditTable.select(personIdCol)
                .where { songIdCol inList chunk }
                .map { it[personIdCol] }
        }.distinct()

        if (personIds.isEmpty()) return@dbQuery emptyList()

        val matchedSongIds = personIds.chunked(1000).flatMap { chunk ->
            creditTable.select(songIdCol)
                .where { personIdCol inList chunk }
                .map { it[songIdCol].value }
        }.filter { it !in seedSet }.distinct()

        matchedSongIds.take(limit).chunked(10000).flatMap { songService.byIds(it, userId) }
    }

    private fun calculateSimilarity(target: FeatureVector, candidate: FeatureVector): Double {
        val dBpm = normalize(target.bpm, 50.0, 200.0) - normalize(candidate.bpm, 50.0, 200.0)
        val dEnergy = target.energy - candidate.energy
        val dDance = target.danceability - candidate.danceability
        val dLoudness = normalize(target.loudness, -60.0, 0.0) - normalize(candidate.loudness, -60.0, 0.0)
        val dAcoustic = target.acousticness - candidate.acousticness
        val dInstrumental = target.instrumentalness - candidate.instrumentalness
        val dSpeech = target.speechiness - candidate.speechiness

        val distance = sqrt(
            (dBpm * 0.2).pow(2) +
            (dEnergy * 0.3).pow(2) +
            (dDance * 0.3).pow(2) +
            (dLoudness * 0.1).pow(2) +
            (dAcoustic * 0.1).pow(2) +
            (dInstrumental * 0.1).pow(2) +
            (dSpeech * 0.05).pow(2)
        )

        val harmonicBonus = if (target.camelot != null && candidate.camelot != null) {
            val hDist = camelotDistance(target.camelot, candidate.camelot)
            when (hDist) {
                0 -> 0.15 // Same key
                1 -> 0.10 // Adjacent key (compatible)
                else -> 0.0
            }
        } else 0.0

        return (1.0 - distance) + harmonicBonus
    }

    private fun normalize(value: Double, min: Double, max: Double): Double {
        return ((value.coerceIn(min, max)) - min) / (max - min)
    }

    private fun camelotDistance(c1: String, c2: String): Int {
        val n1 = try { c1.dropLast(1).toInt() } catch (_: Exception) { 0 }
        val l1 = c1.last()
        val n2 = try { c2.dropLast(1).toInt() } catch (_: Exception) { 0 }
        val l2 = c2.last()

        val numDist = abs(n1 - n2).let { min(it, 12 - it) }
        val letterDist = if (l1 == l2) 0 else 1

        return numDist + letterDist
    }

    private fun mapToCamelot(key: String?, scale: String?): String? {
        if (key == null || scale == null) return null
        val isMinor = scale.lowercase() == "minor"
        return when (key.uppercase()) {
            "G#", "AB" -> if (isMinor) "1A" else "4B"
            "EB", "D#" -> if (isMinor) "2A" else "5B"
            "BB", "A#" -> if (isMinor) "3A" else "6B"
            "F" -> if (isMinor) "4A" else "7B"
            "C" -> if (isMinor) "5A" else "8B"
            "G" -> if (isMinor) "6A" else "9B"
            "D" -> if (isMinor) "7A" else "10B"
            "A" -> if (isMinor) "8A" else "11B"
            "E" -> if (isMinor) "9A" else "12B"
            "B" -> if (isMinor) "10A" else "1B"
            "F#", "GB" -> if (isMinor) "11A" else "2B"
            "C#", "DB" -> if (isMinor) "12A" else "3B"
            else -> null
        }
    }

    private fun mapToFeatureVector(data: SongAudioData): FeatureVector {
        return FeatureVector(
            bpm = data.bpm ?: 120.0,
            energy = data.energy ?: 0.5,
            valence = data.valence ?: 0.5,
            danceability = data.danceability ?: 0.5,
            loudness = data.loudness ?: -10.0,
            acousticness = data.acousticness ?: 0.5,
            instrumentalness = data.instrumentalness ?: 0.5,
            speechiness = data.speechiness ?: 0.5,
            camelot = mapToCamelot(data.key, data.scale)
        )
    }

    private fun mapAudioDataToFeatureVector(row: ResultRow): FeatureVector {
        return FeatureVector(
            bpm = row[SongAudioDataTable.bpm] ?: 120.0,
            energy = row[SongAudioDataTable.energy] ?: 0.5,
            valence = row[SongAudioDataTable.valence] ?: 0.5,
            danceability = row[SongAudioDataTable.danceability] ?: 0.5,
            loudness = row[SongAudioDataTable.loudness] ?: -10.0,
            acousticness = row[SongAudioDataTable.acousticness] ?: 0.5,
            instrumentalness = row[SongAudioDataTable.instrumentalness] ?: 0.5,
            speechiness = row[SongAudioDataTable.speechiness] ?: 0.5,
            camelot = mapToCamelot(row[SongAudioDataTable.key], row[SongAudioDataTable.scale])
        )
    }

    private data class FeatureVector(
        val bpm: Double,
        val energy: Double,
        val valence: Double,
        val danceability: Double,
        val loudness: Double,
        val acousticness: Double,
        val instrumentalness: Double,
        val speechiness: Double,
        val camelot: String?
    )
}
