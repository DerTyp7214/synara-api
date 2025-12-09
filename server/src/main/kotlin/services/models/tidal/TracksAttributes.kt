package dev.dertyp.services.models.tidal

import dev.dertyp.serializers.DurationSerializer
import dev.dertyp.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import kotlin.time.Duration

@Serializable
data class TracksAttributes(
    @Serializable(with = DurationSerializer::class)
    val duration: Duration,
    val explicit: Boolean,
    val isrc: String,
    val key: Key? = null,
    val keyScale: KeyScale? = null,
    val mediaTags: List<String>,
    val popularity: Double,
    val title: String,
    val accessType: AccessType? = null,
    val availability: List<Availability>? = null,
    val bpm: Float? = null,
    val copyright: Copyright? = null,
    @Serializable(with = OffsetDateTimeSerializer::class)
    val createdAt: OffsetDateTime? = null,
    val externalLinks: List<ExternalLink>? = null,
    val spotlighted: Boolean? = null,
    val toneTags: List<String>? = null,
    val version: String? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class Key {
        UNKNOWN,
        C,
        CSharp,
        D,
        Eb,
        E,
        F,
        FSharp,
        G,
        Ab,
        A,
        Bb,
        B;
    }

    @Suppress("unused")
    enum class KeyScale {
        UNKNOWN,
        MAJOR,
        MINOR,
        AEOLIAN,
        BLUES,
        DORIAN,
        HARMONIC_MINOR,
        LOCRIAN,
        LYDIAN,
        MIXOLYDIAN,
        PENTATONIC_MAJOR,
        PHRYGIAN,
        MELODIC_MINOR,
        PENTATONIC_MINOR;
    }

    @Suppress("unused")
    enum class AccessType {
        PUBLIC,
        UNLISTED,
        PRIVATE;
    }

    @Suppress("unused")
    enum class Availability {
        STREAM,
        DJ,
        STEM;
    }
}

