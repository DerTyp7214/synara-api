package dev.dertyp.data

import dev.dertyp.serializers.UUIDListSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class Playlist(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val name: String,
    @Serializable(with = UUIDListSerializer::class)
    val songs: List<UUID>,
    @Serializable(with = UUIDSerializer::class)
    val imageId: UUID? = null,
)

@Serializable
data class PlaylistEntry(
    @Serializable(with = UUIDSerializer::class)
    val id: UUID,
    val name: String,
    val duration: Long,
)

@Serializable
data class InsertablePlaylist(
    val name: String,
    val songPaths: List<String>,
    val imageHash: String? = null,
)