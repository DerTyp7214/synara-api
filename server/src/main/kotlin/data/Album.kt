package dev.dertyp.data

import kotlinx.serialization.Serializable

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artists: List<Artist>,
    val coverUrl: String,
    val songCount: Int = 0,
    val releaseDate: Long,
    val totalDuration: Long,
)
