package dev.dertyp.core

import dev.dertyp.data.Image
import net.coobird.thumbnailator.Thumbnails
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.Path
import kotlin.io.path.readBytes

fun Image.sized(size: Int): ByteArray {
    val outputStream = ByteArrayOutputStream()

    val data = Path(path).readBytes()

    return try {
        Thumbnails
            .of(ByteArrayInputStream(data))
            .size(size, size)
            .outputFormat("jpeg")
            .toOutputStream(outputStream)

        outputStream.toByteArray()
    } catch (_: Throwable) {
        outputStream.close()
        data
    }
}