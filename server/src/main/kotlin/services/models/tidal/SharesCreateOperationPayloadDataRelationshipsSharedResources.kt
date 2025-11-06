package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class SharesCreateOperationPayloadDataRelationshipsSharedResources(
    val data: List<SharesCreateOperationPayloadDataRelationshipsSharedResourcesData>
)