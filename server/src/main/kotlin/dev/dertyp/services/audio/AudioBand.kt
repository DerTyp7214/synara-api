package dev.dertyp.services.audio

import dev.dertyp.data.AudioBand

val AudioBand.lowHz: Int
    get() = when (this) {
        AudioBand.SUB -> 20
        AudioBand.KICK -> 60
        AudioBand.LOW_MID -> 130
        AudioBand.MID -> 400
        AudioBand.HIGH -> 2000
    }

val AudioBand.highHz: Int
    get() = when (this) {
        AudioBand.SUB -> 60
        AudioBand.KICK -> 130
        AudioBand.LOW_MID -> 400
        AudioBand.MID -> 2000
        AudioBand.HIGH -> 8000
    }
