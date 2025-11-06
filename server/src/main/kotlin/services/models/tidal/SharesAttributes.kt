package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class SharesAttributes(
    val code: String,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime,
    val externalLinks: List<ExternalLink>? = null
): BaseAttributes()