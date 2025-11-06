package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.DurationSerializer
import dev.dertyp.serializers.LocalDateSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import java.time.LocalDate

@Serializable
data class VideosAttributes(
    @Serializable(with = DurationSerializer::class)
    val duration: Duration,
    val explicit: Boolean,
    val isrc: String,
    val popularity: Double,
    val title: String,
    val availability: List<Availability>? = null,
    val copyright: Copyright? = null,
    val externalLinks: List<ExternalLink>? = null,
    @Serializable(with = LocalDateSerializer::class)
    val releaseDate: LocalDate? = null,
    val version: String? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class Availability {
        STREAM,
        DJ,
        STEM;
    }
}

