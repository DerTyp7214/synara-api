package dev.dertyp.services.models.tidal


import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class AppreciationsAttributes(
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime
): BaseAttributes()