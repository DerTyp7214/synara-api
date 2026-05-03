package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object FlacInfoTable : Table("flac_info") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val sampleRate = integer("sampleRate")
    val bitDepth = integer("bitDepth")
    val channels = integer("channels")
    val duration = double("duration")
    val fileSize = long("fileSize")
    val bitrateAvg = integer("bitrateAvg")
    val seekpointCount = integer("seekpointCount")
    val seekIntervalMax = double("seekIntervalMax")
    val paddingBytes = integer("paddingBytes")
    val audioMd5 = varchar("audioMd5", 32)
    val lastAnalyzed = long("lastAnalyzed").clientDefault { Instant.now().toEpochMilli() }

    override val primaryKey = PrimaryKey(songId)
}
