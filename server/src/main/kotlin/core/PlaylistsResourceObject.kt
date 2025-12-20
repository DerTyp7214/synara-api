package dev.dertyp.core

import dev.dertyp.services.metadata.MetadataService
import dev.dertyp.services.models.tidal.ArtworksAttributes
import dev.dertyp.services.models.tidal.PlaylistsResourceObject
import dev.dertyp.services.models.tidal.TracksAttributes

fun PlaylistsResourceObject<*, *>.images(artworks: Map<String, ArtworksAttributes>) =
    relationships?.coverArt?.data?.firstNotNullOfOrNull { data ->
        artworks[data.id]?.files?.map {
            MetadataService.Image(
                url = it.href,
                width = it.meta.width,
                height = it.meta.height
            )
        }
    } ?: emptyList()

fun PlaylistsResourceObject<*, *>.tracks(tracks: Map<String, TracksAttributes>) =
    relationships?.items?.data?.mapNotNull { data ->
        tracks[data.id]?.let { track ->
            MetadataService.Track(
                id = data.id,
                title = track.title,
                duration = track.duration,
                createdAt = track.createdAt,
                images = emptyList(),
            )
        }
    } ?: emptyList()