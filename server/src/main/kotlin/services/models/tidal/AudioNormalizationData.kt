package dev.dertyp.services.models.tidal


import kotlinx.serialization.Serializable

@Serializable
data class AudioNormalizationData(
    val peakAmplitude: Float? = null,
    val replayGain: Float? = null
)