package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class AlbumsItemsResourceIdentifierMeta(
    val trackNumber: Int,
    val volumeNumber: Int
)