package dev.dertyp.services

import dev.dertyp.PlatformUUID
import dev.dertyp.audio.LosslessFormat
import dev.dertyp.db.PcmInfoTable
import dev.dertyp.db.SongTable
import dev.dertyp.dbQuery
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class PcmAnalysisService : Service() {
    private val ffprobePath = findInPath("ffprobe")
    private val ffmpegPath = findInPath("ffmpeg")

    private val pcmFormats = listOf(LosslessFormat.WAV.extension, LosslessFormat.AIFF.extension)

    suspend fun getUnanalyzedSongIds(): List<PlatformUUID> = dbQuery {
        SongTable
            .leftJoin(PcmInfoTable)
            .select(SongTable.id)
            .where { (SongTable.format inList pcmFormats) and (PcmInfoTable.songId.isNull()) }
            .map { it[SongTable.id].value }
    }

    suspend fun analyze(songId: PlatformUUID, force: Boolean = false) {
        if (ffprobePath == null || ffmpegPath == null) {
            logger.error("ffprobe/ffmpeg not found in PATH. PCM analysis skipped.")
            return
        }

        val song = dbQuery {
            SongTable.select(SongTable.filePath, SongTable.fileSize)
                .where { SongTable.id eq songId }
                .singleOrNull()
        } ?: return

        val file = File(song[SongTable.filePath])
        val container = LosslessFormat.fromExtension(file.extension) ?: return
        if (container == LosslessFormat.FLAC || !file.exists()) return

        val existing = dbQuery {
            PcmInfoTable.select(PcmInfoTable.songId).where { PcmInfoTable.songId eq songId }.singleOrNull()
        }
        if (existing != null && !force) return

        val info = parsePcmInfo(file, container) ?: return

        dbQuery {
            PcmInfoTable.upsert(PcmInfoTable.songId) {
                it[PcmInfoTable.songId] = songId
                it[PcmInfoTable.container] = container.extension
                it[sampleRate] = info.sampleRate
                it[bitDepth] = info.bitDepth
                it[channels] = info.channels
                it[duration] = info.duration
                it[fileSize] = info.fileSize
                it[bitrateAvg] = info.bitrateAvg
                it[codec] = info.codec
                it[isFloat] = info.isFloat
                it[isBigEndian] = info.isBigEndian
                it[dataOffset] = info.dataOffset
                it[dataSize] = info.dataSize
                it[hasId3] = info.hasId3
                it[hasInfoChunk] = info.hasInfoChunk
                it[audioMd5] = info.audioMd5
                it[lastAnalyzed] = Instant.now().toEpochMilli()
            }
        }
    }

    internal suspend fun parsePcmInfo(file: File, container: LosslessFormat): PcmInfo? {
        val probe = ffprobePath ?: return null
        val result = executeCommand(
            command = listOf(
                probe, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_name,sample_rate,channels,bits_per_sample,bits_per_raw_sample,sample_fmt:format=duration",
                "-of", "default=noprint_wrappers=1", file.absolutePath
            ),
            aliveCheck = { true },
            logger = logger,
            logCommand = false,
        )
        if (result.exitCode != 0) return null

        val probeInfo = parseProbeOutput(result.fullOutput) ?: return null
        val layout = readChunkLayout(file, container)
        val md5 = computeAudioMd5(file)

        val fileSize = file.length()
        val duration = probeInfo.duration.takeIf { it > 0 }
            ?: if (probeInfo.sampleRate > 0 && layout.dataSize > 0 && probeInfo.bitDepth > 0 && probeInfo.channels > 0)
                layout.dataSize.toDouble() / (probeInfo.sampleRate * probeInfo.channels * (probeInfo.bitDepth / 8))
            else 0.0

        return PcmInfo(
            sampleRate = probeInfo.sampleRate,
            bitDepth = probeInfo.bitDepth,
            channels = probeInfo.channels,
            duration = duration,
            fileSize = fileSize,
            bitrateAvg = if (duration > 0) ((fileSize * 8) / duration).toInt() else 0,
            codec = probeInfo.codec,
            isFloat = probeInfo.codec.startsWith("pcm_f") || probeInfo.sampleFmt.startsWith("flt") || probeInfo.sampleFmt.startsWith("dbl"),
            isBigEndian = probeInfo.codec.endsWith("be"),
            dataOffset = layout.dataOffset,
            dataSize = layout.dataSize,
            hasId3 = layout.hasId3,
            hasInfoChunk = layout.hasInfoChunk,
            audioMd5 = md5,
        )
    }

    internal fun parseProbeOutput(output: String): ProbeInfo? {
        val values = output.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()

        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: return null
        if (sampleRate <= 0) return null
        val rawBits = values["bits_per_raw_sample"]?.toIntOrNull()?.takeIf { it > 0 }
        val bits = values["bits_per_sample"]?.toIntOrNull()?.takeIf { it > 0 }
        return ProbeInfo(
            codec = values["codec_name"].orEmpty(),
            sampleRate = sampleRate,
            channels = values["channels"]?.toIntOrNull() ?: 0,
            bitDepth = rawBits ?: bits ?: 0,
            sampleFmt = values["sample_fmt"].orEmpty(),
            duration = values["duration"]?.toDoubleOrNull() ?: 0.0,
        )
    }

    private suspend fun computeAudioMd5(file: File): String {
        val ffmpeg = ffmpegPath ?: return ""
        val result = executeCommand(
            command = listOf(ffmpeg, "-v", "error", "-i", file.absolutePath, "-map", "0:a:0", "-f", "md5", "-"),
            aliveCheck = { true },
            logger = logger,
            logCommand = false,
        )
        if (result.exitCode != 0) return ""
        return result.fullOutput.lines()
            .firstOrNull { it.startsWith("MD5=") }
            ?.substringAfter("MD5=")?.trim()?.take(32)
            .orEmpty()
    }

    internal fun readChunkLayout(file: File, container: LosslessFormat): ChunkLayout {
        val bigEndian = container == LosslessFormat.AIFF
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        var dataOffset = 0L
        var dataSize = 0L
        var hasId3 = false
        var hasInfo = false

        try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < 12) return ChunkLayout()
                val header = ByteArray(12)
                raf.readFully(header)
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                if (riff != "RIFF" && riff != "FORM" && riff != "RF64") return ChunkLayout()

                var pos = 12L
                val chunkHeader = ByteArray(8)
                while (pos + 8 <= length) {
                    raf.seek(pos)
                    raf.readFully(chunkHeader)
                    val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(order).int.toLong() and 0xFFFFFFFFL
                    val payload = pos + 8

                    when (id) {
                        "data" -> if (!bigEndian) {
                            dataOffset = payload
                            dataSize = if (size == 0xFFFFFFFFL) length - payload else minOf(size, length - payload)
                        }

                        "SSND" -> if (bigEndian && payload + 8 <= length) {
                            val ssnd = ByteArray(8)
                            raf.readFully(ssnd)
                            val offset = ByteBuffer.wrap(ssnd, 0, 4).order(order).int.toLong() and 0xFFFFFFFFL
                            dataOffset = payload + 8 + offset
                            dataSize = maxOf(0L, minOf(size - 8 - offset, length - dataOffset))
                        }

                        "LIST" -> if (!bigEndian && payload + 4 <= length) {
                            val type = ByteArray(4)
                            raf.readFully(type)
                            if (String(type, Charsets.US_ASCII) == "INFO") hasInfo = true
                        }

                        "id3 ", "ID3 " -> hasId3 = true
                    }

                    pos = payload + size + (size and 1L)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to read chunk layout of ${file.absolutePath}: ${e.message}")
        }

        return ChunkLayout(dataOffset, dataSize, hasId3, hasInfo)
    }

    internal data class ProbeInfo(
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
        val sampleFmt: String,
        val duration: Double,
    )

    internal data class ChunkLayout(
        val dataOffset: Long = 0,
        val dataSize: Long = 0,
        val hasId3: Boolean = false,
        val hasInfoChunk: Boolean = false,
    )

    internal data class PcmInfo(
        val sampleRate: Int,
        val bitDepth: Int,
        val channels: Int,
        val duration: Double,
        val fileSize: Long,
        val bitrateAvg: Int,
        val codec: String,
        val isFloat: Boolean,
        val isBigEndian: Boolean,
        val dataOffset: Long,
        val dataSize: Long,
        val hasId3: Boolean,
        val hasInfoChunk: Boolean,
        val audioMd5: String,
    )
}
