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