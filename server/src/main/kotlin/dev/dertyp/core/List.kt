@file:JvmName("ServerList")

package dev.dertyp.core

import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.models.tidal.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.decodeFromJsonElement

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

val List<IMetadataService.Image>.largest
    get() = maxByOrNull { it.width } ?: first()

fun List<IMetadataService.Playlist>.toFlow(): Flow<IMetadataService.FlowPlaylist> =
    map { IMetadataService.FlowPlaylist.fromPlaylist(it) }.asFlow()
