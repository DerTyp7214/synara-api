package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.data.*
import dev.dertyp.db.*
import dev.dertyp.dbQuery
import dev.dertyp.utils.ColorUtils
import dev.dertyp.utils.ImageUtils
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
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

    override suspend fun createSongMosaic(
        image: ByteArray,
        width: Int,
        height: Int,
        page: Int,
        pageSize: Int,
        range: Int
    ): PaginatedResponse<UserSong> {
        return discoveryService.createSongMosaic(image, width, height, page, pageSize, range, user.id)
    }

    override suspend fun createAlbumMosaic(
        image: ByteArray,
        width: Int,
        height: Int,
        page: Int,
        pageSize: Int,
        range: Int
    ): PaginatedResponse<Album> {
        return discoveryService.createAlbumMosaic(image, width, height, page, pageSize, range, user.id)
    }
}

class DiscoveryService : Service() {
    private val songService: SongService by inject()
    private val albumService: AlbumService by inject()
    private val audioAnalysisService: AudioAnalysisService by inject()
    private val recommendationServingService: RecommendationServingService by inject()

    suspend fun getSongsBySameComposers(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSongsBySameCredits(seedSongIds, SongComposerTable, limit, userId)
    }

    suspend fun getSongsBySameLyricists(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSongsBySameCredits(seedSongIds, SongLyricistTable, limit, userId)
    }

    suspend fun getSongsBySameProducers(seedSongIds: List<PlatformUUID>, limit: Int = 20, userId: PlatformUUID): List<UserSong> {
        return getSongsBySameCredits(seedSongIds, SongProducerTable, limit, userId)
    }

    suspend fun createSongMosaic(
        image: ByteArray,
        width: Int,
        height: Int,
        page: Int,
        pageSize: Int,
        range: Int,
        userId: PlatformUUID
    ): PaginatedResponse<UserSong> {
        val allPixels = ImageUtils.extractColors(image, width, height)
        val total = width * height
        val startIndex = page * pageSize
        if (startIndex >= total) return PaginatedResponse(emptyList(), total, page, pageSize)
        val endIndex = min(startIndex + pageSize, total)

        val pagePixels = allPixels.subList(startIndex, endIndex)
        val globalRanks = (startIndex until endIndex).map { i -> calculateGlobalRank(allPixels, i) }

        val idsByPixelAndRank = mutableMapOf<ImageUtils.Pixel, Map<Int, UUID>>()

        pagePixels.distinct().forEach { pixel ->
            val pixelIndicesInPage = pagePixels.indices.filter { pagePixels[it] == pixel }
            val ranksNeeded = pixelIndicesInPage.map { globalRanks[it] }
            val offsetVal = ranksNeeded.minOrNull() ?: 0
            val limitVal = (ranksNeeded.maxOrNull() ?: 0) - offsetVal + 1

            val (l, a, b) = ColorUtils.rgbToLab(pixel.r, pixel.g, pixel.b)

            val ids = dbQuery {
                SongTable
                    .innerJoin(ImageMetadataTable, onColumn = { cover }, otherColumn = { ImageMetadataTable.imageId })
                    .select(SongTable.id)
                    .filterByColor(l, a, b, range)
                    .orderByColorDistance(l, a, b)
                    .limit(limitVal)
                    .offset(offsetVal.toLong())
                    .map { it[SongTable.id].value }
            }

            @Suppress("UNCHECKED_CAST")
            idsByPixelAndRank[pixel] = ranksNeeded.associateWith { rank ->
                val idIndex = rank - offsetVal
                ids.getOrNull(idIndex)
            }.filterValues { it != null } as Map<Int, UUID>
        }

        val allFetchedIds = pagePixels.indices.mapNotNull { i ->
            idsByPixelAndRank[pagePixels[i]]?.get(globalRanks[i])
        }

        val songs = songService.byIds(allFetchedIds, userId).associateBy { it.id }
        val data = allFetchedIds.mapNotNull { songs[it] }

        return PaginatedResponse(data, total, page, pageSize, hasNextPage = endIndex < total)
    }

    suspend fun createAlbumMosaic(
        image: ByteArray,
        width: Int,
        height: Int,
        page: Int,
        pageSize: Int,
        range: Int,
        userId: PlatformUUID
    ): PaginatedResponse<Album> {
        val allPixels = ImageUtils.extractColors(image, width, height)
        val total = width * height
        val startIndex = page * pageSize
        if (startIndex >= total) return PaginatedResponse(emptyList(), total, page, pageSize)
        val endIndex = min(startIndex + pageSize, total)

        val pagePixels = allPixels.subList(startIndex, endIndex)
        val globalRanks = (startIndex until endIndex).map { i -> calculateGlobalRank(allPixels, i) }

        val idsByColorAndRank = mutableMapOf<ImageUtils.Pixel, Map<Int, UUID>>()

        pagePixels.distinct().forEach { pixel ->
            val pixelIndicesInPage = pagePixels.indices.filter { pagePixels[it] == pixel }
            val ranksNeeded = pixelIndicesInPage.map { globalRanks[it] }
            val offsetVal = ranksNeeded.minOrNull() ?: 0
            val limitVal = (ranksNeeded.maxOrNull() ?: 0) - offsetVal + 1

            val (l, a, b) = ColorUtils.rgbToLab(pixel.r, pixel.g, pixel.b)

            val ids = dbQuery {
                AlbumTable
                    .innerJoin(ImageMetadataTable, onColumn = { cover }, otherColumn = { ImageMetadataTable.imageId })
                    .select(AlbumTable.id)
                    .filterByColor(l, a, b, range)
                    .orderByColorDistance(l, a, b)
                    .limit(limitVal)
                    .offset(offsetVal.toLong())
                    .map { it[AlbumTable.id].value }
            }

            @Suppress("UNCHECKED_CAST")
            idsByColorAndRank[pixel] = ranksNeeded.associateWith { rank ->
                val idIndex = rank - offsetVal
                ids.getOrNull(idIndex)
            }.filterValues { it != null } as Map<Int, UUID>
        }

        val allFetchedIds = pagePixels.indices.mapNotNull { i ->
            idsByColorAndRank[pagePixels[i]]?.get(globalRanks[i])
        }

        val albums = albumService.byIds(allFetchedIds, userId).associateBy { it.id }
        val data = allFetchedIds.mapNotNull { albums[it] }

        return PaginatedResponse(data, total, page, pageSize, hasNextPage = endIndex < total)
    }

    private fun calculateGlobalRank(allPixels: List<ImageUtils.Pixel>, targetIndex: Int): Int {
        val pixel = allPixels[targetIndex]
        return allPixels.take(targetIndex).count { it == pixel }
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
            val distance = weightedDistance {
                add(target.energy, candidate.energy, weight = 1.0)
                add(target.valence, candidate.valence, weight = 1.0)
            }
            1.0 - (distance / sqrt(2.0))
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
        val embeddingBased = recommendationServingService.similarSongs(seedSongIds, userId, limit)
        if (embeddingBased.isNotEmpty()) return embeddingBased

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
        val distance = weightedDistance {
            add(target.bpm, candidate.bpm, weight = 0.15, min = 50.0, max = 200.0)
            add(target.energy, candidate.energy, weight = 0.25)
            add(target.valence, candidate.valence, weight = 0.25)
            add(target.danceability, candidate.danceability, weight = 0.2)
            add(target.loudness, candidate.loudness, weight = 0.05, min = -60.0, max = 0.0)
            add(target.acousticness, candidate.acousticness, weight = 0.05)
            add(target.instrumentalness, candidate.instrumentalness, weight = 0.05)
            add(target.speechiness, candidate.speechiness, weight = 0.05)
        }

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

    private fun mapToCamelot(key: String?, scale: AudioScale?): String? {
        if (key == null || scale == null) return null
        val isMinor = scale == AudioScale.Minor
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
            bpm = data.bpm ?: SongAudioData.DEFAULT_BPM,
            energy = data.energy ?: SongAudioData.DEFAULT_ENERGY,
            valence = data.valence ?: SongAudioData.DEFAULT_VALENCE,
            danceability = data.danceability ?: SongAudioData.DEFAULT_DANCEABILITY,
            loudness = data.loudness ?: SongAudioData.DEFAULT_LOUDNESS,
            acousticness = data.acousticness ?: SongAudioData.DEFAULT_ACOUSTICNESS,
            instrumentalness = data.instrumentalness ?: SongAudioData.DEFAULT_INSTRUMENTALNESS,
            speechiness = data.speechiness ?: SongAudioData.DEFAULT_SPEECHINESS,
            camelot = mapToCamelot(data.key, data.scale)
        )
    }

    private fun mapAudioDataToFeatureVector(row: ResultRow): FeatureVector {
        return FeatureVector(
            bpm = row[SongAudioDataTable.bpm] ?: SongAudioData.DEFAULT_BPM,
            energy = row[SongAudioDataTable.energy] ?: SongAudioData.DEFAULT_ENERGY,
            valence = row[SongAudioDataTable.valence] ?: SongAudioData.DEFAULT_VALENCE,
            danceability = row[SongAudioDataTable.danceability] ?: SongAudioData.DEFAULT_DANCEABILITY,
            loudness = row[SongAudioDataTable.loudness] ?: SongAudioData.DEFAULT_LOUDNESS,
            acousticness = row[SongAudioDataTable.acousticness] ?: SongAudioData.DEFAULT_ACOUSTICNESS,
            instrumentalness = row[SongAudioDataTable.instrumentalness] ?: SongAudioData.DEFAULT_INSTRUMENTALNESS,
            speechiness = row[SongAudioDataTable.speechiness] ?: SongAudioData.DEFAULT_SPEECHINESS,
            camelot = mapToCamelot(row[SongAudioDataTable.key], AudioScale.fromString(row[SongAudioDataTable.scale]))
        )
    }

    private inline fun weightedDistance(block: SimilarityBuilder.() -> Unit): Double {
        val builder = SimilarityBuilder()
        builder.block()
        return builder.build()
    }

    private inner class SimilarityBuilder {
        private var sumOfSquares = 0.0

        fun add(target: Double, candidate: Double, weight: Double, min: Double? = null, max: Double? = null) {
            val t = if (min != null && max != null) normalize(target, min, max) else target
            val c = if (min != null && max != null) normalize(candidate, min, max) else candidate
            sumOfSquares += ((t - c) * weight).pow(2)
        }

        fun build(): Double = sqrt(sumOfSquares)
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
