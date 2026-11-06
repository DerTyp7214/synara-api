package dev.dertyp.utils

object Isrcs {
    fun normalize(isrc: String): String = isrc.filter { it.isLetterOrDigit() }.uppercase()
}
