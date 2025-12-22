package dev.dertyp.core

import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.models.tidal.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
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

val List<MetadataService.Image>.largest
    get() = maxByOrNull { it.width } ?: first()

fun List<MetadataService.Playlist>.toFlow(): Flow<MetadataService.FlowPlaylist> =
    map { MetadataService.FlowPlaylist.fromPlaylist(it) }.asFlow()

@Suppress("UNCHECKED_CAST")
fun <K, V> List<Pair<K, V?>>.filterValueNotNull(): List<Pair<K, V>> = filter { (_, v) -> v != null } as List<Pair<K, V>>

@Suppress("UNCHECKED_CAST")
fun <K, V> List<Pair<K?, V>>.filterKeyNotNull(): List<Pair<K, V>> = filter { (k, _) -> k != null } as List<Pair<K, V>>

@Suppress("UNCHECKED_CAST")
fun <K, V> List<Pair<K?, V?>>.filterNotNull(): List<Pair<K, V>> = filter { (k, v) -> k != null && v != null } as List<Pair<K, V>>

val <K, V> List<Pair<K, V>>.keys
    get() = map { it.first }
val <K, V> List<Pair<K, V>>.values
    get() = map { it.second }

fun <T> Iterable<T>.minusOnce(other: Iterable<T>): List<T> {
    val result = toMutableList()
    other.forEach { result.remove(it) }
    return result
}