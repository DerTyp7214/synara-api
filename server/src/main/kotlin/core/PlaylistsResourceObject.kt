package dev.dertyp.core

import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.models.tidal.ArtworksAttributes
import dev.dertyp.services.models.tidal.PlaylistsResourceObject
import dev.dertyp.services.models.tidal.TracksAttributes

fun PlaylistsResourceObject<*, *>.images(artworks: Map<String, ArtworksAttributes>) =
    relationships?.coverArt?.data?.firstNotNullOfOrNull { data ->
        artworks[data.id]?.files?.map {
            IMetadataService.Image(
                url = it.href,
                width = it.meta.width,
                height = it.meta.height
            )
        }
    } ?: emptyList()

fun PlaylistsResourceObject<*, *>.tracks(tracks: Map<String, TracksAttributes>) =
    relationships?.items?.data?.mapNotNull { data ->
        tracks[data.id]?.let { track ->
            IMetadataService.Track(
                id = data.id,
                title = track.title,
                duration = track.duration,
                createdAt = track.createdAt,
                images = emptyList(),
            )
        }
    } ?: emptyList()