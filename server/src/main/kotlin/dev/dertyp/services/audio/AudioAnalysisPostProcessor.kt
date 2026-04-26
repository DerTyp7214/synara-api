package dev.dertyp.services.audio

import dev.dertyp.data.SongAudioData
import dev.dertyp.services.EssentiaOutput

interface AudioAnalysisPostProcessor {
    fun process(essentiaOutput: EssentiaOutput, currentData: SongAudioData): SongAudioData
}
