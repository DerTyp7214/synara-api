package dev.dertyp.data

import kotlinx.serialization.Serializable

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val isGroup: Boolean,
    val  artists: List<Artist> = listOf(),
    val imageUrl: String,
    val about: String = "",
)
