package dev.dertyp.core

import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.models.tidal.ArtistsAttributes
import dev.dertyp.services.models.tidal.TracksResourceObject

fun TracksResourceObject.artists(artists: Map<String, ArtistsAttributes>) =
    relationships?.artists?.data?.mapNotNull { data ->
        artists[data.id]?.name
    } ?: emptyList()

fun TracksResourceObject.images(images: Map<String, List<IMetadataService.Image>>) =
    images[relationships?.albums?.data?.firstOrNull()?.id] ?: emptyList()

suspend fun TracksResourceObject.singleImage(imageFetcher: suspend (String) -> List<IMetadataService.Image>) =
    relationships?.albums?.data?.firstOrNull()?.let {
        imageFetcher(it.id)
    } ?: emptyList()