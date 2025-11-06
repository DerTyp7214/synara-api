package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserReportsCreateOperationPayloadDataRelationshipsReportedResources(
    val data: List<UserReportsCreateOperationPayloadDataRelationshipsReportedResourcesData>
)