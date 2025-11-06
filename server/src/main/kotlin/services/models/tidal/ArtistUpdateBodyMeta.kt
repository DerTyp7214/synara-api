package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class ArtistUpdateBodyMeta(
    val dryRun: Boolean? = null
)