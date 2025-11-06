package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class UserReportCreateOperationPayloadDataAttributes(
    val description: String,
    val reason: Reason
): BaseAttributes() {
    @Suppress("unused")
    enum class Reason {
        SEXUAL_CONTENT_OR_NUDITY,
        VIOLENT_OR_DANGEROUS_CONTENT,
        HATEFUL_OR_ABUSIVE_CONTENT,
        HARASSMENT,
        PRIVACY_VIOLATION,
        SCAMS_OR_FRAUD,
        SPAM,
        COPYRIGHT_INFRINGEMENT,
        UNKNOWN;
    }
}

