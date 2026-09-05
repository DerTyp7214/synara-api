package dev.dertyp.services.cover.render

import dev.dertyp.data.CoverStyle
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.PI

object CollageLayout {
    fun draw(g: Graphics2D, size: Int, tiles: List<BufferedImage>, style: CoverStyle, random: Random) {
        if (tiles.isEmpty()) return
        when (style) {
            CoverStyle.SINGLE -> single(g, size, tiles.first())
            CoverStyle.GRID -> grid(g, size, tiles, 2, random)
            CoverStyle.MOSAIC -> grid(g, size, tiles, 3, random)
            CoverStyle.STACKED -> stacked(g, size, tiles, random)
            CoverStyle.GRADIENT, CoverStyle.AUTO -> Unit
        }
    }

    private fun single(g: Graphics2D, size: Int, tile: BufferedImage) {
        CoverRenderer.drawCoverFit(g, tile, 0, 0, size, size)
    }

    private fun grid(g: Graphics2D, size: Int, tiles: List<BufferedImage>, columns: Int, random: Random) {
        val cells = columns * columns
        val ordered = tiles.take(cells)
        val gap = if (columns == 2) size / 64 else size / 96
        val margin = size / 24
        val inner = size - 2 * margin - gap * (columns - 1)
        val cell = inner / columns
        val offsetX = margin + (size - 2 * margin - (cell * columns + gap * (columns - 1))) / 2
        val offsetY = offsetX
        ordered.forEachIndexed { index, tile ->
            val col = index % columns
            val row = index / columns
            val x = offsetX + col * (cell + gap)
            val y = offsetY + row * (cell + gap)
            shadow(g, x, y, cell, cell, size / 80)
            CoverRenderer.drawCoverFit(g, tile, x, y, cell, cell, random.nextInt(4) == 0)
        }
    }

    private fun stacked(g: Graphics2D, size: Int, tiles: List<BufferedImage>, random: Random) {
        val shown = tiles.take(3)
        val card = (size * 0.56).toInt()
        val positions = when (shown.size) {
            1 -> listOf(0.5 to 0.5)
            2 -> listOf(0.38 to 0.42, 0.62 to 0.58)
            else -> listOf(0.32 to 0.38, 0.68 to 0.42, 0.5 to 0.62)
        }
        shown.forEachIndexed { index, tile ->
            val (px, py) = positions[index]
            val angle = (random.nextDouble() * 16 - 8) * PI / 180
            val cx = px * size
            val cy = py * size
            val transform = AffineTransform()
            transform.translate(cx, cy)
            transform.rotate(angle)
            transform.translate(-card / 2.0, -card / 2.0)
            val old = g.transform
            g.transform(transform)
            shadow(g, 0, 0, card, card, size / 40)
            g.color = Color(255, 255, 255, 230)
            val border = size / 100
            g.fillRect(-border, -border, card + 2 * border, card + 2 * border)
            CoverRenderer.drawCoverFit(g, tile, 0, 0, card, card)
            g.transform = old
        }
    }

    private fun shadow(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, spread: Int) {
        if (spread <= 0) return
        val steps = 6
        for (i in steps downTo 1) {
            val offset = spread * i / steps
            g.color = Color(0, 0, 0, 14)
            g.fillRoundRect(x - offset + spread / 2, y - offset + spread, w + 2 * offset, h + 2 * offset, offset * 2, offset * 2)
        }
    }
}
