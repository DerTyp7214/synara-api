package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserReportsCreateOperationPayloadDataRelationships(
    val reportedResources: UserReportsCreateOperationPayloadDataRelationshipsReportedResources
): BaseRelationships()