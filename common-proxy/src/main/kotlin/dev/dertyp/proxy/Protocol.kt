package dev.dertyp.proxy

import io.ktor.http.Headers
import io.ktor.websocket.Frame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.UUID

@Serializable
data class ConnectionMetadata(val uri: String, val headers: Map<String, String>)

@Serializable
data class InstanceInfo(val id: String, val name: String?)

enum class HttpResetReason(val code: Int) {
    CANCEL(0),
    REFUSED(1),
    INTERNAL(2),
    PROTOCOL(3),
    HEAD_TOO_LARGE(4);

    companion object {
        fun fromCode(code: Int): HttpResetReason = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

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

    data class HttpRequest(
        override val clientId: UUID,
        val method: String,
        val uri: String,
        val headers: Headers,
        val noBody: Boolean
    ) : ProxyMessage()

    data class HttpResponse(
        override val clientId: UUID,
        val status: Int,
        val headers: Headers,
        val noBody: Boolean
    ) : ProxyMessage()

    data class HttpBody(
        override val clientId: UUID,
        val data: ByteArray,
        val last: Boolean
    ) : ProxyMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as HttpBody

            if (last != other.last) return false
            if (clientId != other.clientId) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = last.hashCode()
            result = 31 * result + clientId.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    data class HttpReset(override val clientId: UUID, val reason: HttpResetReason) : ProxyMessage()

    data class HttpWindow(override val clientId: UUID, val increment: Long) : ProxyMessage()

    fun toFrame(): Frame {
        val type = when (this) {
            is NewClient -> 0
            is ClientFrame -> 1
            is ClientDisconnected -> 2
            is AssignedId -> 3
            is Ping -> 4
            is Pong -> 5
            is HttpRequest -> 6
            is HttpResponse -> 7
            is HttpBody -> 8
            is HttpReset -> 9
            is HttpWindow -> 10
        }
        val subType = when (this) {
            is ClientFrame -> if (isBinary) 1 else 0
            is HttpRequest -> if (noBody) 1 else 0
            is HttpResponse -> if (noBody) 1 else 0
            is HttpBody -> if (last) 1 else 0
            is HttpReset -> reason.code
            else -> 0
        }
        val payload = when (this) {
            is NewClient -> Json.encodeToString(ConnectionMetadata.serializer(), ConnectionMetadata(uri, headers))
                .toByteArray()

            is ClientFrame -> data
            is AssignedId -> id.toByteArray()
            is HttpRequest -> encodeRequestHead(method, uri, headers)
            is HttpResponse -> encodeResponseHead(status, headers)
            is HttpBody -> {
                require(data.size <= HttpTunnel.BODY_CHUNK_SIZE) { "Body chunk of ${data.size} bytes exceeds ${HttpTunnel.BODY_CHUNK_SIZE}" }
                data
            }

            is HttpWindow -> {
                require(increment in 0..MAX_U32) { "Window increment $increment does not fit into 32 bits" }
                ByteBuffer.allocate(4).putInt(increment.toInt()).array()
            }

            else -> ByteArray(0)
        }

        val bytes = ByteBuffer.allocate(PREFIX_SIZE + payload.size)
        bytes.put(type.toByte())
        bytes.putLong(clientId.mostSignificantBits)
        bytes.putLong(clientId.leastSignificantBits)
        bytes.put(subType.toByte())
        bytes.put(payload)

        return Frame.Binary(true, bytes.array())
    }

    companion object {
        private const val PREFIX_SIZE = 1 + 16 + 1
        private const val MAX_U8 = 0xFF
        private const val MAX_U16 = 0xFFFF
        private const val MAX_U32 = 0xFFFFFFFFL

        fun fromFrame(frame: Frame): ProxyMessage? {
            if (frame !is Frame.Binary) return null
            val buffer = ByteBuffer.wrap(frame.data)
            if (buffer.remaining() < PREFIX_SIZE) return null
            return try {
                decode(buffer)
            } catch (_: BufferUnderflowException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun decode(buffer: ByteBuffer): ProxyMessage? {
            val type = buffer.get().toInt()
            val msb = buffer.getLong()
            val lsb = buffer.getLong()
            val clientId = UUID(msb, lsb)
            val subType = buffer.get().toInt() and MAX_U8

            return when (type) {
                0 -> {
                    val metadata = Json.decodeFromString(
                        ConnectionMetadata.serializer(),
                        String(buffer.take(buffer.remaining().toLong()))
                    )
                    NewClient(clientId, metadata.uri, metadata.headers)
                }

                1 -> ClientFrame(clientId, buffer.take(buffer.remaining().toLong()), subType == 1)
                2 -> ClientDisconnected(clientId)
                3 -> AssignedId(String(buffer.take(buffer.remaining().toLong())))
                4 -> Ping
                5 -> Pong
                6 -> {
                    val method = String(buffer.take(buffer.u8().toLong()), Charsets.US_ASCII)
                    val uri = String(buffer.take(buffer.u32()), Charsets.UTF_8)
                    val headers = buffer.headers()
                    if (method.isEmpty() || buffer.hasRemaining()) null
                    else HttpRequest(clientId, method, uri, headers, subType and 1 == 1)
                }

                7 -> {
                    val status = buffer.u16()
                    val headers = buffer.headers()
                    if (buffer.hasRemaining()) null else HttpResponse(clientId, status, headers, subType and 1 == 1)
                }

                8 -> {
                    if (buffer.remaining() > HttpTunnel.BODY_CHUNK_SIZE) null
                    else HttpBody(clientId, buffer.take(buffer.remaining().toLong()), subType and 1 == 1)
                }

                9 -> if (buffer.hasRemaining()) null else HttpReset(clientId, HttpResetReason.fromCode(subType))
                10 -> {
                    val increment = buffer.u32()
                    if (buffer.hasRemaining()) null else HttpWindow(clientId, increment)
                }

                else -> null
            }
        }

        internal fun isMethodEncodable(method: String): Boolean =
            method.length in 1..MAX_U8 && method.all { it.code < 0x80 }

        internal fun requestHeadSize(method: String, uri: String, headers: Headers): Int =
            1 + method.length + 4 + uri.toByteArray(Charsets.UTF_8).size + headersSize(headers)

        internal fun responseHeadSize(headers: Headers): Int = 2 + headersSize(headers)

        private fun headersSize(headers: Headers): Int {
            var size = 2
            headers.forEach { name, values ->
                val nameSize = name.toByteArray(Charsets.UTF_8).size
                values.forEach { size += 2 + nameSize + 4 + it.toByteArray(Charsets.UTF_8).size }
            }
            return size
        }

        private fun encodeRequestHead(method: String, uri: String, headers: Headers): ByteArray {
            require(isMethodEncodable(method)) { "Method of ${method.length} characters is not 1..$MAX_U8 ASCII characters" }
            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            val uriBytes = uri.toByteArray(Charsets.UTF_8)
            data.writeByte(method.length)
            data.write(method.toByteArray(Charsets.US_ASCII))
            data.writeInt(uriBytes.size)
            data.write(uriBytes)
            data.writeHeaders(headers)
            return out.toByteArray()
        }

        private fun encodeResponseHead(status: Int, headers: Headers): ByteArray {
            require(status in 0..MAX_U16) { "Status $status does not fit into 16 bits" }
            val out = ByteArrayOutputStream()
            val data = DataOutputStream(out)
            data.writeShort(status)
            data.writeHeaders(headers)
            return out.toByteArray()
        }

        private fun DataOutputStream.writeHeaders(headers: Headers) {
            val entries = headers.entries().flatMap { (name, values) -> values.map { name to it } }
            require(entries.size <= MAX_U16) { "Header count ${entries.size} exceeds $MAX_U16" }
            writeShort(entries.size)
            entries.forEach { (name, value) ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                val valueBytes = value.toByteArray(Charsets.UTF_8)
                require(nameBytes.size <= MAX_U16) { "Header name of ${nameBytes.size} bytes exceeds $MAX_U16" }
                writeShort(nameBytes.size)
                write(nameBytes)
                writeInt(valueBytes.size)
                write(valueBytes)
            }
        }

        private fun ByteBuffer.u8(): Int = get().toInt() and MAX_U8

        private fun ByteBuffer.u16(): Int = getShort().toInt() and MAX_U16

        private fun ByteBuffer.u32(): Long = getInt().toLong() and MAX_U32

        private fun ByteBuffer.take(length: Long): ByteArray {
            if (length > remaining()) throw BufferUnderflowException()
            return ByteArray(length.toInt()).also { get(it) }
        }

        private fun ByteBuffer.headers(): Headers {
            val count = u16()
            return Headers.build {
                repeat(count) {
                    val name = String(take(u16().toLong()), Charsets.UTF_8)
                    append(name, String(take(u32()), Charsets.UTF_8))
                }
            }
        }
    }
}
