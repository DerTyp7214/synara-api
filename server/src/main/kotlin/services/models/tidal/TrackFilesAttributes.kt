package dev.dertyp.services.models.tidal

import kotlinx.serialization.Serializable

@Serializable
data class TrackFilesAttributes(
    val albumAudioNormalizationData: AudioNormalizationData? = null,
    val format: Format? = null,
    val trackAudioNormalizationData: AudioNormalizationData? = null,
    val trackPresentation: TrackPresentation? = null,
    val url: String? = null
): BaseAttributes() {
    @Suppress("unused")
    enum class Format {
        HEAACV1,
        AACLC,
        FLAC,
        FLAC_HIRES,
        EAC3_JOC;
    }

    @Suppress("unused")
    enum class TrackPresentation {
        FULL,
        PREVIEW;
    }
}

