@file:UseContextualSerialization(UUID::class)

package dev.dertyp.core.wire.fixtures

import dev.dertyp.data.RepeatMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

@Serializable
data class OldWireCredit(
    val id: UUID,
    val name: String,
    val artists: List<OldWireCredit> = listOf(),
    val musicbrainzId: UUID? = null,
    val joinPhrase: String? = null,
)

@Serializable
data class OldWireAlbum(
    val id: UUID,
    val name: String,
    val artists: List<OldWireCredit>,
    val musicbrainzId: UUID? = null,
)

@Serializable
data class OldWireSong(
    val id: UUID,
    val title: String,
    val artists: List<OldWireCredit>,
    val album: OldWireAlbum?,
    val duration: Long,
    val musicBrainzId: UUID? = null,
)

@Serializable
data class OldWirePlaybackState(
    val queue: List<QueueEntry>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    val shuffleMode: Boolean,
    val repeatMode: RepeatMode,
    val sourceId: String? = null,
) {
    @Serializable
    sealed class QueueEntry {
        abstract val queueId: Long

        @Serializable
        @SerialName("FromSource")
        data class FromSource(val songId: UUID, override val queueId: Long) : QueueEntry()

        @Serializable
        @SerialName("Explicit")
        data class Explicit(val song: OldWireSong, override val queueId: Long) : QueueEntry()
    }
}

@Serializable
data class OldWirePlaybackReport(
    val songId: UUID,
    val positionMs: Long = 0,
    val playing: Boolean = true,
    val sentAt: Long? = null,
)

@Serializable
data class OldWireQueueInfo(
    val version: Long,
    val modifiedAt: Long,
    val currentIndex: Int,
    val shuffleMode: Boolean,
    val repeatMode: RepeatMode,
    val sourceId: String? = null,
    val total: Int,
)

@Serializable
data class OldWireQueueItem(
    val songId: UUID,
    val queueId: Long,
    val position: Int,
    val shuffledPosition: Int? = null,
    val explicit: Boolean = false,
    val song: OldWireSong? = null,
)

@Serializable
sealed class OldWireQueueWriteResult {
    abstract val info: OldWireQueueInfo

    @Serializable
    @SerialName("Ok")
    data class Ok(override val info: OldWireQueueInfo) : OldWireQueueWriteResult()

    @Serializable
    @SerialName("Conflict")
    data class Conflict(override val info: OldWireQueueInfo) : OldWireQueueWriteResult()
}

@Serializable
data class OldWireSongMatch(
    val song: OldWireSong,
    val explicitMember: Boolean,
)
