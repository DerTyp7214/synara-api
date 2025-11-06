package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class TrackUpdateOperationPayloadDataAttributes(
    val accessType: AccessType? = null,
    val bpm: Float? = null,
    val explicit: Boolean? = null,
    val key: Key? = null,
    val keyScale: KeyScale? = null,
    val title: String? = null,
    val toneTags: List<String>? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class AccessType {
        PUBLIC,
        UNLISTED,
        PRIVATE;
    }

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
}

