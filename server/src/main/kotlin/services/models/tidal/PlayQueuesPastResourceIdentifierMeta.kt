package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class PlayQueuesPastResourceIdentifierMeta(
    @Serializable(with = UUIDSerializer::class)
    val batchId: UUID,
    val itemId: String
)