package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class FileStatus(
    val moderationFileStatus: ModerationFileStatus,
    val technicalFileStatus: TechnicalFileStatus
) {
    @Suppress("unused")
    enum class ModerationFileStatus {
        NOT_MODERATED,
        SCANNING,
        FLAGGED,
        TAKEN_DOWN,
        OK,
        ERROR;
    }

    @Suppress("unused")
    enum class TechnicalFileStatus {
        UPLOAD_REQUESTED,
        PROCESSING,
        OK,
        ERROR;
    }
}

