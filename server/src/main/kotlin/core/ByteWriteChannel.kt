package dev.dertyp.core

import io.ktor.utils.io.*
import kotlinx.io.IOException

suspend fun ByteWriteChannel.isClientConnected(): Boolean = try {
    writeStringUtf8("")
    flush()
    true
} catch (_: IOException) {
    false
} catch (_: NullPointerException) {
    false
}