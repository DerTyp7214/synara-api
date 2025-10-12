package dev.dertyp.core

import java.io.Serializable
import kotlin.math.ln
import kotlin.math.pow


data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
) : Serializable {
    override fun toString(): String = "($first, $second, $third, $fourth)"
}


fun <T, K> List<T>.duplicatesBy(keySelector: (T) -> K): List<T> {
    return this.groupBy(keySelector)
        .filterValues { it.size > 1 }
        .values
        .flatten()
}

fun Number.toHumanReadableSize(): String {
    val bytes = this.toLong()
    if (bytes <= 0) return "0 Bytes"

    val units = arrayOf("Bytes", "KB", "MB", "GB", "TB", "PB", "EB")
    val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
    val size = bytes / 1024.0.pow(i.toDouble())
    val unit = units.getOrElse(i) { units.last() }

    return "%.1f %s".format(size, unit)
}