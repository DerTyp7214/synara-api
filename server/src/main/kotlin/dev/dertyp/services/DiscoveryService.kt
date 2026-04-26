package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.User
import dev.dertyp.data.UserSong
import dev.dertyp.db.SongAudioDataTable
import dev.dertyp.db.SongComposerTable
import dev.dertyp.db.SongLyricistTable
import dev.dertyp.db.SongProducerTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
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

    suspend fun getSimilarSongs(
        seedSongIds: List<PlatformUUID>,
        limit: Int,
        userId: PlatformUUID
    ): List<UserSong> {
        val seedData = seedSongIds.mapNotNull { audioAnalysisService.getAudioData(it) }
        if (seedData.isEmpty()) return emptyList()

        val targetProfile = FeatureVector(
            bpm = seedData.mapNotNull { it.bpm }.average().takeIf { !it.isNaN() } ?: 120.0,
            energy = seedData.mapNotNull { it.energy }.average().takeIf { !it.isNaN() } ?: 0.5,
            danceability = seedData.mapNotNull { it.danceability }.average().takeIf { !it.isNaN() } ?: 0.5,
            loudness = seedData.mapNotNull { it.loudness }.average().takeIf { !it.isNaN() } ?: -10.0,
            acousticness = seedData.mapNotNull { it.acousticness }.average().takeIf { !it.isNaN() } ?: 0.5,
            instrumentalness = seedData.mapNotNull { it.instrumentalness }.average().takeIf { !it.isNaN() } ?: 0.5,
            speechiness = seedData.mapNotNull { it.speechiness }.average().takeIf { !it.isNaN() } ?: 0.5,
            camelot = seedData.mapNotNull { mapToCamelot(it.key, it.scale) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        )

        val candidates = dbQuery {
            SongAudioDataTable.selectAll()
                .where { SongAudioDataTable.songId notInList seedSongIds }
                .map { mapRowToFeatureVector(it) }
        }

        val similarIdsWithScore = candidates
            .map { it.first to calculateSimilarity(targetProfile, it.second) }
            .sortedByDescending { it.second }
            .take(limit)

        val similarSongs = songService.byIds(similarIdsWithScore.map { it.first }, userId)

        return similarIdsWithScore.mapNotNull { (id, _) -> similarSongs.find { it.id == id } }
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

        val personIds = creditTable.select(personIdCol)
            .where { songIdCol inList seedSongIds }
            .map { it[personIdCol] }
            .distinct()

        if (personIds.isEmpty()) return@dbQuery emptyList()

        val songIds = creditTable.select(songIdCol)
            .where { (personIdCol inList personIds) and (songIdCol notInList seedSongIds) }
            .map { it[songIdCol].value }
            .distinct()
            .take(limit)

        songService.byIds(songIds, userId)
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

    private fun mapRowToFeatureVector(row: ResultRow): Pair<PlatformUUID, FeatureVector> {
        return row[SongAudioDataTable.songId].value to FeatureVector(
            bpm = row[SongAudioDataTable.bpm] ?: 120.0,
            energy = row[SongAudioDataTable.energy] ?: 0.5,
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
        val danceability: Double,
        val loudness: Double,
        val acousticness: Double,
        val instrumentalness: Double,
        val speechiness: Double,
        val camelot: String?
    )
}
