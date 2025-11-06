package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class AlbumCreateOperationPayloadDataAttributes(
    val title: String,
    val copyright: Copyright? = null,
    val explicitLyrics: Boolean? = null,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val upc: String? = null,
    val version: String? = null
): BaseAttributes()