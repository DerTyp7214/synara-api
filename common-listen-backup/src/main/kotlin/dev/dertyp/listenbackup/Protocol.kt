@file:UseSerializers(UuidSerializer::class)

package dev.dertyp.listenbackup

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

object ListenBackupProtocol {
    const val KEY_HEADER = "X-Backup-Key"
    const val HEALTH_PATH = "/health"
    const val STATUS_PATH = "/status"
    const val LISTENS_PATH = "/listens"
    const val SERVER_ID_PARAM = "serverId"
}

@Serializable
data class BackupListen(
    val id: UUID,
    val userId: UUID?,
    val songId: UUID?,
    val recordingMbid: UUID?,
    val recordingMsid: UUID?,
    val releaseMbid: UUID?,
    val isrcs: String?,
    val artistMbids: String?,
    val trackName: String?,
    val artistName: String?,
    val releaseName: String?,
    val listenedAt: Long,
    val msPlayed: Long?,
    val updatedAt: Long,
)

@Serializable
data class ListenBackupBatch(
    val serverId: UUID,
    val listens: List<BackupListen>,
)

@Serializable
data class ListenBackupBatchResult(val received: Int)

@Serializable
data class ListenBackupStatus(
    val serverId: UUID?,
    val listenCount: Long,
    val lastReceivedAt: Long?,
    val maxUpdatedAt: Long?,
)

@Serializable
data class ListenBackupHealth(
    val ok: Boolean = true,
    val listenCount: Long,
)
