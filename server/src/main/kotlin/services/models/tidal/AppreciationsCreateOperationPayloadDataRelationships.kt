package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class AppreciationsCreateOperationPayloadDataRelationships(
    val appreciatedItems: AppreciationsCreateOperationPayloadDataRelationshipsAppreciatedItem
): BaseRelationships()