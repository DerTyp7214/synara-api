package dev.dertyp.proxy

import io.ktor.websocket.Frame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.UUID

@Serializable
data class ConnectionMetadata(val uri: String, val headers: Map<String, String>)

@Serializable
data class InstanceInfo(val id: String, val name: String?)

sealed class ProxyMessage {
    abstract val clientId: UUID

    data class NewClient(
        override val clientId: UUID,
        val uri: String,
        val headers: Map<String, String>
    ) : ProxyMessage()

    data class ClientFrame(
        override val clientId: UUID,
        val data: ByteArray,
        val isBinary: Boolean
    ) : ProxyMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ClientFrame

            if (isBinary != other.isBinary) return false
            if (clientId != other.clientId) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = isBinary.hashCode()
            result = 31 * result + clientId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class ClientDisconnected(override val clientId: UUID) : ProxyMessage()
    data class AssignedId(val id: String) : ProxyMessage() {
        override val clientId: UUID = UUID(0, 0)
    }

    object Ping : ProxyMessage() {
        override val clientId: UUID = UUID(0, 0)
    }

    object Pong : ProxyMessage() {
        override val clientId: UUID = UUID(0, 0)
    }

    fun toFrame(): Frame {
        val type = when (this) {
            is NewClient -> 0
            is ClientFrame -> 1
            is ClientDisconnected -> 2
            is AssignedId -> 3
            is Ping -> 4
            is Pong -> 5
        }
        val metadataBytes = when (this) {
            is NewClient -> Json.encodeToString(ConnectionMetadata.serializer(), ConnectionMetadata(uri, headers)).toByteArray()
            is AssignedId -> id.toByteArray()
            else -> ByteArray(0)
        }

        val frameDataSize = if (this is ClientFrame) data.size else 0
        val bytes = ByteBuffer.allocate(1 + 16 + 1 + metadataBytes.size + frameDataSize)
        bytes.put(type.toByte())
        bytes.putLong(clientId.mostSignificantBits)
        bytes.putLong(clientId.leastSignificantBits)
        
        // Use subType byte for frame type preservation
        if (this is ClientFrame) {
            bytes.put(if (isBinary) 1.toByte() else 0.toByte())
            bytes.put(data)
        } else {
            bytes.put(0.toByte())
            bytes.put(metadataBytes)
        }
        
        return Frame.Binary(true, bytes.array())
    }

    companion object {
        fun fromFrame(frame: Frame): ProxyMessage? {
            if (frame !is Frame.Binary) return null
            val buffer = ByteBuffer.wrap(frame.data)
            if (!buffer.hasRemaining()) return null
            val type = buffer.get().toInt()
            if (buffer.remaining() < 16) return null
            val msb = buffer.getLong()
            val lsb = buffer.getLong()
            val clientId = UUID(msb, lsb)
            val subType = buffer.get().toInt()
            
            return when (type) {
                0 -> {
                    val metadata = Json.decodeFromString(ConnectionMetadata.serializer(), String(ByteArray(buffer.remaining()).also { buffer.get(it) }))
                    NewClient(clientId, metadata.uri, metadata.headers)
                }
                1 -> {
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    ClientFrame(clientId, data, subType == 1)
                }
                2 -> ClientDisconnected(clientId)
                3 -> AssignedId(String(ByteArray(buffer.remaining()).also { buffer.get(it) }))
                4 -> Ping
                5 -> Pong
                else -> null
            }
        }
    }
}
