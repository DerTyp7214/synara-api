package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class PlayQueueAddFutureOperationPayloadMeta(
    val mode: Mode,
    @Serializable(with = UUIDSerializer::class)
    val batchId: UUID? = null
) {
    enum class Mode {
        ADD_TO_FRONT,
        ADD_TO_BACK,
        REPLACE_ALL;
    }
}

