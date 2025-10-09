package dev.dertyp.core

import dev.dertyp.data.Image
import net.coobird.thumbnailator.Thumbnails
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

fun Image.sized(size: Int): ByteArray {
    val outputStream = ByteArrayOutputStream()

    return try {
        Thumbnails
            .of(ByteArrayInputStream(this.data))
            .size(size, size)
            .outputFormat("jpeg")
            .toOutputStream(outputStream)

        outputStream.toByteArray()
    } catch (_: Throwable) {
        outputStream.close()
        data
    }
}