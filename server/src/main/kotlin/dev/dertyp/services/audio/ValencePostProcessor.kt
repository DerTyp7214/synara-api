package dev.dertyp.services.audio

import dev.dertyp.data.AudioScale
import dev.dertyp.data.SongAudioData
import dev.dertyp.services.EssentiaOutput

class ValencePostProcessor : AudioAnalysisPostProcessor {
    override fun process(essentiaOutput: EssentiaOutput, currentData: SongAudioData): SongAudioData {
        val rawBpm = essentiaOutput.rhythm?.bpm
        val rawDanceability = essentiaOutput.rhythm?.danceability

        val calculatedArousal = if (rawBpm != null || rawDanceability != null) {
            val bpmArousal = (((rawBpm ?: SongAudioData.DEFAULT_BPM) - 60.0) / 100.0).coerceIn(0.0, 1.0)
            val danceArousal = (((rawDanceability ?: SongAudioData.DEFAULT_DANCEABILITY) - 0.5) / 2.0).coerceIn(0.0, 1.0)
            (bpmArousal * 0.4 + danceArousal * 0.6)
        } else null

        val scale = currentData.scale
        val rawDissonance = essentiaOutput.lowLevel?.dissonance?.mean

        val calculatedValence = if (scale != null || rawDissonance != null) {
            val valenceBaseline = when (scale) {
                AudioScale.Major -> 0.75
                AudioScale.Minor -> 0.25
                else -> 0.5
            }
            val dissonance = rawDissonance ?: SongAudioData.DEFAULT_DISSONANCE
            val dissonanceImpact = (dissonance * 0.8).coerceIn(0.0, 0.5)
            val danceArousal = (((rawDanceability ?: SongAudioData.DEFAULT_DANCEABILITY) - 0.5) / 2.0).coerceIn(0.0, 1.0)

            (valenceBaseline - dissonanceImpact + (danceArousal * 0.2)).coerceIn(0.0, 1.0)
        } else null

        return currentData.copy(
            energy = calculatedArousal ?: currentData.energy,
            valence = calculatedValence ?: currentData.valence
        )
    }
}
