package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesRelationships(
    val owners: MultiRelationshipDataDocument,
    val sharedResources: MultiRelationshipDataDocument
): BaseRelationships()