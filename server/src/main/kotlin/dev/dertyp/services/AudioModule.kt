package dev.dertyp.services

import dev.dertyp.audio.AtmosProcessor
import dev.dertyp.audio.TranscodedSongRepository
import dev.dertyp.audio.Transcoder
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val audioModule = module {
    singleOf(::AudioAnalysisService)
    singleOf(::FlacAnalysisService)
    singleOf(::PcmAnalysisService)
    singleOf(::AudioStartAnalysisService)
    singleOf(::AtmosProcessor)
    singleOf(::Transcoder)
    singleOf(::TranscodedSongRepository)
}
