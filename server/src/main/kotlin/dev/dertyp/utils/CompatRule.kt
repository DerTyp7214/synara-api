package dev.dertyp.utils

import dev.dertyp.core.ClientFeature
import dev.dertyp.core.ClientInfo
import dev.dertyp.data.Song
import dev.dertyp.data.UserSong
import dev.dertyp.ui.UiComponent

interface CompatRule {
    val feature: ClientFeature
    fun isActive(client: ClientInfo): Boolean = !client.supports(feature)
    fun shapeSong(song: Song): Song = song
    fun shapeUserSong(song: UserSong): UserSong = song
    fun shapeUiComponent(component: UiComponent, client: ClientInfo): UiComponent = component
}

@Suppress("DEPRECATION")
object AudioInfoCompat : CompatRule {
    override val feature = ClientFeature.AUDIO_INFO

    override fun shapeSong(song: Song): Song = song.copy(
        sampleRate = song.audio?.sampleRate ?: 0,
        bitsPerSample = song.audio?.bitsPerSample ?: 0,
        bitRate = song.audio?.bitRate ?: 0,
        fileSize = song.audio?.fileSize ?: 0,
        atmosPath = song.atmosVariantPath,
        audio = null,
        atmos = null,
    )

    override fun shapeUserSong(song: UserSong): UserSong = song.copy(
        sampleRate = song.audio?.sampleRate ?: 0,
        bitsPerSample = song.audio?.bitsPerSample ?: 0,
        bitRate = song.audio?.bitRate ?: 0,
        fileSize = song.audio?.fileSize ?: 0,
        atmosPath = song.atmosVariantPath,
        audio = null,
        atmos = null,
    )
}

@Suppress("DEPRECATION")
object DolbyAtmosCompat : CompatRule {
    override val feature = ClientFeature.DOLBY_ATMOS

    override fun shapeSong(song: Song): Song = song.copy(atmosPath = null)

    override fun shapeUserSong(song: UserSong): UserSong = song.copy(atmosPath = null)
}

object CompatRules {
    val all: List<CompatRule> = listOf(AudioInfoCompat, DolbyAtmosCompat, UiSchemaCompat())
}
