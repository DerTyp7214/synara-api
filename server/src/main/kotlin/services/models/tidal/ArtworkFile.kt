package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class ArtworkFile(
    val href: String,
    val meta: ArtworkFileMeta
)