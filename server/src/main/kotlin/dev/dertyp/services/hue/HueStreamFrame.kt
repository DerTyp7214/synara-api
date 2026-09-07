package dev.dertyp.services.hue

data class HueChannelColor(val channel: Int, val r: Int, val g: Int, val b: Int)

object HueStreamFrame {
    const val PROTOCOL = "HueStream"
    const val VERSION_MAJOR = 0x02
    const val VERSION_MINOR = 0x00
    const val COLOR_SPACE_RGB = 0x00
    const val COLOR_SPACE_XY = 0x01
    const val HEADER_SIZE = 52
    const val CHANNEL_SIZE = 7
    const val MAX_CHANNELS = 20
    const val MAX_VALUE = 0xFFFF
    const val CONFIGURATION_ID_LENGTH = 36

    fun encode(
        configurationId: String,
        sequence: Int,
        channels: List<HueChannelColor>,
        colorSpace: Int = COLOR_SPACE_RGB,
    ): ByteArray {
        require(configurationId.length == CONFIGURATION_ID_LENGTH) {
            "Entertainment configuration id must be $CONFIGURATION_ID_LENGTH characters"
        }
        require(channels.size <= MAX_CHANNELS) { "An entertainment frame carries at most $MAX_CHANNELS channels" }
        val frame = ByteArray(HEADER_SIZE + channels.size * CHANNEL_SIZE)
        PROTOCOL.toByteArray(Charsets.US_ASCII).copyInto(frame, 0)
        frame[9] = VERSION_MAJOR.toByte()
        frame[10] = VERSION_MINOR.toByte()
        frame[11] = (sequence and 0xFF).toByte()
        frame[14] = colorSpace.toByte()
        configurationId.toByteArray(Charsets.US_ASCII).copyInto(frame, 16)
        channels.forEachIndexed { index, color ->
            val offset = HEADER_SIZE + index * CHANNEL_SIZE
            frame[offset] = color.channel.toByte()
            writeValue(frame, offset + 1, color.r)
            writeValue(frame, offset + 3, color.g)
            writeValue(frame, offset + 5, color.b)
        }
        return frame
    }

    fun rgb8To16(value: Int): Int = value.coerceIn(0, 255) * 257

    private fun writeValue(frame: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(0, MAX_VALUE)
        frame[offset] = ((clamped shr 8) and 0xFF).toByte()
        frame[offset + 1] = (clamped and 0xFF).toByte()
    }
}

class HueStreamEncoder(private val configurationId: String) {
    private var sequence = 0

    fun next(channels: List<HueChannelColor>): ByteArray {
        val frame = HueStreamFrame.encode(configurationId, sequence, channels)
        sequence = (sequence + 1) % 256
        return frame
    }
}
