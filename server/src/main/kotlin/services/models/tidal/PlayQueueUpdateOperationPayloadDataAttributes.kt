package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class PlayQueueUpdateOperationPayloadDataAttributes(
    val repeat: Repeat? = null,
    val shuffled: Boolean? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class Repeat {
        NONE,
        ONE,
        BATCH;
    }
}

