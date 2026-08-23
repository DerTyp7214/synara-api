package dev.dertyp.audio

import io.ktor.server.config.ApplicationConfig

data class AudioConfig(
    val losslessFormat: LosslessFormat = LosslessFormat.FLAC,
)

fun ApplicationConfig.toAudioConfig(): AudioConfig = AudioConfig(
    losslessFormat = LosslessFormat.parse(propertyOrNull("audio.losslessFormat")?.getString()),
)
