package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class UserReportCreateOperationPayloadData(
    val attributes: UserReportCreateOperationPayloadDataAttributes,
    val relationships: UserReportsCreateOperationPayloadDataRelationships,
    val type: Type
) {
    @Suppress("EnumEntryName")
    enum class Type {
        userReports;
    }
}

