package dev.dertyp.core

import java.text.Normalizer

private val combiningMarks = Regex("\\p{M}+")
private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

fun String.normalizedKey(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(combiningMarks, "")
        .replace(nonAlphanumeric, " ")
        .trim()
