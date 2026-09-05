package dev.dertyp.services.cover.render

import dev.dertyp.data.CoverStyle
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

object CoverRenderer {
    fun resolveStyle(style: CoverStyle, tileCount: Int): CoverStyle = when (style) {
        CoverStyle.AUTO -> when {
            tileCount == 0 -> CoverStyle.GRADIENT
            tileCount == 1 -> CoverStyle.SINGLE
            tileCount <= 3 -> CoverStyle.STACKED
            tileCount <= 8 -> CoverStyle.GRID
            else -> CoverStyle.MOSAIC
        }
        CoverStyle.GRADIENT -> CoverStyle.GRADIENT
        CoverStyle.MOSAIC -> if (tileCount >= 9) CoverStyle.MOSAIC else resolveStyle(CoverStyle.GRID, tileCount)
        CoverStyle.GRID -> if (tileCount >= 4) CoverStyle.GRID else resolveStyle(CoverStyle.STACKED, tileCount)
        CoverStyle.STACKED, CoverStyle.SINGLE -> if (tileCount == 0) CoverStyle.GRADIENT else style
    }

    fun render(spec: CoverRenderSpec): RenderedCover {
        val size = spec.size
        val random = Random(spec.seed)
        val style = resolveStyle(spec.style, spec.tiles.size)
        val canvas = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        val g = canvas.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val background = spec.background
            if (background != null) {
                drawCoverFit(g, background, 0, 0, size, size, random.nextBoolean())
                if (style != CoverStyle.GRADIENT && style != CoverStyle.SINGLE) {
                    g.color = Color(0, 0, 0, 70)
                    g.fillRect(0, 0, size, size)
                }
            } else {
                ProceduralBackground.draw(g, size, spec.palette, random)
            }

            CollageLayout.draw(g, size, spec.tiles, style, random)

            spec.overlay?.let { overlay ->
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f)
                drawCoverFit(g, overlay, 0, 0, size, size, random.nextBoolean())
                g.composite = old
            }

            drawVignette(g, size)

            spec.title?.takeIf { it.isNotBlank() }?.let { title ->
                CoverTypography.draw(g, size, title, spec.font)
            }
        } finally {
            g.dispose()
        }
        return RenderedCover(canvas, style)
    }

    fun encodeJpeg(image: BufferedImage, quality: Float = 0.9f): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val output = ByteArrayOutputStream()
        MemoryCacheImageOutputStream(output).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), params)
        }
        writer.dispose()
        return output.toByteArray()
    }

    internal fun drawCoverFit(g: Graphics2D, image: BufferedImage, x: Int, y: Int, w: Int, h: Int, flip: Boolean = false) {
        val scale = maxOf(w.toDouble() / image.width, h.toDouble() / image.height)
        val srcW = (w / scale).toInt().coerceIn(1, image.width)
        val srcH = (h / scale).toInt().coerceIn(1, image.height)
        val sx = (image.width - srcW) / 2
        val sy = (image.height - srcH) / 2
        if (flip) {
            g.drawImage(image, x + w, y, x, y + h, sx, sy, sx + srcW, sy + srcH, null)
        } else {
            g.drawImage(image, x, y, x + w, y + h, sx, sy, sx + srcW, sy + srcH, null)
        }
    }

    internal fun drawRotated(g: Graphics2D, image: BufferedImage, cx: Double, cy: Double, w: Int, h: Int, angleRad: Double) {
        val transform = AffineTransform()
        transform.translate(cx, cy)
        transform.rotate(angleRad)
        transform.translate(-w / 2.0, -h / 2.0)
        val old = g.transform
        g.transform(transform)
        drawCoverFit(g, image, 0, 0, w, h)
        g.transform = old
    }

    private fun drawVignette(g: Graphics2D, size: Int) {
        val center = size / 2f
        val paint = RadialGradientPaint(
            center, center, size * 0.75f,
            floatArrayOf(0f, 0.6f, 1f),
            arrayOf(Color(0, 0, 0, 0), Color(0, 0, 0, 30), Color(0, 0, 0, 110)),
        )
        val old = g.paint
        g.paint = paint
        g.fillRect(0, 0, size, size)
        g.paint = old
    }
}
