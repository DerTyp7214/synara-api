package dev.dertyp.core

import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.models.tidal.ArtistsAttributes
import dev.dertyp.services.models.tidal.TracksResourceObject

fun TracksResourceObject.artists(artists: Map<String, ArtistsAttributes>) =
    relationships?.artists?.data?.mapNotNull { data ->
        artists[data.id]?.name
    } ?: emptyList()

fun TracksResourceObject.images(images: Map<String, List<MetadataService.Image>>) =
    images[relationships?.albums?.data?.firstOrNull()?.id] ?: emptyList()

suspend fun TracksResourceObject.singleImage(imageFetcher: suspend (String) -> List<MetadataService.Image>) =
    relationships?.albums?.data?.firstOrNull()?.let {
        imageFetcher(it.id)
    } ?: emptyList()