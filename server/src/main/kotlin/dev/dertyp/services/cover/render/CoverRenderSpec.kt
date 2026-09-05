package dev.dertyp.services.cover.render

import dev.dertyp.data.CoverStyle
import java.awt.Font
import java.awt.image.BufferedImage

data class CoverRenderSpec(
    val seed: Long,
    val style: CoverStyle,
    val tiles: List<BufferedImage>,
    val palette: List<Int>,
    val background: BufferedImage? = null,
    val overlay: BufferedImage? = null,
    val title: String? = null,
    val font: Font? = null,
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        const val DEFAULT_SIZE = 1024
    }
}

data class RenderedCover(
    val image: BufferedImage,
    val style: CoverStyle,
)
