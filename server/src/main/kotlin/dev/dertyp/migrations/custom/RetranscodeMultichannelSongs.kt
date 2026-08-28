package dev.dertyp.migrations.custom

import dev.dertyp.AudioUtils
import dev.dertyp.core.CustomMigration
import dev.dertyp.core.Migration
import dev.dertyp.core.logTask
import dev.dertyp.db.FlacInfoTable
import dev.dertyp.db.PcmInfoTable
import dev.dertyp.db.SongTable
import dev.dertyp.db.TranscodedSongTable
import dev.dertyp.dbQuery
import io.ktor.server.application.ApplicationEnvironment
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.orWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.inject
import java.io.File

@Migration("3.11")
class RetranscodeMultichannelSongs : CustomMigration() {
    private val environment by inject<ApplicationEnvironment>()

    override suspend fun migrate() {
        logTask("Retranscode multichannel songs") {
            val rows = dbQuery {
                TranscodedSongTable
                    .innerJoin(SongTable)
                    .leftJoin(FlacInfoTable)
                    .leftJoin(PcmInfoTable)
                    .select(
                        TranscodedSongTable.songId,
                        TranscodedSongTable.bitrate,
                        TranscodedSongTable.format,
                        TranscodedSongTable.path,
                        SongTable.filePath,
                        FlacInfoTable.channels,
                        PcmInfoTable.channels,
                    )
                    .where { FlacInfoTable.channels greater 2 }
                    .orWhere { PcmInfoTable.channels greater 2 }
                    .orWhere { FlacInfoTable.channels.isNull() and PcmInfoTable.channels.isNull() }
                    .toList()
            }

            var retranscoded = 0
            var failed = 0
            val channelCache = mutableMapOf<String, Int>()

            rows.forEachIndexed { index, row ->
                val source = File(row[SongTable.filePath])
                val channels = row.getOrNull(FlacInfoTable.channels)
                    ?: row.getOrNull(PcmInfoTable.channels)
                    ?: channelCache.getOrPut(source.absolutePath) { probeChannels(source) }

                if (channels > 2 && source.exists()) {
                    val bitrate = row[TranscodedSongTable.bitrate]
                    val format = row[TranscodedSongTable.format]
                    try {
                        File(row[TranscodedSongTable.path]).delete()
                        val info = AudioUtils.transcodeAudio(environment, source, bitrate, force = true, audioFormat = format)
                        dbQuery {
                            TranscodedSongTable.update({
                                (TranscodedSongTable.songId eq row[TranscodedSongTable.songId]) and
                                        (TranscodedSongTable.bitrate eq bitrate) and
                                        (TranscodedSongTable.format eq format)
                            }) {
                                it[path] = info.file.absolutePath
                                it[fileSize] = info.file.length()
                            }
                        }
                        retranscoded++
                    } catch (e: Exception) {
                        failed++
                        logger.error("Failed to retranscode ${source.absolutePath} @ $bitrate $format", e)
                    }
                }

                updateProgress((index + 1).toDouble() / rows.size, "Checked ${index + 1}/${rows.size}, retranscoded $retranscoded, failed $failed")
            }

            mapOf("checked" to rows.size, "retranscoded" to retranscoded, "failed" to failed)
        }
    }

    private fun probeChannels(file: File): Int {
        if (!file.exists()) return 0
        return runCatching {
            FFmpegFrameGrabber(file.absolutePath).use { grabber ->
                grabber.start()
                grabber.audioChannels
            }
        }.getOrDefault(0)
    }
}
