package dev.dertyp.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object SongAudioDataTable : Table("song_audio_data") {
    val songId = reference("songId", SongTable.id, onDelete = ReferenceOption.CASCADE)
    val bpm = double("bpm").nullable()
    val key = varchar("key", 16).nullable()
    val scale = varchar("scale", 16).nullable()
    val loudness = double("loudness").nullable()
    val energy = double("energy").nullable()
    val valence = double("valence").nullable()
    val danceability = double("danceability").nullable()
    val acousticness = double("acousticness").nullable()
    val instrumentalness = double("instrumentalness").nullable()
    val speechiness = double("speechiness").nullable()

    override val primaryKey = PrimaryKey(songId)
}
