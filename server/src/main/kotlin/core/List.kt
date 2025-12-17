package dev.dertyp.core

fun <T> MutableList<T>.removeFirst(condition: (T) -> Boolean): T? {
    val index = indexOfFirst(condition)
    if (index > -1) return removeAt(index)

    return null
}

fun <K, V> List<Map.Entry<K, V>>.toMap(): Map<K, V> {
    val map = mutableMapOf<K, V>()
    forEach { entry ->
        map[entry.key] = entry.value
    }
    return map
}

fun <T> MutableList<T>.splice(
    start: Int,
    deleteCount: Int,
    vararg items: T
): List<T> {
    val actualStart = if (start < 0) size + start else start

    val removedElements = if (deleteCount > 0) {
        this.subList(actualStart, (actualStart + deleteCount).coerceAtMost(size)).let {
            val removed = it.toList()
            it.clear()
            removed
        }
    } else {
        emptyList()
    }

    if (items.isNotEmpty()) {
        this.addAll(actualStart, items.toList())
    }

    return removedElements
}