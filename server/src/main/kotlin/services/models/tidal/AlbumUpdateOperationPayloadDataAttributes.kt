package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class AlbumUpdateOperationPayloadDataAttributes(
    val copyright: Copyright? = null,
    val explicitLyrics: Boolean? = null,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val title: String? = null,
    val version: String? = null
): BaseAttributes()