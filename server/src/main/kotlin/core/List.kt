package dev.dertyp.core

fun <T> MutableList<T>.removeFirst(condition: (T) -> Boolean): T? {
    val index = indexOfFirst(condition)
    if (index > -1) return removeAt(index)

    return null
}