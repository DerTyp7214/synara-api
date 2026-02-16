package dev.dertyp.core

import dev.dertyp.data.Image
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.coobird.thumbnailator.Thumbnails
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.readBytes

fun Image.bytes(): ByteArray = Path(path).readBytes()

fun Image.sized(size: Int): ByteArray {
    val outputStream = ByteArrayOutputStream()

    val data = bytes()

    if (size == -1) return data

    return try {
        Thumbnails
            .of(ByteArrayInputStream(data))
            .size(size, size)
            .outputFormat(when (Path(path).extension) {
                "jpg" -> "jpeg"
                "jpeg" -> "jpeg"
                "png" -> "png"
                else -> "jpeg"
            })
            .toOutputStream(outputStream)
        outputStream.toByteArray()
    } catch (_: Throwable) {
        outputStream.close()
        data
    }
}

suspend fun RoutingCall.respondImageSized(image: Image, size: Int) {
    val sizedImage = image.sized(size)

    val contentType = when (Path(image.path).extension) {
        "jpg" -> ContentType.Image.JPEG
        "jpeg" -> ContentType.Image.JPEG
        "png" -> ContentType.Image.PNG
        else -> ContentType.Image.JPEG
    }

    try {
        respondBytes(sizedImage, contentType)
    } catch (e: Throwable) {
        e.printStackTrace()
        respondBytes(sizedImage, contentType)
    }
}