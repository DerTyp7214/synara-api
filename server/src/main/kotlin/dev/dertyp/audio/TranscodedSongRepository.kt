package dev.dertyp.audio

import dev.dertyp.core.date.getDateFromISO
import dev.dertyp.core.db.dbQuery
import dev.dertyp.data.AudioFormat
import dev.dertyp.data.AudioInfo
import dev.dertyp.data.SimpleSong
import dev.dertyp.data.TranscodedVersion
import dev.dertyp.db.SongTable
import dev.dertyp.db.TranscodedSongTable
import dev.dertyp.db.fullSongTitle
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import java.io.File
import java.util.UUID

class TranscodedSongRepository {
    suspend fun getSongsWithTranscodingInfo(exclude: List<TranscodedVersion> = emptyList()) = dbQuery {
        val excludedSongIds = TranscodedSongTable
            .select(TranscodedSongTable.songId)
            .where {
                if (exclude.isEmpty()) Op.FALSE
                else exclude.map { (bitrate, format) ->
                    (TranscodedSongTable.bitrate eq bitrate) and (TranscodedSongTable.format eq format)
                }.reduce { acc, op -> acc or op }
            }
            .map { it[TranscodedSongTable.songId].value }
            .distinct()

        SongTable
            .leftJoin(TranscodedSongTable)
            .select(SongTable.columns + TranscodedSongTable.columns)
            .where { SongTable.id notInList excludedSongIds }
            .map {
                SimpleSong(
                    id = it[SongTable.id].value,
                    title = it.fullSongTitle(),
                    duration = it[SongTable.duration],
                    explicit = it[SongTable.explicit],
                    releaseDate = getDateFromISO(it[SongTable.releaseDate]),
                    path = it[SongTable.filePath],
                    originalUrl = it[SongTable.originalUrl],
                    trackNumber = it[SongTable.trackNumber],
                    discNumber = it[SongTable.discNumber],
                    audio = AudioInfo(
                        codec = it[SongTable.format],
                        sampleRate = it[SongTable.sampleRate],
                        bitsPerSample = it[SongTable.bitsPerSample],
                        bitRate = it[SongTable.bitRate],
                        fileSize = it[SongTable.fileSize],
                        channels = it[SongTable.channels],
                    ),
                    coverId = it[SongTable.cover]?.value,
                    transcodedTo = listOfNotNull(
                        if (it.getOrNull(TranscodedSongTable.bitrate) != null) {
                            TranscodedVersion(
                                it[TranscodedSongTable.bitrate],
                                it[TranscodedSongTable.format]
                            )
                        } else null
                    ),
                )
            }
            .groupBy { it.id }
            .map { (_, songs) ->
                songs.first().copy(
                    transcodedTo = songs.flatMap { it.transcodedTo }.distinct(),
                )
            }
    }

    suspend fun insertTranscodedSong(songs: List<Triple<SimpleSong, File, TranscodedVersion>>) = dbQuery {
        songs.forEach { (song, file, version) ->
            TranscodedSongTable.insertIgnore {
                it[TranscodedSongTable.songId] = song.id
                it[TranscodedSongTable.bitrate] = version.bitrate
                it[TranscodedSongTable.format] = version.format
                it[TranscodedSongTable.path] = file.absolutePath
                it[TranscodedSongTable.fileSize] = file.length()
            }
        }
    }

    suspend fun insertTranscodedSong(songId: UUID, file: File, bitrate: Int, format: AudioFormat = AudioFormat.OPUS) = dbQuery {
        TranscodedSongTable.insertIgnore {
            it[TranscodedSongTable.songId] = songId
            it[TranscodedSongTable.bitrate] = bitrate
            it[TranscodedSongTable.format] = format
            it[TranscodedSongTable.path] = file.absolutePath
            it[TranscodedSongTable.fileSize] = file.length()
        }
    }
}
