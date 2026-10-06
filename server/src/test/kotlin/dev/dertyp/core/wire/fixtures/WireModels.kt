@file:UseContextualSerialization(UUID::class)

package dev.dertyp.core.wire.fixtures

import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.RepeatMode
import dev.dertyp.rpc.annotations.LegacyWireName
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import java.util.UUID

@Serializable
data class WireCredit(
    val id: UUID,
    val name: String,
    val artists: List<WireCredit> = listOf(),
    @LegacyWireName("musicbrainzId")
    val musicBrainzId: UUID? = null,
    val joinPhrase: String? = null,
)

@Serializable
data class WireAlbum(
    val id: UUID,
    val name: String,
    val artists: List<WireCredit>,
    @LegacyWireName("musicbrainzId")
    val musicBrainzId: UUID? = null,
)

@Serializable
data class WireSong(
    val id: UUID,
    val title: String,
    val artists: List<WireCredit>,
    val album: WireAlbum?,
    val duration: Long,
    val musicBrainzId: UUID? = null,
)

@Serializable
data class WirePlaybackState(
    val queue: List<QueueEntry>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val positionMs: Long,
    @LegacyWireName("shuffleMode")
    val isShuffled: Boolean,
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
        @SerialName("WithSong")
        @LegacyWireName("Explicit")
        data class WithSong(val song: WireSong, override val queueId: Long) : QueueEntry()
    }
}

@Serializable
data class WirePlaybackReport(
    val songId: UUID,
    val positionMs: Long = 0,
    @LegacyWireName("playing")
    val isPlaying: Boolean = true,
    val sentAt: Long? = null,
)

@Serializable
data class WireQueueInfo(
    val version: Long,
    val modifiedAt: Long,
    val currentIndex: Int,
    @LegacyWireName("shuffleMode")
    val isShuffled: Boolean,
    val repeatMode: RepeatMode,
    val sourceId: String? = null,
    val total: Int,
)

@Serializable
data class WireQueueMeta(
    val currentIndex: Int,
    @LegacyWireName("shuffleMode")
    val isShuffled: Boolean,
    val repeatMode: RepeatMode,
    val sourceId: String? = null,
)

@Serializable
data class WireQueueItem(
    val songId: UUID,
    val queueId: Long,
    val position: Int,
    val shuffledPosition: Int? = null,
    @LegacyWireName("explicit")
    val userAdded: Boolean = false,
    val song: WireSong? = null,
)

@Serializable
sealed class WireQueueWriteResult {
    abstract val info: WireQueueInfo

    @Serializable
    @SerialName("Ok")
    data class Ok(override val info: WireQueueInfo) : WireQueueWriteResult()

    @Serializable
    @SerialName("Conflict")
    data class Conflict(override val info: WireQueueInfo) : WireQueueWriteResult()
}

@Serializable
sealed class WireQueueUploadStart {
    @Serializable
    @SerialName("Started")
    data class Started(val uploadId: UUID) : WireQueueUploadStart()

    @Serializable
    @SerialName("Conflict")
    data class Conflict(val info: WireQueueInfo) : WireQueueUploadStart()
}

@Serializable
data class WireSongMatch(
    val song: WireSong,
    @LegacyWireName("explicitMember")
    val directMember: Boolean,
)

@Rpc
interface IWireQueueService {
    suspend fun getQueueInfo(): WireQueueInfo

    suspend fun getQueue(page: Int, pageSize: Int): PaginatedResponse<WireQueueItem>

    fun observeQueue(): Flow<WireQueueInfo>

    suspend fun insert(baseVersion: Long, index: Int, items: List<WireQueueItem>): WireQueueWriteResult

    suspend fun beginUpload(baseVersion: Long): WireQueueUploadStart

    suspend fun commitUpload(uploadId: UUID, meta: WireQueueMeta): WireQueueWriteResult
}
