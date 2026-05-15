package dev.dertyp.utils

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.ScaleMethod

object ImageUtils {
    data class Pixel(val r: Int, val g: Int, val b: Int, val argb: Int)

    fun extractColors(image: ByteArray, width: Int, height: Int): List<Pixel> {
        val img = ImmutableImage.loader().fromBytes(image).scaleTo(width, height, ScaleMethod.FastScale)
        return (0 until img.height).flatMap { y ->
            (0 until img.width).map { x ->
                val p = img.pixel(x, y)
                Pixel(p.red(), p.green(), p.blue(), p.toARGBInt())
            }
        }
    }
}
