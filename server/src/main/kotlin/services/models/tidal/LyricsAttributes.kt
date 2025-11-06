package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class LyricsAttributes(
    val technicalStatus: TechnicalStatus,
    val direction: Direction? = null,
    val lrcText: String? = null,
    val provider: LyricsAttributesProvider? = null,
    val text: String? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class TechnicalStatus {
        PENDING,
        PROCESSING,
        ERROR,
        OK;
    }

    @Suppress("unused")
    enum class Direction {
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT;
    }
}

