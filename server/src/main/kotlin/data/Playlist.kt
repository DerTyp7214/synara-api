package dev.dertyp.data

import dev.dertyp.serializers.UUIDListSerializer
import dev.dertyp.serializers.UUIDSerializer
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
sealed class BasePlaylist {
    @Serializable(with = UUIDSerializer::class)
    abstract val id: UUID
    abstract val name: String
    @Serializable(with = UUIDListSerializer::class)
    abstract val songs: List<UUID>
    abstract val totalDuration: Long
    @Serializable(with = UUIDSerializer::class)
    abstract val imageId: UUID?
}

@Serializable
data class Playlist(
    @Serializable(with = UUIDSerializer::class)
    override val id: UUID,
    override val name: String,
    @Serializable(with = UUIDListSerializer::class)
    override val songs: List<UUID>,
    override val totalDuration: Long = -1L,
    @Serializable(with = UUIDSerializer::class)
    override val imageId: UUID? = null,
): BasePlaylist()

@Serializable
data class UserPlaylist(
    @Serializable(with = UUIDSerializer::class)
    override val id: UUID,
    override val name: String,
    @Serializable(with = UUIDListSerializer::class)
    override val songs: List<UUID>,
    override val totalDuration: Long = -1L,
    @Serializable(with = UUIDSerializer::class)
    override val imageId: UUID? = null,
    @Serializable(with = UUIDSerializer::class)
    val creator: UUID,
    val description: String,
    val origin: String? = null,
): BasePlaylist()

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
    val description: String = "",
    val songPaths: List<String>,
    val imageHash: String? = null,
    val origin: String? = null,
)