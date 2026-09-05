package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SongAudioTimelineTable : Table("song_audio_timeline") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val version = integer("version")
    val status = varchar("status", 16)
    val beatSource = varchar("source", 16)
    val analyzedAt = long("analyzedAt")
    val beats = binary("beats").nullable()
    val beatsCount = integer("beatsCount").nullable()
    val onsetRate = double("onsetRate").nullable()
    val beatsLoudnessMean = double("beatsLoudnessMean").nullable()
    val beatsLoudnessMax = double("beatsLoudnessMax").nullable()
    val envelope = binary("envelope").nullable()
    val envelopeHz = integer("envelopeHz").default(10)
    val envelopeMinDb = double("envelopeMinDb").default(-70.0)
    val envelopeMaxDb = double("envelopeMaxDb").default(0.0)
    val loudnessRange = double("loudnessRange").nullable()
    val dynamicComplexity = double("dynamicComplexity").nullable()

    override val primaryKey = PrimaryKey(songId)
}
