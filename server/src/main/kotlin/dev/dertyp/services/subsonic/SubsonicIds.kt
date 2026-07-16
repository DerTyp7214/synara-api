package dev.dertyp.services.subsonic

import dev.dertyp.core.toUUIDOrNull
import java.util.UUID

sealed class SubsonicId(val uuid: UUID) {
    class Song(uuid: UUID) : SubsonicId(uuid)
    class Album(uuid: UUID) : SubsonicId(uuid)
    class Artist(uuid: UUID) : SubsonicId(uuid)
    class Playlist(uuid: UUID) : SubsonicId(uuid)
    class Image(uuid: UUID) : SubsonicId(uuid)
    class RadioChannel(uuid: UUID) : SubsonicId(uuid)

    companion object {
        fun parse(raw: String?): SubsonicId? {
            if (raw == null || raw.length < 4 || raw[2] != '-') return null
            val uuid = raw.substring(3).toUUIDOrNull() ?: return null
            return when (raw.substring(0, 2)) {
                "tr" -> Song(uuid)
                "al" -> Album(uuid)
                "ar" -> Artist(uuid)
                "pl" -> Playlist(uuid)
                "im" -> Image(uuid)
                "rc" -> RadioChannel(uuid)
                else -> null
            }
        }
    }
}

fun UUID.trId() = "tr-$this"
fun UUID.alId() = "al-$this"
fun UUID.arId() = "ar-$this"
fun UUID.plId() = "pl-$this"
fun UUID.imId() = "im-$this"
fun UUID.rcId() = "rc-$this"
