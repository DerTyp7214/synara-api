package dev.dertyp.services.cover.render

import io.ktor.util.logging.KtorSimpleLogger
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D

object CoverTypography {
    private val logger = KtorSimpleLogger("CoverTypography")

    val bundledFont: Font? by lazy {
        runCatching {
            CoverTypography::class.java.getResourceAsStream("/fonts/CoverTitle.ttf")?.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        }.onFailure { logger.warn("Bundled cover font unavailable: ${it.message}") }.getOrNull()
    }

    fun draw(g: Graphics2D, size: Int, title: String, font: Font?) {
        try {
            val base = font ?: bundledFont ?: Font(Font.SANS_SERIF, Font.BOLD, 10)
            val padding = size / 16
            val maxWidth = size - 2 * padding
            var fontSize = size / 9f
            var lines: List<String>
            while (true) {
                g.font = base.deriveFont(Font.BOLD, fontSize)
                lines = wrap(g, title, maxWidth)
                if (lines.size <= 2 || fontSize <= size / 24f) break
                fontSize *= 0.9f
            }
            if (lines.size > 2) lines = lines.take(2).let { listOf(it[0], it[1].trimEnd() + "…") }
            val metrics = g.fontMetrics
            val lineHeight = metrics.height
            val blockHeight = lineHeight * lines.size
            val scrimTop = size - blockHeight - padding * 3
            g.paint = GradientPaint(0f, scrimTop.toFloat(), Color(0, 0, 0, 0), 0f, size.toFloat(), Color(0, 0, 0, 190))
            g.fillRect(0, scrimTop, size, size - scrimTop)

            var y = size - padding - blockHeight + metrics.ascent
            for (line in lines) {
                g.color = Color(0, 0, 0, 120)
                g.drawString(line, padding + size / 200, y + size / 200)
                g.color = Color.WHITE
                g.drawString(line, padding, y)
                y += lineHeight
            }
        } catch (t: Throwable) {
            logger.warn("Skipping cover title rendering: ${t.message}")
        }
    }

    private fun wrap(g: Graphics2D, text: String, maxWidth: Int): List<String> {
        val metrics = g.fontMetrics
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = ArrayList<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (metrics.stringWidth(candidate) <= maxWidth || current.isEmpty()) {
                current = StringBuilder(candidate)
            } else {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }
}
