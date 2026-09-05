package dev.dertyp.services.cover

import dev.dertyp.data.CoverStyle
import dev.dertyp.services.cover.render.CoverRenderSpec
import dev.dertyp.services.cover.render.CoverRenderer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class CoverRendererTest {
    private fun tile(color: Color, size: Int = 300): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = color
        g.fillRect(0, 0, size, size)
        g.dispose()
        return image
    }

    private val palette = listOf(0xFF2244AA.toInt(), 0xFFAA2244.toInt(), 0xFF44AA22.toInt())

    private fun spec(style: CoverStyle, tiles: Int, seed: Long = 42, title: String? = "Late Night Drive") = CoverRenderSpec(
        seed = seed,
        style = style,
        tiles = List(tiles) { tile(Color((25 * it + 30).coerceAtMost(255), 80, (200 - 20 * it).coerceAtLeast(0))) },
        palette = palette,
        title = title,
    )

    @ParameterizedTest
    @EnumSource(CoverStyle::class)
    fun `every style renders a decodable 1024 jpeg for any tile count`(style: CoverStyle) {
        for (tiles in listOf(0, 1, 2, 4, 9)) {
            val rendered = CoverRenderer.render(spec(style, tiles))
            assertEquals(1024, rendered.image.width)
            assertEquals(1024, rendered.image.height)
            val bytes = CoverRenderer.encodeJpeg(rendered.image)
            val decoded = ImageIO.read(ByteArrayInputStream(bytes))
            assertNotNull(decoded, "$style with $tiles tiles")
            assertEquals(1024, decoded.width)
        }
    }

    @Test
    fun `auto style resolves from the tile count`() {
        assertEquals(CoverStyle.GRADIENT, CoverRenderer.resolveStyle(CoverStyle.AUTO, 0))
        assertEquals(CoverStyle.SINGLE, CoverRenderer.resolveStyle(CoverStyle.AUTO, 1))
        assertEquals(CoverStyle.STACKED, CoverRenderer.resolveStyle(CoverStyle.AUTO, 3))
        assertEquals(CoverStyle.GRID, CoverRenderer.resolveStyle(CoverStyle.AUTO, 4))
        assertEquals(CoverStyle.MOSAIC, CoverRenderer.resolveStyle(CoverStyle.AUTO, 9))
        assertEquals(CoverStyle.GRADIENT, CoverRenderer.resolveStyle(CoverStyle.GRID, 0))
        assertEquals(CoverStyle.GRID, CoverRenderer.resolveStyle(CoverStyle.GRID, 2))
    }

    @Test
    fun `same seed renders identical bytes and a different seed differs`() {
        val first = CoverRenderer.encodeJpeg(CoverRenderer.render(spec(CoverStyle.STACKED, 3, seed = 7)).image)
        val second = CoverRenderer.encodeJpeg(CoverRenderer.render(spec(CoverStyle.STACKED, 3, seed = 7)).image)
        val other = CoverRenderer.encodeJpeg(CoverRenderer.render(spec(CoverStyle.STACKED, 3, seed = 8)).image)
        assertArrayEquals(first, second)
        assertFalse(first.contentEquals(other))
    }

    @Test
    fun `title rendering does not fail headless and changes the bottom of the image`() {
        System.setProperty("java.awt.headless", "true")
        val withTitle = CoverRenderer.render(spec(CoverStyle.GRADIENT, 0, title = "A Very Long Playlist Name That Needs Wrapping Onto Two Lines")).image
        val withoutTitle = CoverRenderer.render(spec(CoverStyle.GRADIENT, 0, title = null)).image
        var differing = 0
        for (x in 0 until 1024 step 8) for (y in 900 until 1024 step 8) {
            if (withTitle.getRGB(x, y) != withoutTitle.getRGB(x, y)) differing++
        }
        assertEquals(true, differing > 0)
    }

    @Test
    fun `background and overlay are composited`() {
        val background = tile(Color.MAGENTA, 512)
        val overlay = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB).also {
            val g = it.createGraphics()
            g.color = Color(0, 255, 0, 200)
            g.fillRect(0, 0, 512, 64)
            g.dispose()
        }
        val rendered = CoverRenderer.render(spec(CoverStyle.GRADIENT, 0, title = null).copy(background = background, overlay = overlay)).image
        val center = Color(rendered.getRGB(512, 600))
        assertEquals(true, center.red > 150 && center.blue > 150, "background should show through: $center")
    }
}
