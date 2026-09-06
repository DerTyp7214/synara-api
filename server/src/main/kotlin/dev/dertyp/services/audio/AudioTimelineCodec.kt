package dev.dertyp.services.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object AudioTimelineCodec {
    const val VERSION = 2
    private const val MAX_DELTA = 0xFFFF

    fun encodeBeats(positionsSec: List<Double>): ByteArray {
        val buffer = ByteBuffer.allocate(positionsSec.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var previousMs = 0
        for (position in positionsSec) {
            val ms = (position * 1000).roundToInt().coerceAtLeast(previousMs)
            val delta = (ms - previousMs).coerceAtMost(MAX_DELTA)
            buffer.putShort(delta.toShort())
            previousMs += delta
        }
        return buffer.array()
    }

    fun decodeBeats(bytes: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = IntArray(bytes.size / 2)
        var ms = 0
        for (i in result.indices) {
            ms += buffer.getShort().toInt() and MAX_DELTA
            result[i] = ms
        }
        return result
    }

    fun encodeEnvelope(db: FloatArray, minDb: Float, maxDb: Float): ByteArray {
        val range = (maxDb - minDb).takeIf { it > 0f } ?: 1f
        return ByteArray(db.size) { i ->
            val normalized = ((db[i] - minDb) / range).coerceIn(0f, 1f)
            (normalized * 255f).roundToInt().toByte()
        }
    }

    fun decodeEnvelope(bytes: ByteArray, minDb: Float, maxDb: Float): FloatArray {
        val range = maxDb - minDb
        return FloatArray(bytes.size) { i -> minDb + (bytes[i].toInt() and 0xFF) / 255f * range }
    }
}
