package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesResourceObject(
    val id: String,
    val type: String,
    val attributes: SharesAttributes? = null,
    val relationships: SharesRelationships? = null
)