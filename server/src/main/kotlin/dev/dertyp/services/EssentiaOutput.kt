package dev.dertyp.services

import dev.dertyp.data.AudioScale
import dev.dertyp.data.SongAudioData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EssentiaOutput(
    @SerialName("lowlevel")
    val lowLevel: LowLevel? = null,
    @SerialName("rhythm")
    val rhythm: Rhythm? = null,
    @SerialName("tonal")
    val tonal: Tonal? = null,
    @SerialName("metadata")
    val metadata: Metadata? = null
) {
    fun toSongAudioData() = SongAudioData(
        bpm = rhythm?.bpm,
        key = tonal?.keyEdma?.key,
        scale = tonal?.keyEdma?.scale,
        loudness = lowLevel?.loudnessEbu128?.integrated,
        energy = lowLevel?.spectralEnergy?.mean,
        danceability = rhythm?.danceability,
        composer = metadata?.tags?.composer,
        lyricist = metadata?.tags?.lyricist,
        producers = metadata?.tags?.producer
    )

    @Serializable
    data class Metadata(
        @SerialName("tags")
        val tags: Tags? = null
    )

    @Serializable
    data class Tags(
        @SerialName("composer")
        val composer: List<String>? = null,
        @SerialName("lyricist")
        val lyricist: List<String>? = null,
        @SerialName("producer")
        val producer: List<String>? = null
    )

    @Serializable
    data class LowLevel(
        @SerialName("loudness_ebu128")
        val loudnessEbu128: LoudnessEbu128? = null,
        @SerialName("spectral_energy")
        val spectralEnergy: Statistics? = null,
        @SerialName("average_loudness")
        val averageLoudness: Double? = null,
        @SerialName("dissonance")
        val dissonance: Statistics? = null,
        @SerialName("dynamic_complexity")
        val dynamicComplexity: Double? = null
    )

    @Serializable
    data class LoudnessEbu128(
        @SerialName("integrated")
        val integrated: Double? = null,
        @SerialName("loudness_range")
        val loudnessRange: Double? = null
    )

    @Serializable
    data class Statistics(
        @SerialName("mean")
        val mean: Double? = null,
        @SerialName("stdev")
        val stdev: Double? = null,
        @SerialName("min")
        val min: Double? = null,
        @SerialName("max")
        val max: Double? = null
    )

    @Serializable
    data class Rhythm(
        @SerialName("bpm")
        val bpm: Double? = null,
        @SerialName("danceability")
        val danceability: Double? = null,
        @SerialName("beats_position")
        val beatsPosition: List<Double>? = null,
        @SerialName("beats_count")
        val beatsCount: Double? = null,
        @SerialName("onset_rate")
        val onsetRate: Double? = null,
        @SerialName("beats_loudness")
        val beatsLoudness: Statistics? = null
    )

    @Serializable
    data class Tonal(
        @SerialName("key_edma")
        val keyEdma: KeyEdma? = null
    )

    @Serializable
    data class KeyEdma(
        @SerialName("key")
        val key: String? = null,
        @SerialName("scale")
        val scale: AudioScale? = null
    )
}
