package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object PcmInfoTable : Table("pcm_info") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val container = varchar("container", 8)
    val sampleRate = integer("sampleRate")
    val bitDepth = integer("bitDepth")
    val channels = integer("channels")
    val duration = double("duration")
    val fileSize = long("fileSize")
    val bitrateAvg = integer("bitrateAvg")
    val codec = varchar("codec", 32)
    val isFloat = bool("isFloat")
    val isBigEndian = bool("isBigEndian")
    val dataOffset = long("dataOffset")
    val dataSize = long("dataSize")
    val hasId3 = bool("hasId3")
    val hasInfoChunk = bool("hasInfoChunk")
    val audioMd5 = varchar("audioMd5", 32)
    val lastAnalyzed = long("lastAnalyzed").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(songId)
}
