package dev.dertyp.services.sync

import dev.dertyp.PlatformUUID
import dev.dertyp.db.MBRecordingIsrcTable
import dev.dertyp.db.SongMusicBrainzTable
import dev.dertyp.db.SongTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select

private const val QUERY_CHUNK = 1000

data class RecordingMatch(
    val mbidToIsrcs: Map<PlatformUUID, List<String>>,
    val isrcToSong: Map<String, PlatformUUID>,
    val mbidToSong: Map<PlatformUUID, PlatformUUID>,
) {
    fun songForRecording(mbid: PlatformUUID): PlatformUUID? =
        mbidToSong[mbid] ?: mbidToIsrcs[mbid]?.firstNotNullOfOrNull { isrcToSong[it] }
}

fun resolveRecordingMatches(mbids: List<PlatformUUID>, directIsrcs: List<String>): RecordingMatch {
    val mbidToIsrcs = mbids.chunked(QUERY_CHUNK).flatMap { chunk ->
        MBRecordingIsrcTable
            .select(MBRecordingIsrcTable.recordingId, MBRecordingIsrcTable.isrc)
            .where { MBRecordingIsrcTable.recordingId inList chunk }
            .map { it[MBRecordingIsrcTable.recordingId].value to it[MBRecordingIsrcTable.isrc].uppercase() }
    }.groupBy({ it.first }, { it.second })

    val candidateIsrcs = (directIsrcs + mbidToIsrcs.values.flatten()).distinct()

    val isrcToSong = candidateIsrcs.chunked(QUERY_CHUNK).flatMap { chunk ->
        SongTable
            .select(SongTable.id, SongTable.isrc)
            .where { SongTable.isrc inList chunk }
            .mapNotNull { row -> row[SongTable.isrc]?.uppercase()?.let { it to row[SongTable.id].value } }
    }.toMap()

    val mbidToSong = mbids.chunked(QUERY_CHUNK).flatMap { chunk ->
        SongMusicBrainzTable
            .select(SongMusicBrainzTable.songId, SongMusicBrainzTable.musicBrainzId)
            .where { SongMusicBrainzTable.musicBrainzId inList chunk }
            .map { it[SongMusicBrainzTable.musicBrainzId]!!.value to it[SongMusicBrainzTable.songId].value }
    }.toMap()

    return RecordingMatch(mbidToIsrcs, isrcToSong, mbidToSong)
}
