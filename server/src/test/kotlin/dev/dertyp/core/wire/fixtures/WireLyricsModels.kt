@file:UseContextualSerialization(UUID::class)
@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.core.wire.fixtures

import dev.dertyp.core.wire.LegacyFieldSource
import dev.dertyp.core.wire.LegacyWire
import dev.dertyp.rpc.annotations.LegacyWireField
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

const val FIXTURE_LYRICS_UNTIL = 10

@Serializable
@LegacyWireField("lyrics", untilApiVersion = FIXTURE_LYRICS_UNTIL)
data class WireLyricsSong(
    val id: UUID,
    val title: String,
    val album: WireAlbum?,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val lyricsInfo: String? = null,
)

@Serializable
data class OldWireLyricsSong(
    val id: UUID,
    val title: String,
    val album: OldWireAlbum?,
    val lyrics: String = "",
)

@Serializable
@LegacyWireField("lyrics", untilApiVersion = FIXTURE_LYRICS_UNTIL + 1)
data class WireMisdeclaredLyricsSong(val id: UUID)

private fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-0000-0000-%012d".format(n))

val fixtureLyrics: Map<UUID, String> = mapOf(uuid(21) to "First line\nSecond line", uuid(22) to "")

val lyricsAlbum = WireAlbum(id = uuid(23), name = "Album", artists = emptyList(), musicBrainzId = uuid(24))

val oldLyricsAlbum = OldWireAlbum(id = uuid(23), name = "Album", artists = emptyList(), musicbrainzId = uuid(24))

val newLyricsSongs = listOf(
    WireLyricsSong(id = uuid(21), title = "Sung", album = lyricsAlbum),
    WireLyricsSong(id = uuid(22), title = "Quiet", album = null),
)

val oldLyricsSongs = listOf(
    OldWireLyricsSong(id = uuid(21), title = "Sung", album = oldLyricsAlbum, lyrics = "First line\nSecond line"),
    OldWireLyricsSong(id = uuid(22), title = "Quiet", album = null, lyrics = ""),
)

val FixtureLyricsSource = LegacyFieldSource("lyrics", FIXTURE_LYRICS_UNTIL) {
    fixtureLyrics.getValue((it as WireLyricsSong).id)
}

val FixtureRenamesAndLyricsWire = LegacyWire(emptyList(), renames = true, fields = listOf(FixtureLyricsSource))

val FixtureLyricsOnlyWire = LegacyWire(emptyList(), renames = false, fields = listOf(FixtureLyricsSource))
