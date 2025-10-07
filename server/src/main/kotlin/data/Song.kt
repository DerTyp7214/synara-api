package dev.dertyp.data

import kotlinx.serialization.Serializable

@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val album: Album,
    val coverUrl: String,
    val duration: Long,
    val releaseDate: Long,
    val lyrics: String = "",
    val path: String,
)
