package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class PlayQueuesAttributes(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val lastModifiedAt: OffsetDateTime,
    val repeat: Repeat,
    val shuffled: Boolean
): BaseAttributes() {
    @Suppress("unused")
    enum class Repeat {
        NONE,
        ONE,
        BATCH;
    }
}

