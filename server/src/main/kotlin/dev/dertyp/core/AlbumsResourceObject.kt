package dev.dertyp.core

import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.models.tidal.AlbumsResourceObject
import dev.dertyp.services.models.tidal.ArtistsAttributes
import dev.dertyp.services.models.tidal.ArtworksAttributes

fun AlbumsResourceObject.artists(artists: Map<String, ArtistsAttributes>) =
    relationships?.artists?.data?.mapNotNull { data ->
        artists[data.id]?.name
    } ?: emptyList()

fun AlbumsResourceObject.images(artworks: Map<String, ArtworksAttributes>) =
    relationships?.coverArt?.data?.flatMap { data ->
        artworks[data.id]?.files?.map {
            IMetadataService.Image(
                url = it.href,
                width = it.meta.width,
                height = it.meta.height
            )
        } ?: emptyList()
    } ?: emptyList()