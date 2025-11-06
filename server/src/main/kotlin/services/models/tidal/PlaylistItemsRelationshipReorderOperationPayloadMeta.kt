package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlaylistItemsRelationshipReorderOperationPayloadMeta(
    val positionBefore: String
)