package dev.dertyp.core

import dev.dertyp.data.InsertableAlbum

fun Map<InsertableAlbum, Any>.getAlbum(album: InsertableAlbum): InsertableAlbum? {
    for (key in keys) {
        if (key.contentEquals(album)) return key
    }

    return null
}

fun Map<InsertableAlbum, Any>.hasAlbum(album: InsertableAlbum): Boolean {
    return getAlbum(album) != null
}

fun <K, V> Map<K, V>.flip(): Map<V, K> = map { (key, value) -> Pair(value, key) }.toMap()

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, V?>.filterValueNotNull(): Map<K, V> = filter { (_, v) -> v != null } as Map<K, V>