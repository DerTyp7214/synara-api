package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.DurationSerializer
import dev.dertyp.serializers.LocalDateSerializer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.time.Duration

@Serializable
data class AlbumsAttributes(
    val barcodeId: String,
    @Serializable(with = DurationSerializer::class)
    val duration: Duration,
    val explicit: Boolean,
    val mediaTags: List<String>,
    val numberOfItems: Int,
    val numberOfVolumes: Int,
    val popularity: Double,
    val title: String,
    val type: Type,
    val availability: List<Availability>? = null,
    val copyright: Copyright? = null,
    val externalLinks: List<ExternalLink>? = null,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val version: String? = null
): BaseAttributes() {
    enum class Type {
        ALBUM,
        EP,
        SINGLE;
    }

    enum class Availability {
        STREAM,
        DJ,
        STEM;
    }
}

