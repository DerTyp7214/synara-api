package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.db.FlacInfoTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

class FlacAnalysisService : Service() {
    private val metaflacPath = findInPath("metaflac")

    suspend fun getUnanalyzedSongIds(): List<PlatformUUID> = dbQuery {
        SongTable
            .leftJoin(FlacInfoTable)
            .select(SongTable.id)
            .where { 
                (SongTable.filePath.lowerCase().like("%.flac")) and (FlacInfoTable.songId.isNull())
            }
            .map { it[SongTable.id].value }
    }

    suspend fun getIdsNeedingFix(maxInterval: Double): List<PlatformUUID> = dbQuery {
        FlacInfoTable.select(FlacInfoTable.songId)
            .where { FlacInfoTable.seekIntervalMax greater maxInterval }
            .map { it[FlacInfoTable.songId].value }
    }

    suspend fun analyze(songId: PlatformUUID, force: Boolean = false) {
        if (metaflacPath == null) {
            logger.error("metaflac not found in PATH. FLAC analysis skipped.")
            return
        }

        val song = dbQuery {
            SongTable.select(SongTable.filePath, SongTable.fileSize)
                .where { SongTable.id eq songId }
                .singleOrNull()
        } ?: return

        val filePath = song[SongTable.filePath]
        val fileSize = song[SongTable.fileSize]

        if (!filePath.lowercase().endsWith(".flac")) return

        val existing = dbQuery {
            FlacInfoTable.select(FlacInfoTable.songId)
                .where { FlacInfoTable.songId eq songId }
                .singleOrNull()
        }

        if ((existing != null) && !force) return

        val info = parseFlacInfo(filePath, fileSize) ?: return

        dbQuery {
            FlacInfoTable.upsert(FlacInfoTable.songId) {
                it[FlacInfoTable.songId] = songId
                it[sampleRate] = info.sampleRate
                it[bitDepth] = info.bitDepth
                it[channels] = info.channels
                it[duration] = info.duration
                it[FlacInfoTable.fileSize] = info.fileSize
                it[bitrateAvg] = info.bitrateAvg
                it[seekpointCount] = info.seekpointCount
                it[seekIntervalMax] = info.seekIntervalMax
                it[paddingBytes] = info.paddingBytes
                it[audioMd5] = info.audioMd5
                it[lastAnalyzed] = Instant.now().toEpochMilli()
            }
        }
    }

    suspend fun fixSeekpoints(songId: PlatformUUID, interval: String = "2s") {
        if (metaflacPath == null) return

        val filePath = dbQuery {
            SongTable.select(SongTable.filePath)
                .where { SongTable.id eq songId }
                .singleOrNull()?.get(SongTable.filePath)
        } ?: return

        executeCommand(
            command = listOf(metaflacPath, "--add-seekpoint=$interval", "--add-padding=8192", filePath),
            aliveCheck = { true },
            logger = logger,
            logCommand = false,
        )

        analyze(songId, force = true)
    }

    private suspend fun parseFlacInfo(filePath: String, fileSize: Long): FlacInfo? {
        val path = metaflacPath ?: return null
        val result = executeCommand(
            command = listOf(path, "--list", filePath),
            aliveCheck = { true },
            logger = logger,
            logCommand = false,
        )

        if (result.exitCode != 0) return null

        val output = result.fullOutput
        
        var sampleRate = 0
        var channels = 0
        var bitDepth = 0
        var totalSamples = 0L
        var md5 = ""
        var padding = 0
        val seekPoints = mutableListOf<Long>()

        var currentBlockType = ""

        output.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("type: ")) {
                currentBlockType = when {
                    trimmed.contains("STREAMINFO") -> "STREAMINFO"
                    trimmed.contains("SEEKTABLE") -> "SEEKTABLE"
                    trimmed.contains("PADDING") -> "PADDING"
                    else -> ""
                }
            }

            when (currentBlockType) {
                "STREAMINFO" -> {
                    if (trimmed.startsWith("sample_rate: ")) sampleRate = trimmed.substringAfter(": ").substringBefore(" ").toInt()
                    if (trimmed.startsWith("channels: ")) channels = trimmed.substringAfter(": ").toInt()
                    if (trimmed.startsWith("bits-per-sample: ")) bitDepth = trimmed.substringAfter(": ").toInt()
                    if (trimmed.startsWith("total samples: ")) totalSamples = trimmed.substringAfter(": ").toLong()
                    if (trimmed.startsWith("MD5 signature: ")) md5 = trimmed.substringAfter(": ")
                }
                "PADDING" -> {
                    if (trimmed.startsWith("length: ")) padding += trimmed.substringAfter(": ").toInt()
                }
                "SEEKTABLE" -> {
                    if (trimmed.startsWith("point ") && trimmed.contains("sample_number=")) {
                        val sampleNum = trimmed.substringAfter("sample_number=").substringBefore(",").toLong()
                        if (sampleNum >= 0) {
                            seekPoints.add(sampleNum)
                        }
                    }
                }
            }
        }

        if (sampleRate == 0) return null

        val duration = totalSamples.toDouble() / sampleRate
        val bitrateAvg = if (duration > 0) ((fileSize * 8) / duration).toInt() else 0

        var maxGap = 0.0
        if (seekPoints.isNotEmpty()) {
            seekPoints.sort()
            var prev = 0L
            for (p in seekPoints) {
                val gap = (p - prev).toDouble() / sampleRate
                if (gap > maxGap) maxGap = gap
                prev = p
            }
            val endGap = (totalSamples - prev).toDouble() / sampleRate
            if (endGap > maxGap) maxGap = endGap
        } else {
            maxGap = duration
        }

        return FlacInfo(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            duration = duration,
            fileSize = fileSize,
            bitrateAvg = bitrateAvg,
            seekpointCount = seekPoints.size,
            seekIntervalMax = maxGap,
            paddingBytes = padding,
            audioMd5 = md5
        )
    }

    private data class FlacInfo(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val duration: Double,
        val fileSize: Long,
        val bitrateAvg: Int,
        val seekpointCount: Int,
        val seekIntervalMax: Double,
        val paddingBytes: Int,
        val audioMd5: String
    )
}
