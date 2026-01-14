package dev.dertyp.core

import io.ktor.utils.io.*
import kotlinx.io.IOException
import java.time.LocalTime
import java.time.format.DateTimeFormatter

suspend fun ByteWriteChannel.isClientConnected(): Boolean = try {
    writeStringUtf8("")
    flush()
    true
} catch (_: IOException) {
    false
} catch (_: NullPointerException) {
    false
}

suspend fun ByteWriteChannel.sendSafe(msg: String, message: String = "") = try {
    writeStringUtf8("event: $message${
        LocalTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME).split(".").first()
    }\ndata: $msg\n\n"
    )
    flush()
} catch (_: Throwable) {
}