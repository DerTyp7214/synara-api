package dev.dertyp.core

import java.io.Serializable


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