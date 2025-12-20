package dev.dertyp.core

import dev.dertyp.services.models.tidal.*
import kotlinx.serialization.json.decodeFromJsonElement

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

inline fun <reified F : BaseAttributes> List<IncludedInner<JsonAttribute, *>>.mapAttributes(): Map<String, F> =
    mapNotNull { included ->
        val attribute = when (included.type) {
            "artists" -> ApplicationScope.json.decodeFromJsonElement<ArtistsAttributes>(included.attributes.element)
            "albums" -> ApplicationScope.json.decodeFromJsonElement<AlbumsAttributes>(included.attributes.element)
            "tracks" -> ApplicationScope.json.decodeFromJsonElement<TracksAttributes>(included.attributes.element)
            "artworks" -> ApplicationScope.json.decodeFromJsonElement<ArtworksAttributes>(included.attributes.element)
            else -> null
        }

        if (attribute is F) included.id to attribute
        else null
    }.toMap()