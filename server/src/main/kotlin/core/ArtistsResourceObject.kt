package dev.dertyp.core

import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.models.tidal.ArtistsResourceObject
import dev.dertyp.services.models.tidal.ArtworksAttributes

fun ArtistsResourceObject.images(artworks: Map<String, ArtworksAttributes>) =
    relationships?.profileArt?.data?.firstNotNullOfOrNull { data ->
        artworks[data.id]?.files?.map {
            MetadataService.Image(
                url = it.href,
                width = it.meta.width,
                height = it.meta.height
            )
        }
    } ?: emptyList()