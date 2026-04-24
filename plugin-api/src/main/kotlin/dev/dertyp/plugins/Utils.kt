package dev.dertyp.plugins

import kotlin.math.ln
import kotlin.math.pow

fun Number.toHumanReadableSize(): String {
    val bytes = this.toLong()
    if (bytes <= 0) return "0 Bytes"

    val units = arrayOf("Bytes", "KB", "MB", "GB", "TB", "PB", "EB")
    val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val size = bytes / 1024.0.pow(i.toDouble())
    val unit = units.getOrElse(i) { units.last() }

    return "%.1f %s".format(size, unit)
}
