package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtworkFileMeta(
    val height: Int,
    val width: Int
)